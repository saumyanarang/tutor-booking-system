package com.saumyanarang.tutorbooking.service;

import com.saumyanarang.tutorbooking.entity.Slot;
import com.saumyanarang.tutorbooking.entity.Booking;
import com.saumyanarang.tutorbooking.entity.User;
import com.saumyanarang.tutorbooking.exception.DuplicateBookingException;
import com.saumyanarang.tutorbooking.exception.BookingNotAvailableException;
import com.saumyanarang.tutorbooking.repository.BookingRepository;
import com.saumyanarang.tutorbooking.repository.TimeSlotRepository;
import com.saumyanarang.tutorbooking.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private TimeSlotRepository timeSlotRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CacheableOperations cacheableOperations;

    private MeterRegistry meterRegistry;

    @InjectMocks
    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        bookingService = new BookingService(
                timeSlotRepository,
                bookingRepository,
                userRepository,
                meterRegistry,
                cacheableOperations
        );
    }

    @Test
    void shouldFindNextSlot() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        Slot slot = new Slot();
        slot.setId(1L);
        slot.setStartTime(now.plusHours(1));
        slot.setEndTime(now.plusHours(2));
        slot.setReserved(false);

        when(timeSlotRepository.findNextAvailable(any(LocalDateTime.class)))
                .thenReturn(Optional.of(slot));

        // When
        Optional<Slot> result = bookingService.findNextSlotCached();

        // Then
        assertTrue(result.isPresent());
        assertEquals(slot.getId(), result.get().getId());
        assertEquals(slot.getStartTime(), result.get().getStartTime());
    }

    @Test
    void shouldReserveNearestSlot() {
        // Given
        String email = "test@example.com";
        LocalDateTime now = LocalDateTime.now();

        User user = new User();
        user.setId(1L);
        user.setEmail(email);

        Slot slot = new Slot();
        slot.setId(1L);
        slot.setStartTime(now.plusHours(1));
        slot.setEndTime(now.plusHours(2));
        slot.setReserved(false);

        Booking booking = new Booking();
        booking.setId(1L);
        booking.setUser(user);
        booking.setSlot(slot);

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(timeSlotRepository.findNextAvailable(any(LocalDateTime.class))).thenReturn(Optional.of(slot));
        when(timeSlotRepository.findById(slot.getId())).thenReturn(Optional.of(slot));
        when(timeSlotRepository.save(any(Slot.class))).thenReturn(slot);
        when(bookingRepository.save(any(Booking.class))).thenReturn(booking);
        when(bookingRepository.existsByUserEmailAndStartTimeAfter(anyString(), any(LocalDateTime.class))).thenReturn(false);

        // When
        Booking result = bookingService.reserveNearestSlot(email);

        // Then
        assertNotNull(result);
        assertEquals(1L, result.getId());
        verify(timeSlotRepository).save(any(Slot.class));
        verify(bookingRepository).save(any(Booking.class));
    }

    @Test
    void shouldThrowExceptionWhenUserAlreadyHasActiveBooking() {
        // Given
        String email = "test@example.com";
        User user = new User();
        user.setId(1L);
        user.setEmail(email);

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(bookingRepository.existsByUserEmailAndStartTimeAfter(anyString(), any(LocalDateTime.class))).thenReturn(true);

        // When/Then
        assertThrows(DuplicateBookingException.class, () -> bookingService.reserveNearestSlot(email));
    }

    @Test
    void shouldThrowExceptionWhenNoSlotsAvailable() {
        // Given
        String email = "test@example.com";
        User user = new User();
        user.setId(1L);
        user.setEmail(email);

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(bookingRepository.existsByUserEmailAndStartTimeAfter(anyString(), any(LocalDateTime.class))).thenReturn(false);
        when(timeSlotRepository.findNextAvailable(any(LocalDateTime.class))).thenReturn(Optional.empty());

        // When/Then
        assertThrows(BookingNotAvailableException.class, () -> bookingService.reserveNearestSlot(email));
    }

    @Test
    void shouldCancelBooking() {
        // Given
        Long bookingId = 1L;
        LocalDateTime now = LocalDateTime.now();

        User user = new User();
        user.setId(1L);
        user.setEmail("test@example.com");

        Slot slot = new Slot();
        slot.setId(1L);
        slot.setStartTime(now.plusHours(1));
        slot.setEndTime(now.plusHours(2));
        slot.setReserved(true);

        Booking booking = new Booking();
        booking.setId(bookingId);
        booking.setUser(user);
        booking.setSlot(slot);

        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));

        // When
        bookingService.cancelBooking(bookingId);

        // Then
        verify(timeSlotRepository).save(any(Slot.class));
        verify(bookingRepository).delete(any(Booking.class));
        assertFalse(slot.isReserved());
    }
}
