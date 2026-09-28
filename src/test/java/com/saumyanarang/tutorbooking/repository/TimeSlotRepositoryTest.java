package com.saumyanarang.tutorbooking.repository;

import com.saumyanarang.tutorbooking.entity.Slot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class TimeSlotRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private TimeSlotRepository timeSlotRepository;

    @Test
    void findNextAvailable_shouldReturnNextUnreservedSlotAfterGivenTime() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime slotTime1 = now.plusHours(1);
        LocalDateTime slotTime2 = now.plusHours(2);
        LocalDateTime slotTime3 = now.plusHours(3);

        // Already reserved slot (should be skipped)
        Slot slot1 = new Slot();
        slot1.setStartTime(slotTime1);
        slot1.setEndTime(slotTime1.plusHours(1));
        slot1.setReserved(true);

        // Available slot (should be returned)
        Slot slot2 = new Slot();
        slot2.setStartTime(slotTime2);
        slot2.setEndTime(slotTime2.plusHours(1));
        slot2.setReserved(false);

        // Another available slot (later, should not be returned)
        Slot slot3 = new Slot();
        slot3.setStartTime(slotTime3);
        slot3.setEndTime(slotTime3.plusHours(1));
        slot3.setReserved(false);

        // Persist slots
        entityManager.persist(slot1);
        entityManager.persist(slot2);
        entityManager.persist(slot3);
        entityManager.flush();

        // When
        Optional<Slot> result = timeSlotRepository.findNextAvailable(now);

        // Then
        assertTrue(result.isPresent());
        assertEquals(slot2.getStartTime(), result.get().getStartTime());
        assertFalse(result.get().isReserved());
    }

    @Test
    void findNextAvailable_shouldReturnEmptyWhenNoUnreservedSlotsAvailable() {
        // Given
        LocalDateTime now = LocalDateTime.now();

        // All slots are reserved
        Slot slot1 = new Slot();
        slot1.setStartTime(now.plusHours(1));
        slot1.setEndTime(now.plusHours(2));
        slot1.setReserved(true);

        Slot slot2 = new Slot();
        slot2.setStartTime(now.plusHours(2));
        slot2.setEndTime(now.plusHours(3));
        slot2.setReserved(true);

        entityManager.persist(slot1);
        entityManager.persist(slot2);
        entityManager.flush();

        // When
        Optional<Slot> result = timeSlotRepository.findNextAvailable(now);

        // Then
        assertTrue(result.isEmpty());
    }
}
