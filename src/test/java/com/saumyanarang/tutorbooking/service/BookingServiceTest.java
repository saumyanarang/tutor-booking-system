package com.saumyanarang.tutorbooking.service;

import com.saumyanarang.tutorbooking.entity.Booking;
import com.saumyanarang.tutorbooking.entity.Role;
import com.saumyanarang.tutorbooking.entity.Slot;
import com.saumyanarang.tutorbooking.entity.User;
import com.saumyanarang.tutorbooking.exception.BookingCapacityExceededException;
import com.saumyanarang.tutorbooking.exception.BookingNotAvailableException;
import com.saumyanarang.tutorbooking.exception.DuplicateBookingException;
import com.saumyanarang.tutorbooking.exception.ForbiddenOperationException;
import com.saumyanarang.tutorbooking.exception.InvalidBookingTimeException;
import com.saumyanarang.tutorbooking.exception.ResourceNotFoundException;
import com.saumyanarang.tutorbooking.exception.SlotUnavailableException;
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
import org.springframework.dao.OptimisticLockingFailureException;
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
 * (which slot to try, when to skip, when to release the lock, who may cancel), not Redis or Postgres
 * themselves - those are covered by the Testcontainers tests in the integration package.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    private static final String EMAIL = "test@example.com";
    private static final String TUTOR_EMAIL = "tutor@example.com";

    @Mock private TimeSlotRepository timeSlotRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private UserRepository userRepository;
    @Mock private CacheableOperations cacheableOperations;
    @Mock private SlotLockService slotLockService;
    @Mock private BookingTransactionService bookingTransactionService;

    private BookingService bookingService;
    private User user;
    private User tutor;

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
        user.setRole(Role.STUDENT);

        tutor = new User();
        tutor.setId(50L);
        tutor.setEmail(TUTOR_EMAIL);
        tutor.setRole(Role.TUTOR);
    }

    // ---------- helpers ----------

    private Slot slot(long id) {
        Slot slot = new Slot();
        slot.setId(id);
        slot.setTutorId(tutor.getId());
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

    // ---------- reserveSlot (a specific slot) ----------

    private void givenStudentAndSlot(Slot slot) {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(timeSlotRepository.findById(slot.getId())).thenReturn(Optional.of(slot));
    }

    private void givenNoOverlappingBooking(Slot slot) {
        when(bookingRepository.existsOverlappingBookingForUser(user.getId(), slot.getStartTime(), slot.getEndTime()))
                .thenReturn(false);
    }

    @Test
    void shouldBookTheSpecificSlotAndReleaseTheLock() {
        Slot slot = slot(7L);
        givenStudentAndSlot(slot);
        givenNoOverlappingBooking(slot);
        Booking created = booking(70L);
        when(slotLockService.tryLock(7L)).thenReturn("token-7");
        when(bookingTransactionService.createBooking(user, 7L)).thenReturn(created);

        Booking result = bookingService.reserveSlot(EMAIL, 7L);

        assertSame(created, result);
        verify(slotLockService).unlock(7L, "token-7");
    }

    @Test
    void shouldNotLetATutorBookASlot() {
        when(userRepository.findByEmail(TUTOR_EMAIL)).thenReturn(Optional.of(tutor));

        assertThrows(ForbiddenOperationException.class, () -> bookingService.reserveSlot(TUTOR_EMAIL, 7L));

        verifyNoInteractions(timeSlotRepository, slotLockService, bookingTransactionService);
    }

    @Test
    void shouldReportNotFoundWhenTheSlotDoesNotExist() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(timeSlotRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> bookingService.reserveSlot(EMAIL, 99L));

        verifyNoInteractions(slotLockService, bookingTransactionService);
    }

    @Test
    void shouldRejectASlotThatHasAlreadyStarted() {
        Slot slot = slot(7L);
        slot.setStartTime(LocalDateTime.now().minusMinutes(5));
        slot.setEndTime(LocalDateTime.now().plusMinutes(55));
        givenStudentAndSlot(slot);

        assertThrows(InvalidBookingTimeException.class, () -> bookingService.reserveSlot(EMAIL, 7L));

        verifyNoInteractions(slotLockService, bookingTransactionService);
    }

    @Test
    void shouldRejectASlotThatIsAlreadyBookedWithoutTakingTheLock() {
        Slot slot = slot(7L);
        slot.setReserved(true);
        givenStudentAndSlot(slot);

        assertThrows(SlotUnavailableException.class, () -> bookingService.reserveSlot(EMAIL, 7L));

        verifyNoInteractions(slotLockService, bookingTransactionService);
    }

    @Test
    void shouldRejectABookingThatOverlapsTheStudentsOtherLesson() {
        Slot slot = slot(7L);
        givenStudentAndSlot(slot);
        when(bookingRepository.existsOverlappingBookingForUser(user.getId(), slot.getStartTime(), slot.getEndTime()))
                .thenReturn(true);

        assertThrows(DuplicateBookingException.class, () -> bookingService.reserveSlot(EMAIL, 7L));

        verifyNoInteractions(slotLockService, bookingTransactionService);
    }

    @Test
    void shouldTellTheStudentTheSlotIsTakenWhenAnotherRequestHoldsTheLock() {
        Slot slot = slot(7L);
        givenStudentAndSlot(slot);
        givenNoOverlappingBooking(slot);
        when(slotLockService.tryLock(7L)).thenReturn(null);

        assertThrows(SlotUnavailableException.class, () -> bookingService.reserveSlot(EMAIL, 7L));

        verifyNoInteractions(bookingTransactionService);
        verify(slotLockService, never()).unlock(anyLong(), anyString());   // we never owned the lock
    }

    @Test
    void shouldTurnTheSlotRecheckIntoASlotUnavailableAnswerAndStillReleaseTheLock() {
        Slot slot = slot(7L);
        givenStudentAndSlot(slot);
        givenNoOverlappingBooking(slot);
        when(slotLockService.tryLock(7L)).thenReturn("token-7");
        when(bookingTransactionService.createBooking(user, 7L))
                .thenThrow(new BookingNotAvailableException("Time slot already reserved"));

        assertThrows(SlotUnavailableException.class, () -> bookingService.reserveSlot(EMAIL, 7L));

        verify(slotLockService).unlock(7L, "token-7");
    }

    @Test
    void shouldTurnTheDatabaseUniqueConstraintIntoASlotUnavailableAnswer() {
        Slot slot = slot(7L);
        givenStudentAndSlot(slot);
        givenNoOverlappingBooking(slot);
        when(slotLockService.tryLock(7L)).thenReturn("token-7");
        when(bookingTransactionService.createBooking(user, 7L))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThrows(SlotUnavailableException.class, () -> bookingService.reserveSlot(EMAIL, 7L));

        verify(slotLockService).unlock(7L, "token-7");
    }

    @Test
    void shouldTurnAnOptimisticLockFailureIntoASlotUnavailableAnswer() {
        Slot slot = slot(7L);
        givenStudentAndSlot(slot);
        givenNoOverlappingBooking(slot);
        when(slotLockService.tryLock(7L)).thenReturn("token-7");
        when(bookingTransactionService.createBooking(user, 7L))
                .thenThrow(new OptimisticLockingFailureException("slot was updated by someone else"));

        assertThrows(SlotUnavailableException.class, () -> bookingService.reserveSlot(EMAIL, 7L));

        verify(slotLockService).unlock(7L, "token-7");
    }

    @Test
    void shouldAlwaysReleaseTheLockWhenBookingASpecificSlotFailsUnexpectedly() {
        Slot slot = slot(7L);
        givenStudentAndSlot(slot);
        givenNoOverlappingBooking(slot);
        when(slotLockService.tryLock(7L)).thenReturn("token-7");
        when(bookingTransactionService.createBooking(user, 7L)).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> bookingService.reserveSlot(EMAIL, 7L));

        verify(slotLockService).unlock(7L, "token-7");
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

    private Booking reservedBookingOwnedByStudent(long bookingId, Slot slot) {
        slot.setReserved(true);
        Booking booking = booking(bookingId);
        booking.setUser(user);
        booking.setSlot(slot);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        return booking;
    }

    @Test
    void shouldLetTheStudentCancelTheirBookingAndFreeTheSlot() {
        Slot slot = slot(1L);
        Booking booking = reservedBookingOwnedByStudent(5L, slot);

        bookingService.cancelBooking(5L, EMAIL);

        assertFalse(slot.isReserved());
        verify(timeSlotRepository).save(slot);
        verify(bookingRepository).delete(booking);
    }

    @Test
    void shouldMatchTheStudentsEmailIgnoringCaseWhenCancelling() {
        Slot slot = slot(1L);
        Booking booking = reservedBookingOwnedByStudent(5L, slot);

        bookingService.cancelBooking(5L, EMAIL.toUpperCase());

        verify(bookingRepository).delete(booking);
    }

    @Test
    void shouldLetTheSlotsTutorCancelABookingOnTheirSlot() {
        Slot slot = slot(1L);
        Booking booking = reservedBookingOwnedByStudent(5L, slot);
        when(userRepository.findByEmail(TUTOR_EMAIL)).thenReturn(Optional.of(tutor));

        bookingService.cancelBooking(5L, TUTOR_EMAIL);

        assertFalse(slot.isReserved());
        verify(bookingRepository).delete(booking);
    }

    @Test
    void shouldNotLetAStrangerCancelSomeoneElsesBooking() {
        Slot slot = slot(1L);
        reservedBookingOwnedByStudent(5L, slot);
        User stranger = new User();
        stranger.setId(99L);
        stranger.setEmail("stranger@example.com");
        stranger.setRole(Role.STUDENT);
        when(userRepository.findByEmail("stranger@example.com")).thenReturn(Optional.of(stranger));

        assertThrows(ForbiddenOperationException.class, () -> bookingService.cancelBooking(5L, "stranger@example.com"));

        assertTrue(slot.isReserved());
        verify(bookingRepository, never()).delete(any(Booking.class));
    }

    @Test
    void shouldNotLetADifferentTutorCancelABookingOnSomeoneElsesSlot() {
        Slot slot = slot(1L);
        reservedBookingOwnedByStudent(5L, slot);
        User otherTutor = new User();
        otherTutor.setId(51L);
        otherTutor.setEmail("other-tutor@example.com");
        otherTutor.setRole(Role.TUTOR);
        when(userRepository.findByEmail("other-tutor@example.com")).thenReturn(Optional.of(otherTutor));

        assertThrows(ForbiddenOperationException.class, () -> bookingService.cancelBooking(5L, "other-tutor@example.com"));

        verify(bookingRepository, never()).delete(any(Booking.class));
    }

    @Test
    void shouldReportNotFoundWhenCancellingABookingThatDoesNotExist() {
        when(bookingRepository.findById(404L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> bookingService.cancelBooking(404L, EMAIL));
    }

    // ---------- listMyBookings ----------

    @Test
    void shouldListAStudentsOwnBookings() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        Slot slot = slot(1L);
        Booking booking = booking(5L);
        booking.setUser(user);
        booking.setSlot(slot);
        when(bookingRepository.findAllForStudent(user.getId())).thenReturn(List.of(booking));

        var result = bookingService.listMyBookings(EMAIL);

        assertEquals(1, result.size());
        assertEquals(5L, result.get(0).bookingId());
        assertEquals(1L, result.get(0).slotId());
        verify(bookingRepository, never()).findAllForTutor(anyLong());
    }

    @Test
    void shouldListTheBookingsMadeOnATutorsSlots() {
        when(userRepository.findByEmail(TUTOR_EMAIL)).thenReturn(Optional.of(tutor));
        Slot slot = slot(1L);
        Booking booking = booking(5L);
        booking.setUser(user);
        booking.setSlot(slot);
        user.setUserName("alice");
        when(bookingRepository.findAllForTutor(tutor.getId())).thenReturn(List.of(booking));

        var result = bookingService.listMyBookings(TUTOR_EMAIL);

        assertEquals(1, result.size());
        assertEquals("alice", result.get(0).studentName());
        verify(bookingRepository, never()).findAllForStudent(anyLong());
    }
}
