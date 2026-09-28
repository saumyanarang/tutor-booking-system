package com.saumyanarang.tutorbooking.controller;

import com.saumyanarang.tutorbooking.dto.booking.BookingRequestDto;
import com.saumyanarang.tutorbooking.dto.booking.BookingResponseDto;
import com.saumyanarang.tutorbooking.entity.Booking;
import com.saumyanarang.tutorbooking.service.LoadMonitoringService;
import com.saumyanarang.tutorbooking.service.BookingQueueService;
import com.saumyanarang.tutorbooking.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Booking API", description = "مدیریت رزرو زمان")
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private static final Logger logger = LoggerFactory.getLogger(BookingController.class);
    private final BookingService bookingService;
    private final BookingQueueService bookingQueueService;
    private final LoadMonitoringService loadMonitoringService;

    @Autowired
    public BookingController(
            BookingService bookingService,
            BookingQueueService bookingQueueService,
            LoadMonitoringService loadMonitoringService) {
        this.bookingService = bookingService;
        this.bookingQueueService = bookingQueueService;
        this.loadMonitoringService = loadMonitoringService;
    }

    @Operation(summary = "رزرو نزدیک‌ترین زمان آزاد")
    @PostMapping("/reserve")
    public ResponseEntity<BookingResponseDto> reserveNearest(@RequestBody @Valid BookingRequestDto request) {
        try {
            // Increment active request counter
            loadMonitoringService.incrementActiveRequests();

            // Check if we should queue this request based on current system load
            if (loadMonitoringService.shouldQueueRequest()) {
                // High load - use queue
                logger.info("Processing booking request for {} through queue due to high load", request.getEmail());
                String requestId = bookingQueueService.enqueueBookingRequest(request);
                String status = bookingQueueService.getRequestStatus(requestId);
                return ResponseEntity.accepted().body(new BookingResponseDto(requestId, status));
            } else {
                // Normal load - process directly
                logger.info("Processing booking request for {} directly", request.getEmail());
                Booking booking = bookingService.reserveNearestSlot(request.getEmail());
                String requestId = "direct-" + booking.getId();
                return ResponseEntity.ok().body(new BookingResponseDto(requestId, "SUCCESS"));
            }
        } finally {
            // Always decrement the counter when processing is complete
            loadMonitoringService.decrementActiveRequests();
        }
    }

    @Operation(summary = "بررسی وضعیت درخواست رزرو با requestId")
    @GetMapping("/status/{requestId}")
    public ResponseEntity<BookingResponseDto> getBookingStatus(@PathVariable String requestId) {
        String status = bookingQueueService.getRequestStatus(requestId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new BookingResponseDto(requestId, status));
    }

    @Operation(summary = "لغو رزرو با ID")
    @DeleteMapping("/cancel/{id}")
    public ResponseEntity<Void> cancelBooking(@PathVariable Long id) {
        bookingService.cancelBooking(id);
        return ResponseEntity.noContent().build();
    }
}

