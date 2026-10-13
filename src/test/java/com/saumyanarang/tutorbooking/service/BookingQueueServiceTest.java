package com.saumyanarang.tutorbooking.service;

import com.saumyanarang.tutorbooking.dto.booking.BookingRequestDto;
import com.saumyanarang.tutorbooking.exception.DuplicateBookingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests of the booking queue's bookkeeping with a mocked RedisTemplate.
 * (Real Redis behaviour is exercised by the Testcontainers tests in the integration package.)
 */
@ExtendWith(MockitoExtension.class)
class BookingQueueServiceTest {

    private static final String EMAIL = "test@example.com";

    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private BookingService bookingService;
    @Mock private RedisCleanupService redisCleanupService;
    @Mock private ListOperations<String, Object> listOperations;
    @Mock private ValueOperations<String, Object> valueOperations;
    @Mock private SetOperations<String, Object> setOperations;

    private BookingQueueService queueService;

    @BeforeEach
    void setUp() {
        // Built by hand (not injected into the test class), with a real ObjectMapper and an in-memory metrics registry.
        queueService = new BookingQueueService(
                redisTemplate, bookingService, new ObjectMapper(), new SimpleMeterRegistry(), redisCleanupService);
    }

    private BookingRequestDto request(String email) {
        BookingRequestDto request = new BookingRequestDto();
        request.setEmail(email);
        return request;
    }

    @Test
    void shouldEnqueueBookingRequest() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        String requestId = queueService.enqueueBookingRequest(request(EMAIL));

        assertNotNull(requestId);
        verify(listOperations).rightPush(eq("booking:queue"), anyString());
        verify(valueOperations).set("booking:status:" + requestId, BookingQueueService.RequestStatus.QUEUED.name());
        verify(redisCleanupService).setExpiryOnStatusKey("booking:status:" + requestId);
        verify(setOperations).add("booking:emails:queued", EMAIL);
    }

    @Test
    void shouldRejectAnEmailThatIsAlreadyWaitingInTheQueue() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(setOperations.isMember("booking:emails:queued", EMAIL)).thenReturn(true);

        assertThrows(DuplicateBookingException.class, () -> queueService.enqueueBookingRequest(request(EMAIL)));

        verify(redisTemplate, never()).opsForList();
    }

    @Test
    void shouldGetRequestStatus() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        String status = BookingQueueService.RequestStatus.PROCESSING.name();
        when(valueOperations.get("booking:status:test-request-id")).thenReturn(status);

        String result = queueService.getRequestStatus("test-request-id");

        assertEquals(status, result);
    }

    @Test
    void shouldReturnNullWhenRequestStatusNotFound() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("booking:status:test-request-id")).thenReturn(null);

        assertNull(queueService.getRequestStatus("test-request-id"));
    }

    @Test
    void shouldReportQueueLength() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.size("booking:queue")).thenReturn(5L);

        assertEquals(5L, queueService.getQueueLength());
    }

    @Test
    void shouldReportZeroWhenRedisHasNoQueueYet() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.size("booking:queue")).thenReturn(null);

        assertEquals(0L, queueService.getQueueLength());
    }
}
