package com.saumyanarang.tutorbooking.service;

import com.saumyanarang.tutorbooking.entity.Booking;
import com.saumyanarang.tutorbooking.entity.Slot;
import com.saumyanarang.tutorbooking.entity.User;
import com.saumyanarang.tutorbooking.exception.BookingCapacityExceededException;
import com.saumyanarang.tutorbooking.exception.BookingNotAvailableException;
import com.saumyanarang.tutorbooking.exception.DuplicateBookingException;
import com.saumyanarang.tutorbooking.repository.BookingRepository;
import com.saumyanarang.tutorbooking.repository.TimeSlotRepository;
import com.saumyanarang.tutorbooking.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the booking flow: Redis lock per slot -> DB transaction -> unlock.
 * Every collaborator is a mock, so these tests check the DECISIONS BookingService makes
 * (which slot to try, when to skip, when to release the lock), not Redis or Postgres themselves.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private static final String EMAIL = "test@example.com";

    @Mock private TimeSlotRepository timeSlotRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private UserRepository userRepository;
    @Mock private CacheableOperations cacheableOperations;
    @Mock private SlotLockService slotLockService;
    @Mock private BookingTransactionService bookingTransactionService;

    private BookingService bookingService;
    private User user;

    @BeforeEach
    void setUp() {
        bookingService = new BookingService(
                timeSlotRepository,
                bookingRepository,
                userRepository,
                new SimpleMeterRegistry(),
                cacheableOperations,
                slotLockService,
                bookingTransactionService
        );
        user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
    }

    // ---------- helpers ----------

    private Slot slot(long id) {
        Slot slot = new Slot();
        slot.setId(id);
        slot.setStartTime(LocalDateTime.now().plusHours(id));
        slot.setEndTime(LocalDateTime.now().plusHours(id + 1));
        slot.setReserved(false);
        return slot;
    }

    private Booking booking(long id) {
        Booking booking = new Booking();
        booking.setId(id);
        return booking;
    }

    private void givenUserWithNoActiveBooking() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bookingRepository.existsByUserEmailAndStartTimeAfter(eq(EMAIL), any(LocalDateTime.class)))
                .thenReturn(false);
    }

    private void givenFreeSlots(Slot... slots) {
        when(timeSlotRepository.findAvailable(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(slots));
    }

    // ---------- reserveNearestSlot ----------

    @Test
    void shouldBookTheNearestSlotAndReleaseTheLock() {
        givenUserWithNoActiveBooking();
        givenFreeSlots(slot(1L));
        Booking created = booking(10L);
        when(slotLockService.tryLock(1L)).thenReturn("token-1");
        when(bookingTransactionService.createBooking(user, 1L)).thenReturn(created);

        Booking result = bookingService.reserveNearestSlot(EMAIL);

        assertSame(created, result);
        verify(slotLockService).unlock(1L, "token-1");
    }

    @Test
    void shouldRejectAUserWhoAlreadyHasAnActiveBooking() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(bookingRepository.existsByUserEmailAndStartTimeAfter(eq(EMAIL), any(LocalDateTime.class)))
                .thenReturn(true);

        assertThrows(DuplicateBookingException.class, () -> bookingService.reserveNearestSlot(EMAIL));

        verifyNoInteractions(timeSlotRepository, slotLockService, bookingTransactionService);
    }

    @Test
    void shouldThrowNotAvailableWhenThereAreNoFreeSlots() {
        givenUserWithNoActiveBooking();
        givenFreeSlots();

        assertThrows(BookingNotAvailableException.class, () -> bookingService.reserveNearestSlot(EMAIL));

        verifyNoInteractions(slotLockService, bookingTransactionService);
    }

    @Test
    void shouldSkipASlotThatIsLockedByAnotherRequestAndBookTheNextOne() {
        givenUserWithNoActiveBooking();
        givenFreeSlots(slot(1L), slot(2L));
        Booking created = booking(11L);
        when(slotLockService.tryLock(1L)).thenReturn(null);          // someone else is booking slot 1
        when(slotLockService.tryLock(2L)).thenReturn("token-2");
        when(bookingTransactionService.createBooking(user, 2L)).thenReturn(created);

        Booking result = bookingService.reserveNearestSlot(EMAIL);

        assertSame(created, result);
        verify(bookingTransactionService, never()).createBooking(any(User.class), eq(1L));
        verify(slotLockService, never()).unlock(eq(1L), anyString());   // we never owned lock 1
        verify(slotLockService).unlock(2L, "token-2");
    }

    @Test
    void shouldTellTheCallerToRetryWhenEverySlotIsLocked() {
        givenUserWithNoActiveBooking();
        givenFreeSlots(slot(1L), slot(2L));
        when(slotLockService.tryLock(anyLong())).thenReturn(null);

        assertThrows(BookingCapacityExceededException.class, () -> bookingService.reserveNearestSlot(EMAIL));

        verifyNoInteractions(bookingTransactionService);
    }

    @Test
    void shouldMoveToTheNextSlotWhenTheSlotWasTakenBeforeTheTransactionRan() {
        givenUserWithNoActiveBooking();
        givenFreeSlots(slot(1L), slot(2L));
        Booking created = booking(12L);
        when(slotLockService.tryLock(1L)).thenReturn("token-1");
        when(slotLockService.tryLock(2L)).thenReturn("token-2");
        when(bookingTransactionService.createBooking(user, 1L))
                .thenThrow(new BookingNotAvailableException("Time slot already reserved"));
        when(bookingTransactionService.createBooking(user, 2L)).thenReturn(created);

        Booking result = bookingService.reserveNearestSlot(EMAIL);

        assertSame(created, result);
        verify(slotLockService).unlock(1L, "token-1");   // lock released even though slot 1 failed
        verify(slotLockService).unlock(2L, "token-2");
    }

    @Test
    void shouldMoveToTheNextSlotWhenTheDatabaseUniqueConstraintRejectsTheBooking() {
        givenUserWithNoActiveBooking();
        givenFreeSlots(slot(1L), slot(2L));
        Booking created = booking(13L);
        when(slotLockService.tryLock(1L)).thenReturn("token-1");
        when(slotLockService.tryLock(2L)).thenReturn("token-2");
        when(bookingTransactionService.createBooking(user, 1L))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));
        when(bookingTransactionService.createBooking(user, 2L)).thenReturn(created);

        Booking result = bookingService.reserveNearestSlot(EMAIL);

        assertSame(created, result);
        verify(slotLockService).unlock(1L, "token-1");
        verify(slotLockService).unlock(2L, "token-2");
    }

    @Test
    void shouldAlwaysReleaseTheLockEvenWhenAnUnexpectedErrorOccurs() {
        givenUserWithNoActiveBooking();
        givenFreeSlots(slot(1L));
        when(slotLockService.tryLock(1L)).thenReturn("token-1");
        when(bookingTransactionService.createBooking(user, 1L)).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> bookingService.reserveNearestSlot(EMAIL));

        verify(slotLockService).unlock(1L, "token-1");
    }

    // ---------- other operations ----------

    @Test
    void shouldFindTheNextSlotThroughTheCache() {
        Slot slot = slot(1L);
        when(cacheableOperations.findNextSlotCached(any(LocalDateTime.class))).thenReturn(Optional.of(slot));

        Optional<Slot> result = bookingService.findNextSlotCached();

        assertTrue(result.isPresent());
        assertSame(slot, result.get());
    }

    @Test
    void shouldCancelABookingAndFreeItsSlot() {
        Slot slot = slot(1L);
        slot.setReserved(true);
        Booking booking = booking(5L);
        booking.setUser(user);
        booking.setSlot(slot);
        when(bookingRepository.findById(5L)).thenReturn(Optional.of(booking));

        bookingService.cancelBooking(5L);

        assertFalse(slot.isReserved());
        verify(timeSlotRepository).save(slot);
        verify(bookingRepository).delete(booking);
    }
}
