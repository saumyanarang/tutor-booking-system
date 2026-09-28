package com.saumyanarang.tutorbooking.service;

import com.saumyanarang.tutorbooking.entity.Booking;
import com.saumyanarang.tutorbooking.entity.Slot;
import com.saumyanarang.tutorbooking.entity.User;
import com.saumyanarang.tutorbooking.exception.BookingCapacityExceededException;
import com.saumyanarang.tutorbooking.exception.BookingNotAvailableException;
import com.saumyanarang.tutorbooking.exception.BusinessException;
import com.saumyanarang.tutorbooking.exception.DuplicateBookingException;
import com.saumyanarang.tutorbooking.repository.BookingRepository;
import com.saumyanarang.tutorbooking.repository.TimeSlotRepository;
import com.saumyanarang.tutorbooking.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Booking operations. Double-booking is prevented in three layers:
 *   1. Redis lock per slot (fast-fail, taken before the DB transaction)
 *   2. Re-check of slot.isReserved inside the transaction
 *   3. UNIQUE(slot_id) on the booking table (final backstop, enforced by Postgres)
 */
@Service
@RequiredArgsConstructor
public class BookingService {

    private final TimeSlotRepository timeSlotRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final MeterRegistry meterRegistry;
    private final CacheableOperations cacheableOperations;
    private final SlotLockService slotLockService;
    private final BookingTransactionService bookingTransactionService;

    private static final int MAX_CANDIDATE_SLOTS = 5;
    private static final Logger logger = LoggerFactory.getLogger(BookingService.class);

    public Optional<Slot> findNextSlotCached() {
        return cacheableOperations.findNextSlotCached(LocalDateTime.now());
    }

    /**
     * Books the nearest free slot for the user.
     * Deliberately NOT @Transactional: the Redis lock must wrap the whole DB transaction
     * (lock -> begin -> commit -> unlock), never the other way around.
     */
    public Booking reserveNearestSlot(String email) {
        logger.info("Attempting to book nearest slot for user: {}", email);
        try {
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new BusinessException("User not found for email: " + email));

            if (bookingRepository.existsByUserEmailAndStartTimeAfter(email, LocalDateTime.now())) {
                throw new DuplicateBookingException("User already has an active booking");
            }

            List<Slot> candidates = timeSlotRepository.findAvailable(
                    LocalDateTime.now(), PageRequest.of(0, MAX_CANDIDATE_SLOTS));
            if (candidates.isEmpty()) {
                throw new BookingNotAvailableException("No available time slots");
            }

            boolean sawLockedSlot = false;
            for (Slot candidate : candidates) {
                String token = slotLockService.tryLock(candidate.getId());
                if (token == null) {
                    sawLockedSlot = true;   // another request is booking this slot right now
                    continue;
                }
                try {
                    Booking booking = bookingTransactionService.createBooking(user, candidate.getId());
                    logger.info("Booking {} created for user {} on slot {}", booking.getId(), email, candidate.getId());
                    meterRegistry.counter("booking.success").increment();
                    return booking;
                } catch (BookingNotAvailableException | DataIntegrityViolationException e) {
                    // Slot was taken between our read and our lock (layer 2), or Postgres rejected it (layer 3).
                    logger.warn("Slot {} could not be booked ({}), trying next slot", candidate.getId(), e.getClass().getSimpleName());
                } finally {
                    slotLockService.unlock(candidate.getId(), token);
                    evictNextSlotCache();
                }
            }

            if (sawLockedSlot) {
                throw new BookingCapacityExceededException("Slots are being booked right now, please retry");
            }
            throw new BookingNotAvailableException("No available time slots");

        } catch (BusinessException e) {
            logger.error("Failed to create booking for user: {}. Reason: {}", email, e.getMessage());
            meterRegistry.counter("booking.failed").increment();
            throw e;
        }
    }

    @Transactional
    public void cancelBooking(Long id) {
        logger.info("Attempting to cancel booking with id: {}", id);
        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Booking not found for id: " + id));

        Slot slot = booking.getSlot();
        slot.setReserved(false);
        timeSlotRepository.save(slot);

        bookingRepository.delete(booking);
        logger.info("Booking {} cancelled, slot {} freed", id, slot.getId());

        meterRegistry.counter("booking.cancelled").increment();
        evictNextSlotCache();
    }

    private void evictNextSlotCache() {
        cacheableOperations.evictNextSlotCache();
    }
}
