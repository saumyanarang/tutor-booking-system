package com.saumyanarang.tutorbooking.service;

import com.saumyanarang.tutorbooking.entity.Slot;
import com.saumyanarang.tutorbooking.entity.Booking;
import com.saumyanarang.tutorbooking.repository.BookingRepository;
import com.saumyanarang.tutorbooking.repository.TimeSlotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Service responsible for managing booking expirations and
 * automatically freeing up unclaimed slots.
 */
@Service
public class BookingExpiryService {

    private static final Logger logger = LoggerFactory.getLogger(BookingExpiryService.class);

    private final BookingRepository bookingRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final CacheableOperations cacheableOperations;

    @Value("${booking.expiry.hours:24}")
    private int expiryHours;

    public BookingExpiryService(
            BookingRepository bookingRepository,
            TimeSlotRepository timeSlotRepository,
            CacheableOperations cacheableOperations) {
        this.bookingRepository = bookingRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.cacheableOperations = cacheableOperations;
    }

    /**
     * Scheduled task that runs at a configured interval to detect and handle expired bookings.
     * Default is to run every 15 minutes.
     */
    @Scheduled(fixedDelayString = "${booking.expiry.check-minutes:15}000")
    @Transactional
    public void processExpiredBookings() {
        logger.info("Starting expired bookings check");

        LocalDateTime expirationThreshold = LocalDateTime.now().minusHours(expiryHours);
        List<Booking> expiredBookings =
            bookingRepository.findExpiredBookings(expirationThreshold);

        if (expiredBookings.isEmpty()) {
            logger.info("No expired bookings found");
            return;
        }

        logger.info("Found {} expired bookings to process", expiredBookings.size());

        for (Booking booking : expiredBookings) {
            try {
                // Free up the time slot
                Slot slot = booking.getSlot();
                slot.setReserved(false);
                timeSlotRepository.save(slot);

                // Delete the booking
                bookingRepository.delete(booking);

                logger.info("Expired booking deleted: id={}, user={}, slot={}",
                    booking.getId(), booking.getUser().getEmail(),
                    booking.getSlot().getId());

            } catch (Exception e) {
                logger.error("Error processing expired booking {}", booking.getId(), e);
            }
        }

        // Clear cache to reflect the newly available slots
        cacheableOperations.evictNextSlotCache();

        logger.info("Completed expired bookings cleanup, processed {} bookings",
            expiredBookings.size());
    }
}
