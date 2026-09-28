package com.saumyanarang.tutorbooking.repository;

import com.saumyanarang.tutorbooking.entity.Slot;
import com.saumyanarang.tutorbooking.entity.Booking;
import com.saumyanarang.tutorbooking.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class BookingRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private BookingRepository bookingRepository;

    @Test
    void existsByUserEmailAndStartTimeAfter_shouldReturnTrueWhenFutureBookingExists() {
        // Given
        String email = "test@azki.com";
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime futureTime = now.plusHours(2);

        // Create user
        User user = new User();
        user.setEmail(email);
        user.setUserName("testuser");
        user.setPassword("password");
        entityManager.persist(user);

        // Create available slot
        Slot slot = new Slot();
        slot.setStartTime(futureTime);
        slot.setEndTime(futureTime.plusHours(1));
        slot.setReserved(true);
        entityManager.persist(slot);

        // Create booking
        Booking booking = new Booking();
        booking.setUser(user);
        booking.setSlot(slot);
        booking.setReservedAt(now);
        entityManager.persist(booking);

        entityManager.flush();

        // When
        boolean exists = bookingRepository.existsByUserEmailAndStartTimeAfter(email, now);

        // Then
        assertTrue(exists);
    }

    @Test
    void existsByUserEmailAndStartTimeAfter_shouldReturnFalseWhenNoFutureBookingExists() {
        // Given
        String email = "test@example.com";
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime pastTime = now.minusHours(2);

        // Create user
        User user = new User();
        user.setEmail(email);
        user.setUserName("testuser");
        user.setPassword("password");
        entityManager.persist(user);

        // Create available slot
        Slot slot = new Slot();
        slot.setStartTime(pastTime);
        slot.setEndTime(pastTime.plusHours(1));
        slot.setReserved(true);
        entityManager.persist(slot);

        // Create booking
        Booking booking = new Booking();
        booking.setUser(user);
        booking.setSlot(slot);
        booking.setReservedAt(pastTime);
        entityManager.persist(booking);

        entityManager.flush();

        // When
        boolean exists = bookingRepository.existsByUserEmailAndStartTimeAfter(email, now);

        // Then
        assertFalse(exists);
    }
}
