package com.saumyanarang.tutorbooking.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.saumyanarang.tutorbooking.entity.Slot;
import com.saumyanarang.tutorbooking.repository.TimeSlotRepository;

import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Implementation of CacheableOperations interface to properly leverage Spring's caching.
 * This class separates cacheable operations from the main service logic.
 */
@Service
@RequiredArgsConstructor
public class CacheableOperationsImpl implements CacheableOperations {

    private final TimeSlotRepository timeSlotRepository;
    private static final Logger logger = LoggerFactory.getLogger(CacheableOperationsImpl.class);

    @Override
    @Cacheable(value = "nextSlot", key = "'single'")
    public Optional<Slot> findNextSlotCached(LocalDateTime now) {
        logger.debug("Finding next available time slot (cached)");
        Optional<Slot> slot = timeSlotRepository.findNextAvailable(now);
        if (slot.isPresent()) {
            logger.debug("Found available slot: id={}, startTime={}", slot.get().getId(), slot.get().getStartTime());
        } else {
            logger.debug("No available time slots found");
        }
        return slot;
    }

    @Override
    @CacheEvict(value = "nextSlot", key = "'single'")
    public void evictNextSlotCache() {
        logger.debug("Evicting nextSlot cache");
    }
}
