package com.saumyanarang.tutorbooking.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for application metrics to enhance monitoring and observability.
 * Defines metrics that help track system performance and health.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public Timer bookingProcessingTimer(MeterRegistry registry) {
        return Timer.builder("booking.processing.time")
                .description("Time taken to process a booking")
                .register(registry);
    }

    @Bean
    public Timer slotSelectionTimer(MeterRegistry registry) {
        return Timer.builder("booking.slot.selection.time")
                .description("Time taken to select an available slot")
                .register(registry);
    }
}
