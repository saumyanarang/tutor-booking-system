package com.saumyanarang.tutorbooking.service;

import com.saumyanarang.tutorbooking.dto.slot.SlotResponseDto;
import com.saumyanarang.tutorbooking.entity.Role;
import com.saumyanarang.tutorbooking.entity.Slot;
import com.saumyanarang.tutorbooking.entity.User;
import com.saumyanarang.tutorbooking.exception.ForbiddenOperationException;
import com.saumyanarang.tutorbooking.exception.InvalidBookingTimeException;
import com.saumyanarang.tutorbooking.exception.ResourceNotFoundException;
import com.saumyanarang.tutorbooking.exception.SlotUnavailableException;
import com.saumyanarang.tutorbooking.repository.TimeSlotRepository;
import com.saumyanarang.tutorbooking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Slot management: tutors publish their own slots, anyone signed in can browse the free ones.
 */
@Service
@RequiredArgsConstructor
public class SlotService {

    static final Duration MAX_SLOT_DURATION = Duration.ofHours(8);
    static final int MAX_PAGE_SIZE = 100;

    private final TimeSlotRepository timeSlotRepository;
    private final UserRepository userRepository;
    private final CacheableOperations cacheableOperations;

    @Transactional
    public SlotResponseDto createSlot(String tutorEmail, LocalDateTime startTime, LocalDateTime endTime) {
        User tutor = userRepository.findByEmail(tutorEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found for email: " + tutorEmail));
        if (tutor.getRole() != Role.TUTOR) {
            throw new ForbiddenOperationException("Only tutors can create slots");
        }

        if (!startTime.isAfter(LocalDateTime.now())) {
            throw new InvalidBookingTimeException("A slot must start in the future");
        }
        if (!endTime.isAfter(startTime)) {
            throw new InvalidBookingTimeException("A slot must end after it starts");
        }
        if (Duration.between(startTime, endTime).compareTo(MAX_SLOT_DURATION) > 0) {
            throw new InvalidBookingTimeException("A slot can be at most " + MAX_SLOT_DURATION.toHours() + " hours long");
        }
        if (timeSlotRepository.existsOverlappingSlot(tutor.getId(), startTime, endTime)) {
            throw new SlotUnavailableException("You already have a slot that overlaps this time");
        }

        Slot slot = new Slot();
        slot.setTutorId(tutor.getId());
        slot.setStartTime(startTime);
        slot.setEndTime(endTime);
        slot.setReserved(false);
        Slot saved = timeSlotRepository.save(slot);

        cacheableOperations.evictNextSlotCache();
        return SlotResponseDto.from(saved, tutor.getUserName());
    }

    /**
     * Free slots that have not started yet, soonest first. Optionally limited to one tutor.
     * Page size is clamped to 1..{@value #MAX_PAGE_SIZE} so a caller cannot ask for the whole table.
     */
    @Transactional(readOnly = true)
    public List<SlotResponseDto> listAvailable(Long tutorId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        LocalDateTime now = LocalDateTime.now();

        List<Slot> slots = tutorId == null
                ? timeSlotRepository.findAvailableSlots(now, pageRequest)
                : timeSlotRepository.findAvailableSlotsByTutor(tutorId, now, pageRequest);

        Set<Long> tutorIds = slots.stream()
                .map(Slot::getTutorId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<Long, String> tutorNames = new HashMap<>();
        userRepository.findAllById(tutorIds).forEach(u -> tutorNames.put(u.getId(), u.getUserName()));

        return slots.stream()
                .map(slot -> SlotResponseDto.from(slot, tutorNames.get(slot.getTutorId())))
                .toList();
    }
}
