package com.saumyanarang.tutorbooking.controller;

import com.saumyanarang.tutorbooking.dto.booking.BookingRequestDto;
import com.saumyanarang.tutorbooking.dto.booking.BookingResponseDto;
import com.saumyanarang.tutorbooking.service.BookingQueueService;
import com.saumyanarang.tutorbooking.service.BookingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @Mock
    private BookingQueueService bookingQueueService;

    @InjectMocks
    private BookingController bookingController;

    @Test
    void shouldReserveNearest() {
        // Given
        BookingRequestDto requestDto = new BookingRequestDto();
        requestDto.setEmail("test@example.com");
        String expectedResponseBody = "request-123";

        when(bookingQueueService.enqueueBookingRequest(any(BookingRequestDto.class)))
                .thenReturn(expectedResponseBody);

        // When
        ResponseEntity<BookingResponseDto> response = bookingController.reserveNearest(requestDto);

        // Then
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(expectedResponseBody, response.getBody());
        verify(bookingQueueService).enqueueBookingRequest(requestDto);
    }

    @Test
    void shouldGetBookingStatus() {
        // Given
        String requestId = "request-123";
        String status = "PROCESSING";

        when(bookingQueueService.getRequestStatus(requestId)).thenReturn(status);

        // When
        ResponseEntity<BookingResponseDto> response = bookingController.getBookingStatus(requestId);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(status, response.getBody());
        verify(bookingQueueService).getRequestStatus(requestId);
    }

    @Test
    void shouldReturnNotFoundWhenStatusIsNull() {
        // Given
        String requestId = "request-123";

        when(bookingQueueService.getRequestStatus(requestId)).thenReturn(null);

        // When
        ResponseEntity<BookingResponseDto> response = bookingController.getBookingStatus(requestId);

        // Then
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(bookingQueueService).getRequestStatus(requestId);
    }

    @Test
    void shouldCancelBooking() {
        // Given
        Long bookingId = 1L;
        doNothing().when(bookingService).cancelBooking(bookingId);

        // When
        ResponseEntity<Void> response = bookingController.cancelBooking(bookingId);

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(bookingService).cancelBooking(bookingId);
    }
}
