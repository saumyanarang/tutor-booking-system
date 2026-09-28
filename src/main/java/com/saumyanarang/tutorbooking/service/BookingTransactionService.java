package com.saumyanarang.tutorbooking.service;

import com.saumyanarang.tutorbooking.entity.Booking;
import com.saumyanarang.tutorbooking.entity.Slot;
import com.saumyanarang.tutorbooking.entity.User;
import com.saumyanarang.tutorbooking.exception.BookingNotAvailableException;
import com.saumyanarang.tutorbooking.repository.BookingRepository;
import com.saumyanarang.tutorbooking.repository.TimeSlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The database part of a booking, kept in its own bean on purpose.
 *
 * The Redis lock must be taken BEFORE this transaction starts and released AFTER it commits.
 * Because BookingService calls this through Spring's proxy, the commit happens when this method
 * returns, i.e. before BookingService releases the lock.
 */
@Service
@RequiredArgsConstructor
public class BookingTransactionService {

    private final TimeSlotRepository timeSlotRepository;
    private final BookingRepository bookingRepository;

    @Transactional
    public Booking createBooking(User user, Long slotId) {
        Slot slot = timeSlotRepository.findById(slotId)
                .orElseThrow(() -> new BookingNotAvailableException("Time slot no longer exists"));

        if (slot.isReserved()) {
            throw new BookingNotAvailableException("Time slot already reserved");
        }

        slot.setReserved(true);
        timeSlotRepository.save(slot);

        Booking booking = new Booking();
        booking.setUser(user);
        booking.setSlot(slot);
        booking.setReservedAt(LocalDateTime.now());

        // saveAndFlush so the UNIQUE(slot_id) backstop fires here, inside the transaction
        return bookingRepository.saveAndFlush(booking);
    }
}
