package com.saumyanarang.tutorbooking.service;

import com.saumyanarang.tutorbooking.entity.Booking;
import com.saumyanarang.tutorbooking.entity.Slot;
import com.saumyanarang.tutorbooking.entity.User;
import com.saumyanarang.tutorbooking.exception.BusinessException;
import com.saumyanarang.tutorbooking.exception.DuplicateBookingException;
import com.saumyanarang.tutorbooking.exception.BookingCapacityExceededException;
import com.saumyanarang.tutorbooking.exception.BookingNotAvailableException;
import com.saumyanarang.tutorbooking.repository.BookingRepository;
import com.saumyanarang.tutorbooking.repository.TimeSlotRepository;
import com.saumyanarang.tutorbooking.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.annotation.Recover;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Service for handling booking operations such as reserving, cancelling, and finding available slots.
 */
@Service
@RequiredArgsConstructor
public class BookingService {

    private final TimeSlotRepository timeSlotRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final MeterRegistry meterRegistry;
    private final CacheableOperations cacheableOperations;

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final Logger logger = LoggerFactory.getLogger(BookingService.class);

    /**
     * Finds and caches the next available time slot.
     * This method delegates to the CacheableOperations interface to ensure proper caching.
     *
     * @return Optional containing the next available TimeSlot, or empty if none found.
     */
    public Optional<Slot> findNextSlotCached() {
        return cacheableOperations.findNextSlotCached(LocalDateTime.now());
    }

    /**
     * Reserves the nearest available time slot for the given user email with optimistic locking
     * to handle concurrency. Retries up to MAX_RETRY_ATTEMPTS times if booking fails due to
     * concurrent modification by another transaction.
     *
     * @param email the user's email
     * @return the created Booking
     * @throws BusinessException if the user is not found or no available time slots exist
     */
    @Retryable(
        value = OptimisticLockingFailureException.class,
        maxAttempts = 3,
        backoff = @Backoff(delay = 10, multiplier = 1.5)
    )
    @Transactional
    public Booking reserveNearestSlot(String email) {
        logger.info("Attempting to reserve nearest slot for user: {}", email);
        try {
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> {
                        logger.warn("User not found for email: {}", email);
                        return new BusinessException("User not found for email: " + email);
                    });
            logger.debug("Found user: id={}, email={}", user.getId(), user.getEmail());

            // Check if user already has a pending booking
            if (bookingRepository.existsByUserEmailAndStartTimeAfter(email, LocalDateTime.now())) {
                logger.warn("Duplicate booking attempt detected for user: {}", email);
                throw new DuplicateBookingException("User already has an active booking");
            }

            Booking booking = attemptBooking(user);
            logger.info("Successfully created booking: id={} for user={} at time={}",
                    booking.getId(), email, booking.getSlot().getStartTime());
            meterRegistry.counter("booking.success").increment();
            return booking;
        } catch (BusinessException e) {
            logger.error("Failed to create booking for user: {}. Reason: {}", email, e.getMessage());
            meterRegistry.counter("booking.failed").increment();
            throw e;
        }
    }

    /**
     * Recovery method for handling OptimisticLockingFailureException when all retry attempts are exhausted.
     * This method must match the signature expected by the @Retryable method but with the exception as the first parameter.
     *
     * @param e The OptimisticLockingFailureException that caused retries to fail
     * @param email The user's email (from the original method parameter)
     * @return Never returns a Booking, always throws an exception
     * @throws BookingCapacityExceededException when recovery is needed
     */
    @Recover
    public Booking recoverFromOptimisticLockingFailure(OptimisticLockingFailureException e, String email) {
        logger.error("Failed to reserve slot after {} attempts due to concurrent modifications", MAX_RETRY_ATTEMPTS);
        meterRegistry.counter("booking.optimistic_locking_failures").increment();
        throw new BookingCapacityExceededException("Unable to reserve time slot due to high demand, please try again later");
    }

    /**
     * Helper method to perform a single booking attempt with optimistic locking.
     *
     * @param user the user making the booking
     * @return the created booking
     * @throws BookingNotAvailableException if no slots are available
     * @throws OptimisticLockingFailureException if concurrent modification is detected
     */
    @Transactional(noRollbackFor = OptimisticLockingFailureException.class)
    protected Booking attemptBooking(User user) {
        Slot slot = findNextSlotCached()
                .orElseThrow(() -> new BookingNotAvailableException("No available time slots"));

        // Double-check the slot is still available in current database state
        Slot freshSlot = timeSlotRepository.findById(slot.getId())
                .orElseThrow(() -> new BookingNotAvailableException("Time slot no longer exists"));

        if (freshSlot.isReserved()) {
            logger.warn("Concurrency issue: Slot {} is already reserved in database.", freshSlot.getId());
            evictNextSlotCache();
            throw new BookingNotAvailableException("Time slot already reserved");
        }

        freshSlot.setReserved(true);
        Slot savedSlot = timeSlotRepository.save(freshSlot);
        logger.info("Slot {} reserved for user {}", savedSlot.getId(), user.getEmail());

        evictNextSlotCache();

        Booking booking = new Booking();
        booking.setUser(user);
        booking.setSlot(savedSlot);
        booking.setReservedAt(LocalDateTime.now());

        Booking saved = bookingRepository.save(booking);
        logger.info("Booking {} created for user {} at slot {}", saved.getId(), user.getEmail(), savedSlot.getId());
        return saved;
    }

    /**
     * Cancels a booking by its ID and frees the associated time slot. Also evicts the cache.
     *
     * @param id the booking ID
     * @throws BusinessException if the booking is not found
     */
    @Transactional
    public void cancelBooking(Long id) {
        logger.info("Attempting to cancel booking with id: {}", id);
        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Booking not found for id: " + id));

        Slot slot = booking.getSlot();
        slot.setReserved(false);
        timeSlotRepository.save(slot);
        logger.info("Slot {} freed from booking {}", slot.getId(), id);

        bookingRepository.delete(booking);
        logger.info("Booking {} cancelled", id);

        meterRegistry.counter("booking.cancelled").increment();
        evictNextSlotCache();
    }

    /**
     * Evicts the cache entry for the next available slot.
     */
    private void evictNextSlotCache() {
        cacheableOperations.evictNextSlotCache();
    }
}
