package com.saumyanarang.tutorbooking.service;

import com.saumyanarang.tutorbooking.dto.booking.BookingRequestDto;
import com.saumyanarang.tutorbooking.exception.BusinessException;
import com.saumyanarang.tutorbooking.exception.DuplicateBookingException;
import com.saumyanarang.tutorbooking.exception.BookingCapacityExceededException;
import com.saumyanarang.tutorbooking.exception.BookingNotAvailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;

import java.util.UUID;

/**
 * Service for managing booking requests asynchronously using a Redis-backed queue.
 * <p>
 * This service provides methods to enqueue booking requests as JSON strings into a Redis list,
 * and a scheduled background worker to dequeue and process these requests by delegating to
 * {@link BookingService}. Serialization and deserialization are handled using Jackson's ObjectMapper.
 * <p>
 * This design allows the system to handle high concurrency by decoupling incoming API requests from
 * direct database writes, improving scalability and reliability.
 */
@Service
public class BookingQueueService {
    private final RedisTemplate<String, Object> redisTemplate;
    private final BookingService bookingService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final RedisCleanupService redisCleanupService;
    private static final Logger logger = LoggerFactory.getLogger(BookingQueueService.class);
    private static final String QUEUE_KEY = "booking:queue";
    private static final String DLQ_KEY = "booking:dlq";
    private static final String EMAIL_SET_KEY = "booking:emails:queued"; // Key for tracking emails in queue
    private static final int MAX_ATTEMPTS = 3;
    private static final String STATUS_KEY_PREFIX = "booking:status:";
    @Value("${booking.queue.batch-size:10}")
    private int batchSize;

    private volatile boolean running = true;

    public enum RequestStatus {
        QUEUED, PROCESSING, SUCCESS, FAILED
    }

    /**
     * Helper class to wrap booking request and attempt count for DLQ support.
     */
    private static class QueueItem {
        public BookingRequestDto request;
        public int attempts;
        public String requestId; // Added requestId field


        public QueueItem(BookingRequestDto request, int attempts, String requestId) {
            this.request = request;
            this.attempts = attempts;
            this.requestId = requestId;
        }
    }

    public String enqueueBookingRequest(Object bookingRequest) {
        String requestId = UUID.randomUUID().toString();
        try {
            BookingRequestDto req = (BookingRequestDto) bookingRequest;
            if (isUserAlreadyInQueue(req.getEmail())) {
                throw new DuplicateBookingException("A booking request for this email is already in queue");
            }

            String json = objectMapper.writeValueAsString(new QueueItem((BookingRequestDto) bookingRequest, 0, requestId));
            redisTemplate.opsForList().rightPush(QUEUE_KEY, json);
            String statusKey = STATUS_KEY_PREFIX + requestId;
            redisTemplate.opsForValue().set(statusKey, RequestStatus.QUEUED.name());
            redisCleanupService.setExpiryOnStatusKey(statusKey);
            redisTemplate.opsForSet().add(EMAIL_SET_KEY, req.getEmail()); // Add email to set
        } catch (DuplicateBookingException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to serialize booking request: {}", bookingRequest, e);
            throw new BusinessException("Failed to process booking request: " + e.getMessage());
        }
        return requestId;
    }

    boolean isUserAlreadyInQueue(String email) {
        // Check if the email is in the Redis set for O(1) lookup
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(EMAIL_SET_KEY, email));
    }

    public String getRequestStatus(String requestId) {
        Object status = redisTemplate.opsForValue().get(STATUS_KEY_PREFIX + requestId);
        return status != null ? status.toString() : null;
    }

    private QueueItem dequeueQueueItem() {
        Object jsonObj = redisTemplate.opsForList().leftPop(QUEUE_KEY);
        if (jsonObj instanceof String json) {
            try {
                return objectMapper.readValue(json, QueueItem.class);
            } catch (Exception e) {
                logger.error("Failed to deserialize queue item: {}", json, e);
            }
        }
        return null;
    }

    private void moveToDLQ(QueueItem item) {
        try {
            String json = objectMapper.writeValueAsString(item);
            redisTemplate.opsForList().rightPush(DLQ_KEY, json);
            meterRegistry.counter("booking.dlq.moved").increment();
            logger.warn("Moved booking request to DLQ: {}", item.request);
        } catch (Exception e) {
            logger.error("Failed to move booking request to DLQ: {}", item, e);
        }
    }

    /**
     * Graceful shutdown: stop processing new batches when the application is shutting down.
     */
    @PreDestroy
    public void shutdown() {
        running = false;
        logger.info("BookingQueueService is shutting down. No new batches will be processed.");
    }

    /**
     * Returns the current queue length for monitoring.
     */
    public long getQueueLength() {
        Long size = redisTemplate.opsForList().size(QUEUE_KEY);
        return size != null ? size : 0;
    }

    /**
     * Returns the current DLQ length for monitoring.
     */
    public long getDLQLength() {
        Long size = redisTemplate.opsForList().size(DLQ_KEY);
        return size != null ? size : 0;
    }

    /**
     * Ensures idempotency by checking if a request with the same requestId has already succeeded.
     */
    private boolean isAlreadyProcessed(String requestId) {
        String status = getRequestStatus(requestId);
        return RequestStatus.SUCCESS.name().equals(status);
    }

    public BookingQueueService(
        RedisTemplate<String, Object> redisTemplate,
        BookingService bookingService,
        ObjectMapper objectMapper,
        MeterRegistry meterRegistry,
        RedisCleanupService redisCleanupService
    ) {
        this.redisTemplate = redisTemplate;
        this.bookingService = bookingService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.redisCleanupService = redisCleanupService;
        meterRegistry.gauge("booking.queue.length", this, BookingQueueService::getQueueLength);
        meterRegistry.gauge("booking.dlq.length", this, BookingQueueService::getDLQLength);
    }

    @Scheduled(fixedDelayString = "${booking.queue.poll-interval-ms:100}")
    public void processBookingQueue() {
        if (!running) return;
        for (int i = 0; i < batchSize; i++) {
            QueueItem item = dequeueQueueItem();
            if (item == null) break;

            String requestId = item.requestId; // Use requestId directly from QueueItem
            if (requestId != null) {
                if (isAlreadyProcessed(requestId)) {
                    continue; // Item already popped by dequeueQueueItem
                }
                String statusKey = STATUS_KEY_PREFIX + requestId;
                redisTemplate.opsForValue().set(statusKey, RequestStatus.PROCESSING.name());
                redisCleanupService.setExpiryOnStatusKey(statusKey);
            }

            try {
                bookingService.reserveNearestSlot(item.request.getEmail());
                meterRegistry.counter("booking.queue.processed").increment();
                if (requestId != null) {
                    String statusKey = STATUS_KEY_PREFIX + requestId;
                    redisTemplate.opsForValue().set(statusKey, RequestStatus.SUCCESS.name());
                    redisCleanupService.setExpiryOnStatusKey(statusKey);
                }
                // Remove email from tracking set after successful processing
                redisTemplate.opsForSet().remove(EMAIL_SET_KEY, item.request.getEmail());
            } catch (DuplicateBookingException e) {
                logger.info("Skipping duplicate booking: {}", item.request.getEmail());
                meterRegistry.counter("booking.queue.duplicate").increment();
                if (requestId != null) {
                    String statusKey = STATUS_KEY_PREFIX + requestId;
                    redisTemplate.opsForValue().set(statusKey, RequestStatus.FAILED.name() + ": " + e.getMessage());
                    redisCleanupService.setExpiryOnStatusKey(statusKey);
                }
                // Remove email from tracking set as this request is now completed (failed)
                redisTemplate.opsForSet().remove(EMAIL_SET_KEY, item.request.getEmail());
            } catch (BookingNotAvailableException e) {
                logger.info("No slots available for booking: {}", item.request.getEmail());
                meterRegistry.counter("booking.queue.no_slots").increment();
                if (requestId != null) {
                    String statusKey = STATUS_KEY_PREFIX + requestId;
                    redisTemplate.opsForValue().set(statusKey, RequestStatus.FAILED.name() + ": " + e.getMessage());
                    redisCleanupService.setExpiryOnStatusKey(statusKey);
                }
                // Remove email from tracking set as this request is now completed (failed)
                redisTemplate.opsForSet().remove(EMAIL_SET_KEY, item.request.getEmail());
            } catch (BookingCapacityExceededException e) {
                handleRetryableError(item, requestId, e, "capacity_exceeded");
            } catch (BusinessException e) {
                handleRetryableError(item, requestId, e, "business_rule");
            } catch (Exception e) {
                handleRetryableError(item, requestId, e, "technical");
            }
        }
    }

    private void handleRetryableError(QueueItem item, String requestId, Exception e, String errorType) {
        item.attempts++;
        logger.error("Failed to process booking request (attempt {}, type: {}): {}", item.attempts, errorType, item.request, e);
        meterRegistry.counter("booking.queue.process.errors." + errorType).increment();
        if (item.attempts >= MAX_ATTEMPTS) {
            moveToDLQ(item);
            if (requestId != null) {
                redisTemplate.opsForValue().set(STATUS_KEY_PREFIX + requestId,
                    RequestStatus.FAILED.name() + ": " + e.getMessage());
            }
            // Remove email from tracking set when max retries are exhausted
            redisTemplate.opsForSet().remove(EMAIL_SET_KEY, item.request.getEmail());
            redisTemplate.opsForList().leftPop(QUEUE_KEY);
        } else {
            try {
                String updatedJson = objectMapper.writeValueAsString(item);
                redisTemplate.opsForList().set(QUEUE_KEY, 0, updatedJson);
            } catch (Exception ex) {
                logger.error("Failed to re-enqueue booking request: {}", item, ex);
                moveToDLQ(item);
                if (requestId != null) {
                    redisTemplate.opsForValue().set(STATUS_KEY_PREFIX + requestId, RequestStatus.FAILED.name());
                }
                // Remove email from tracking set when request can't be re-enqueued
                redisTemplate.opsForSet().remove(EMAIL_SET_KEY, item.request.getEmail());
                redisTemplate.opsForList().leftPop(QUEUE_KEY);
            }
        }
    }
}
