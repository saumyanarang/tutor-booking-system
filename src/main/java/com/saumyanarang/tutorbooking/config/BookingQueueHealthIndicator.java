package com.saumyanarang.tutorbooking.config;

import com.saumyanarang.tutorbooking.service.BookingQueueService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Custom health indicator that monitors the booking queue.
 * Provides insights into the queue's health and backlog status.
 */
@Component
public class BookingQueueHealthIndicator implements HealthIndicator {

    private final BookingQueueService bookingQueueService;

    // Configurable thresholds for queue health status
    private static final int QUEUE_WARNING_THRESHOLD = 50;
    private static final int QUEUE_CRITICAL_THRESHOLD = 100;
    private static final int DLQ_WARNING_THRESHOLD = 10;

    public BookingQueueHealthIndicator(BookingQueueService bookingQueueService) {
        this.bookingQueueService = bookingQueueService;
    }

    @Override
    public Health health() {
        long queueSize = bookingQueueService.getQueueLength();
        long dlqSize = bookingQueueService.getDLQLength();

        // Build health response with queue details
        Health.Builder builder = Health.up()
            .withDetail("queueSize", queueSize)
            .withDetail("deadLetterQueueSize", dlqSize);

        // Check queue size thresholds
        if (queueSize > QUEUE_CRITICAL_THRESHOLD) {
            return builder.down()
                .withDetail("error", "Queue size exceeds critical threshold")
                .build();
        } else if (queueSize > QUEUE_WARNING_THRESHOLD) {
            return builder.status("WARNING")
                .withDetail("warning", "Queue size exceeds warning threshold")
                .build();
        }

        // Check DLQ size threshold
        if (dlqSize > DLQ_WARNING_THRESHOLD) {
            return builder.status("WARNING")
                .withDetail("warning", "Dead letter queue size exceeds threshold")
                .build();
        }

        return builder.build();
    }
}
