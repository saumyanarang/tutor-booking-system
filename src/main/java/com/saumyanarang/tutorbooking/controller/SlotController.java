package com.saumyanarang.tutorbooking.controller;

import com.saumyanarang.tutorbooking.dto.slot.CreateSlotRequestDto;
import com.saumyanarang.tutorbooking.dto.slot.SlotResponseDto;
import com.saumyanarang.tutorbooking.service.SlotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Slots", description = "Tutors publish time slots; students browse the free ones")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/slots")
@RequiredArgsConstructor
public class SlotController {

    private final SlotService slotService;

    @Operation(summary = "Publish a slot (tutors only)",
            description = "The slot belongs to the calling tutor. It must start in the future, last at most 8 hours "
                    + "and not overlap another slot of the same tutor.")
    @PostMapping
    public ResponseEntity<SlotResponseDto> createSlot(@RequestBody @Valid CreateSlotRequestDto request,
                                                      Authentication authentication) {
        SlotResponseDto created = slotService.createSlot(authentication.getName(), request.startTime(), request.endTime());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Browse free slots",
            description = "Slots that are not booked and have not started yet, soonest first. "
                    + "Pass tutorId to see one tutor's slots. Page size is capped at 100.")
    @GetMapping
    public List<SlotResponseDto> listAvailable(@RequestParam(required = false) Long tutorId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return slotService.listAvailable(tutorId, page, size);
    }
}
