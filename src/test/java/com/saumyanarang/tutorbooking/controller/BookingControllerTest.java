package com.saumyanarang.tutorbooking.controller;

import com.saumyanarang.tutorbooking.dto.booking.BookSlotRequestDto;
import com.saumyanarang.tutorbooking.dto.booking.BookingDetailsDto;
import com.saumyanarang.tutorbooking.dto.booking.BookingRequestDto;
import com.saumyanarang.tutorbooking.dto.booking.BookingResponseDto;
import com.saumyanarang.tutorbooking.entity.Booking;
import com.saumyanarang.tutorbooking.entity.Slot;
import com.saumyanarang.tutorbooking.entity.User;
import com.saumyanarang.tutorbooking.exception.ForbiddenOperationException;
import com.saumyanarang.tutorbooking.service.BookingQueueService;
import com.saumyanarang.tutorbooking.service.BookingService;
import com.saumyanarang.tutorbooking.service.LoadMonitoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain unit tests of the controller methods: the services are mocks, and the caller's identity is
 * passed in as an Authentication exactly the way Spring Security hands it over.
 * (Role rules and HTTP status codes through the real filter chain are covered by TutorBookingFlowTest.)
 */
@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    private static final String EMAIL = "student@example.com";

    @Mock private BookingService bookingService;
    @Mock private BookingQueueService bookingQueueService;
    @Mock private LoadMonitoringService loadMonitoringService;

    private BookingController bookingController;
    private Authentication student;

    @BeforeEach
    void setUp() {
        bookingController = new BookingController(bookingService, bookingQueueService, loadMonitoringService);
        student = new UsernamePasswordAuthenticationToken(EMAIL, null, List.of());
    }

    private Booking bookingWithId(long id) {
        User user = new User();
        user.setUserName("alice");
        Slot slot = new Slot();
        slot.setId(7L);
        slot.setTutorId(50L);
        slot.setStartTime(LocalDateTime.now().plusDays(1));
        slot.setEndTime(LocalDateTime.now().plusDays(1).plusHours(1));
        Booking booking = new Booking();
        booking.setId(id);
        booking.setUser(user);
        booking.setSlot(slot);
        booking.setReservedAt(LocalDateTime.now());
        return booking;
    }

    // ---------- book a specific slot ----------

    @Test
    void shouldBookTheRequestedSlotForTheAuthenticatedStudent() {
        when(bookingService.reserveSlot(EMAIL, 7L)).thenReturn(bookingWithId(70L));

        ResponseEntity<BookingDetailsDto> response = bookingController.bookSlot(new BookSlotRequestDto(7L), student);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(70L, response.getBody().bookingId());
        assertEquals(7L, response.getBody().slotId());
        assertEquals(50L, response.getBody().tutorId());
        assertEquals("alice", response.getBody().studentName());
    }

    @Test
    void shouldListTheCallersOwnBookings() {
        BookingDetailsDto details = BookingDetailsDto.from(bookingWithId(70L));
        when(bookingService.listMyBookings(EMAIL)).thenReturn(List.of(details));

        List<BookingDetailsDto> result = bookingController.myBookings(student);

        assertEquals(List.of(details), result);
    }

    // ---------- book the nearest slot (original endpoint) ----------

    @Test
    void shouldBookTheNearestSlotDirectlyWhenLoadIsNormal() {
        when(loadMonitoringService.shouldQueueRequest()).thenReturn(false);
        when(bookingService.reserveNearestSlot(EMAIL)).thenReturn(bookingWithId(10L));
        BookingRequestDto request = new BookingRequestDto();
        request.setEmail(EMAIL);

        ResponseEntity<BookingResponseDto> response = bookingController.reserveNearest(request, student);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("direct-10", response.getBody().getRequestId());
        assertEquals("SUCCESS", response.getBody().getStatus());
        verify(loadMonitoringService).incrementActiveRequests();
        verify(loadMonitoringService).decrementActiveRequests();   // always, via finally
        verify(bookingQueueService, never()).enqueueBookingRequest(any());
    }

    @Test
    void shouldQueueTheRequestWhenLoadIsHigh() {
        when(loadMonitoringService.shouldQueueRequest()).thenReturn(true);
        when(bookingQueueService.enqueueBookingRequest(any(BookingRequestDto.class))).thenReturn("request-123");
        when(bookingQueueService.getRequestStatus("request-123")).thenReturn("QUEUED");
        BookingRequestDto request = new BookingRequestDto();
        request.setEmail(EMAIL);

        ResponseEntity<BookingResponseDto> response = bookingController.reserveNearest(request, student);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("request-123", response.getBody().getRequestId());
        assertEquals("QUEUED", response.getBody().getStatus());
        verify(loadMonitoringService).decrementActiveRequests();
        verify(bookingService, never()).reserveNearestSlot(any());
    }

    @Test
    void shouldNotLetAStudentBookTheNearestSlotOnBehalfOfSomeoneElse() {
        BookingRequestDto request = new BookingRequestDto();
        request.setEmail("someone.else@example.com");

        assertThrows(ForbiddenOperationException.class, () -> bookingController.reserveNearest(request, student));

        verify(bookingService, never()).reserveNearestSlot(any());
        verify(bookingQueueService, never()).enqueueBookingRequest(any());
    }

    // ---------- status and cancel ----------

    @Test
    void shouldReturnTheStatusOfAQueuedRequest() {
        when(bookingQueueService.getRequestStatus("request-123")).thenReturn("PROCESSING");

        ResponseEntity<BookingResponseDto> response = bookingController.getBookingStatus("request-123");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("request-123", response.getBody().getRequestId());
        assertEquals("PROCESSING", response.getBody().getStatus());
    }

    @Test
    void shouldReturnNotFoundWhenTheRequestStatusIsUnknown() {
        when(bookingQueueService.getRequestStatus("request-123")).thenReturn(null);

        ResponseEntity<BookingResponseDto> response = bookingController.getBookingStatus("request-123");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void shouldCancelABookingAsTheAuthenticatedUser() {
        ResponseEntity<Void> response = bookingController.cancelBooking(1L, student);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(bookingService).cancelBooking(1L, EMAIL);
    }
}
