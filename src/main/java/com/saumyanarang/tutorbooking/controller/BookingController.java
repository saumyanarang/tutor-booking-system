package com.saumyanarang.tutorbooking.controller;

import com.saumyanarang.tutorbooking.dto.booking.BookSlotRequestDto;
import com.saumyanarang.tutorbooking.dto.booking.BookingDetailsDto;
import com.saumyanarang.tutorbooking.dto.booking.BookingRequestDto;
import com.saumyanarang.tutorbooking.dto.booking.BookingResponseDto;
import com.saumyanarang.tutorbooking.entity.Booking;
import com.saumyanarang.tutorbooking.exception.ForbiddenOperationException;
import com.saumyanarang.tutorbooking.service.LoadMonitoringService;
import com.saumyanarang.tutorbooking.service.BookingQueueService;
import com.saumyanarang.tutorbooking.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Bookings", description = "Book a specific slot, list your bookings, cancel")
@SecurityRequirement(name = "bearerAuth")
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

    @Operation(summary = "Book a specific slot (students only)",
            description = "201 if you got it, 409 if the slot is already booked (or being booked by someone else "
                    + "right now) or overlaps one of your own lessons, 404 if the slot does not exist.")
    @PostMapping
    public ResponseEntity<BookingDetailsDto> bookSlot(@RequestBody @Valid BookSlotRequestDto request,
                                                      Authentication authentication) {
        Booking booking = bookingService.reserveSlot(authentication.getName(), request.slotId());
        return ResponseEntity.status(HttpStatus.CREATED).body(BookingDetailsDto.from(booking));
    }

    @Operation(summary = "List your bookings",
            description = "For a student: the lessons they booked. For a tutor: the bookings made on their slots.")
    @GetMapping("/mine")
    public List<BookingDetailsDto> myBookings(Authentication authentication) {
        return bookingService.listMyBookings(authentication.getName());
    }

    @Operation(summary = "Book the nearest free slot (students only)",
            description = "Convenience endpoint from the original project: picks the soonest free slot for you. "
                    + "The email in the body must be your own. Under heavy load the request is queued (202).")
    @PostMapping("/reserve")
    public ResponseEntity<BookingResponseDto> reserveNearest(@RequestBody @Valid BookingRequestDto request,
                                                             Authentication authentication) {
        if (!authentication.getName().equalsIgnoreCase(request.getEmail())) {
            throw new ForbiddenOperationException("You can only book for your own account");
        }
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

    @Operation(summary = "Check the status of a queued booking request by requestId")
    @GetMapping("/status/{requestId}")
    public ResponseEntity<BookingResponseDto> getBookingStatus(@PathVariable String requestId) {
        String status = bookingQueueService.getRequestStatus(requestId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new BookingResponseDto(requestId, status));
    }

    @Operation(summary = "Cancel a booking and free its slot",
            description = "Allowed for the student who booked it and for the tutor who owns the slot.")
    @DeleteMapping("/cancel/{id}")
    public ResponseEntity<Void> cancelBooking(@PathVariable Long id, Authentication authentication) {
        bookingService.cancelBooking(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
