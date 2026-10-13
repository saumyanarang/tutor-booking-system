package com.saumyanarang.tutorbooking.service;

import com.saumyanarang.tutorbooking.dto.booking.BookingDetailsDto;
import com.saumyanarang.tutorbooking.entity.Booking;
import com.saumyanarang.tutorbooking.entity.Role;
import com.saumyanarang.tutorbooking.entity.Slot;
import com.saumyanarang.tutorbooking.entity.User;
import com.saumyanarang.tutorbooking.exception.BookingCapacityExceededException;
import com.saumyanarang.tutorbooking.exception.BookingNotAvailableException;
import com.saumyanarang.tutorbooking.exception.BusinessException;
import com.saumyanarang.tutorbooking.exception.DuplicateBookingException;
import com.saumyanarang.tutorbooking.exception.ForbiddenOperationException;
import com.saumyanarang.tutorbooking.exception.InvalidBookingTimeException;
import com.saumyanarang.tutorbooking.exception.ResourceNotFoundException;
import com.saumyanarang.tutorbooking.exception.SlotUnavailableException;
import com.saumyanarang.tutorbooking.repository.BookingRepository;
import com.saumyanarang.tutorbooking.repository.TimeSlotRepository;
import com.saumyanarang.tutorbooking.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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
     * Books one specific slot for a student.
     * Deliberately NOT @Transactional: the Redis lock must wrap the whole DB transaction
     * (lock -> begin -> commit -> unlock), never the other way around.
     *
     * @throws ResourceNotFoundException if the slot does not exist
     * @throws ForbiddenOperationException if the caller is not a student
     * @throws SlotUnavailableException if the slot is taken, or is being taken by another request right now
     * @throws DuplicateBookingException if the student already has a lesson at an overlapping time
     */
    public Booking reserveSlot(String email, Long slotId) {
        logger.info("User {} is trying to book slot {}", email, slotId);
        try {
            User student = userRepository.findByEmail(email)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found for email: " + email));
            if (student.getRole() != Role.STUDENT) {
                throw new ForbiddenOperationException("Only students can book slots");
            }

            Slot slot = timeSlotRepository.findById(slotId)
                    .orElseThrow(() -> new ResourceNotFoundException("Slot not found: " + slotId));
            if (!slot.getStartTime().isAfter(LocalDateTime.now())) {
                throw new InvalidBookingTimeException("This slot has already started");
            }
            if (slot.isReserved()) {
                throw new SlotUnavailableException("This slot is already booked");
            }
            if (bookingRepository.existsOverlappingBookingForUser(student.getId(), slot.getStartTime(), slot.getEndTime())) {
                throw new DuplicateBookingException("You already have a booking that overlaps this slot");
            }

            // Layer 1: if another request holds the lock it is about to win (or lose) this slot; don't queue behind it.
            String token = slotLockService.tryLock(slotId);
            if (token == null) {
                throw new SlotUnavailableException("This slot is being booked by someone else right now");
            }
            try {
                Booking booking = bookingTransactionService.createBooking(student, slotId);
                logger.info("Booking {} created for user {} on slot {}", booking.getId(), email, slotId);
                meterRegistry.counter("booking.success").increment();
                return booking;
            } catch (BookingNotAvailableException | DataIntegrityViolationException | OptimisticLockingFailureException e) {
                // Layer 2 (slot flag re-check) or layer 3 (Postgres UNIQUE / version check) said no.
                logger.warn("Slot {} could not be booked ({})", slotId, e.getClass().getSimpleName());
                throw new SlotUnavailableException("This slot is already booked");
            } finally {
                slotLockService.unlock(slotId, token);
                evictNextSlotCache();
            }
        } catch (BusinessException e) {
            logger.warn("Failed to book slot {} for user {}. Reason: {}", slotId, email, e.getMessage());
            meterRegistry.counter("booking.failed").increment();
            throw e;
        }
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

    /**
     * A student's own bookings, or - for a tutor - the bookings made on that tutor's slots. Soonest first.
     */
    @Transactional(readOnly = true)
    public List<BookingDetailsDto> listMyBookings(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found for email: " + email));
        List<Booking> bookings = user.getRole() == Role.TUTOR
                ? bookingRepository.findAllForTutor(user.getId())
                : bookingRepository.findAllForStudent(user.getId());
        return bookings.stream().map(BookingDetailsDto::from).toList();
    }

    /**
     * Cancels a booking and frees its slot. Only the student who made the booking, or the tutor who owns
     * the slot, may cancel it.
     */
    @Transactional
    public void cancelBooking(Long id, String requesterEmail) {
        logger.info("User {} is attempting to cancel booking {}", requesterEmail, id);
        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Booking not found for id: " + id));

        Slot slot = booking.getSlot();
        boolean isBookingStudent = booking.getUser().getEmail().equalsIgnoreCase(requesterEmail);
        // Only look the requester up when they are not the booking's student (saves a query on the common path).
        if (!isBookingStudent && !isTutorOfSlot(slot, requesterEmail)) {
            throw new ForbiddenOperationException("You can only cancel your own bookings");
        }

        slot.setReserved(false);
        timeSlotRepository.save(slot);

        bookingRepository.delete(booking);
        logger.info("Booking {} cancelled, slot {} freed", id, slot.getId());

        meterRegistry.counter("booking.cancelled").increment();
        evictNextSlotCache();
    }

    private boolean isTutorOfSlot(Slot slot, String email) {
        if (slot.getTutorId() == null) {
            return false;   // legacy demo slot with no owner: nobody is its tutor
        }
        return userRepository.findByEmail(email)
                .map(user -> slot.getTutorId().equals(user.getId()))
                .orElse(false);
    }

    private void evictNextSlotCache() {
        cacheableOperations.evictNextSlotCache();
    }
}
