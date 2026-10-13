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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlotServiceTest {

    private static final String TUTOR_EMAIL = "tutor@example.com";

    @Mock private TimeSlotRepository timeSlotRepository;
    @Mock private UserRepository userRepository;
    @Mock private CacheableOperations cacheableOperations;

    private SlotService slotService;
    private User tutor;

    @BeforeEach
    void setUp() {
        slotService = new SlotService(timeSlotRepository, userRepository, cacheableOperations);
        tutor = new User();
        tutor.setId(10L);
        tutor.setEmail(TUTOR_EMAIL);
        tutor.setUserName("maths_mentor");
        tutor.setRole(Role.TUTOR);
    }

    private LocalDateTime inHours(long hours) {
        return LocalDateTime.now().plusHours(hours);
    }

    // ---------- createSlot ----------

    @Test
    void shouldCreateASlotOwnedByTheTutor() {
        LocalDateTime start = inHours(24);
        LocalDateTime end = start.plusHours(1);
        when(userRepository.findByEmail(TUTOR_EMAIL)).thenReturn(Optional.of(tutor));
        when(timeSlotRepository.existsOverlappingSlot(10L, start, end)).thenReturn(false);
        when(timeSlotRepository.save(any(Slot.class))).thenAnswer(invocation -> {
            Slot saved = invocation.getArgument(0);
            saved.setId(77L);
            return saved;
        });

        SlotResponseDto result = slotService.createSlot(TUTOR_EMAIL, start, end);

        assertEquals(77L, result.id());
        assertEquals(10L, result.tutorId());
        assertEquals("maths_mentor", result.tutorName());
        assertEquals(start, result.startTime());
        assertEquals(end, result.endTime());
        assertFalse(result.booked());
        verify(cacheableOperations).evictNextSlotCache();
    }

    @Test
    void shouldRefuseToCreateASlotForAStudent() {
        User student = new User();
        student.setId(11L);
        student.setEmail("student@example.com");
        student.setRole(Role.STUDENT);
        when(userRepository.findByEmail("student@example.com")).thenReturn(Optional.of(student));

        assertThrows(ForbiddenOperationException.class,
                () -> slotService.createSlot("student@example.com", inHours(24), inHours(25)));

        verifyNoInteractions(timeSlotRepository);
    }

    @Test
    void shouldReportNotFoundForAnUnknownUser() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> slotService.createSlot("ghost@example.com", inHours(24), inHours(25)));
    }

    @Test
    void shouldRejectASlotThatStartsInThePast() {
        when(userRepository.findByEmail(TUTOR_EMAIL)).thenReturn(Optional.of(tutor));

        assertThrows(InvalidBookingTimeException.class,
                () -> slotService.createSlot(TUTOR_EMAIL, inHours(-2), inHours(-1)));

        verifyNoInteractions(timeSlotRepository);
    }

    @Test
    void shouldRejectASlotThatEndsBeforeItStartsOrWhenItStarts() {
        when(userRepository.findByEmail(TUTOR_EMAIL)).thenReturn(Optional.of(tutor));
        LocalDateTime start = inHours(24);

        assertThrows(InvalidBookingTimeException.class, () -> slotService.createSlot(TUTOR_EMAIL, start, start.minusHours(1)));
        assertThrows(InvalidBookingTimeException.class, () -> slotService.createSlot(TUTOR_EMAIL, start, start));
    }

    @Test
    void shouldRejectASlotLongerThanEightHours() {
        when(userRepository.findByEmail(TUTOR_EMAIL)).thenReturn(Optional.of(tutor));
        LocalDateTime start = inHours(24);

        assertThrows(InvalidBookingTimeException.class, () -> slotService.createSlot(TUTOR_EMAIL, start, start.plusHours(9)));
    }

    @Test
    void shouldAcceptASlotOfExactlyEightHours() {
        LocalDateTime start = inHours(24);
        LocalDateTime end = start.plusHours(8);
        when(userRepository.findByEmail(TUTOR_EMAIL)).thenReturn(Optional.of(tutor));
        when(timeSlotRepository.existsOverlappingSlot(10L, start, end)).thenReturn(false);
        when(timeSlotRepository.save(any(Slot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertDoesNotThrow(() -> slotService.createSlot(TUTOR_EMAIL, start, end));
    }

    @Test
    void shouldRejectASlotThatOverlapsOneOfTheTutorsOwnSlots() {
        LocalDateTime start = inHours(24);
        LocalDateTime end = start.plusHours(1);
        when(userRepository.findByEmail(TUTOR_EMAIL)).thenReturn(Optional.of(tutor));
        when(timeSlotRepository.existsOverlappingSlot(10L, start, end)).thenReturn(true);

        assertThrows(SlotUnavailableException.class, () -> slotService.createSlot(TUTOR_EMAIL, start, end));

        verify(timeSlotRepository, never()).save(any(Slot.class));
    }

    // ---------- listAvailable ----------

    private Slot slotOf(long id, Long tutorId) {
        Slot slot = new Slot();
        slot.setId(id);
        slot.setTutorId(tutorId);
        slot.setStartTime(inHours(id));
        slot.setEndTime(inHours(id + 1));
        return slot;
    }

    @Test
    void shouldListFreeSlotsFromEveryTutorWithTheirNames() {
        when(timeSlotRepository.findAvailableSlots(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(slotOf(1L, 10L), slotOf(2L, 10L)));
        when(userRepository.findAllById(any())).thenReturn(List.of(tutor));

        List<SlotResponseDto> result = slotService.listAvailable(null, 0, 20);

        assertEquals(2, result.size());
        assertEquals("maths_mentor", result.get(0).tutorName());
        verify(timeSlotRepository, never()).findAvailableSlotsByTutor(anyLong(), any(LocalDateTime.class), any(Pageable.class));
    }

    @Test
    void shouldListOnlyOneTutorsFreeSlotsWhenATutorIdIsGiven() {
        when(timeSlotRepository.findAvailableSlotsByTutor(anyLong(), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(slotOf(1L, 10L)));
        when(userRepository.findAllById(any())).thenReturn(List.of(tutor));

        List<SlotResponseDto> result = slotService.listAvailable(10L, 0, 20);

        assertEquals(1, result.size());
        verify(timeSlotRepository, never()).findAvailableSlots(any(LocalDateTime.class), any(Pageable.class));
    }

    @Test
    void shouldClampTheRequestedPageSizeAndNegativePage() {
        when(timeSlotRepository.findAvailableSlots(any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of());

        slotService.listAvailable(null, -5, 100_000);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(timeSlotRepository).findAvailableSlots(any(LocalDateTime.class), pageable.capture());
        assertEquals(PageRequest.of(0, SlotService.MAX_PAGE_SIZE), pageable.getValue());
    }
}
