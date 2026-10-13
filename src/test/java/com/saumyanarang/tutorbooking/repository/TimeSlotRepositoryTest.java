package com.saumyanarang.tutorbooking.repository;

import com.saumyanarang.tutorbooking.config.JpaAuditingConfig;
import com.saumyanarang.tutorbooking.entity.Role;
import com.saumyanarang.tutorbooking.entity.Slot;
import com.saumyanarang.tutorbooking.entity.User;
import com.saumyanarang.tutorbooking.support.PostgresContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Repository tests against a real PostgreSQL (Testcontainers).
 *
 * The database is shared with the other test classes, so these tests work in a time window far in the
 * future (about 50 years from now) where no other test creates slots, instead of assuming the table is empty.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
@ActiveProfiles("test")
class TimeSlotRepositoryTest extends PostgresContainerSupport {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private TimeSlotRepository timeSlotRepository;

    private LocalDateTime farFuture() {
        return LocalDateTime.now().plusYears(50).withNano(0);
    }

    private User persistTutor() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User tutor = new User();
        tutor.setEmail("tutor-" + unique + "@example.com");
        tutor.setUserName("tutor_" + unique);
        tutor.setPassword("password");
        tutor.setRole(Role.TUTOR);
        return entityManager.persist(tutor);
    }

    private Slot persistSlot(Long tutorId, LocalDateTime start, LocalDateTime end, boolean reserved) {
        Slot slot = new Slot();
        slot.setTutorId(tutorId);
        slot.setStartTime(start);
        slot.setEndTime(end);
        slot.setReserved(reserved);
        return entityManager.persist(slot);
    }

    // ---------- findNextAvailable (legacy "nearest slot" lookup) ----------

    @Test
    void findNextAvailable_shouldReturnNextUnreservedSlotAfterGivenTime() {
        LocalDateTime base = farFuture();
        persistSlot(null, base.plusHours(1), base.plusHours(2), true);        // reserved: skipped
        Slot expected = persistSlot(null, base.plusHours(2), base.plusHours(3), false);
        persistSlot(null, base.plusHours(3), base.plusHours(4), false);       // later: not the nearest
        entityManager.flush();

        Optional<Slot> result = timeSlotRepository.findNextAvailable(base);

        assertTrue(result.isPresent());
        assertEquals(expected.getId(), result.get().getId());
        assertFalse(result.get().isReserved());
    }

    @Test
    void findNextAvailable_shouldReturnEmptyWhenNoUnreservedSlotsAvailable() {
        LocalDateTime base = farFuture();
        persistSlot(null, base.plusHours(1), base.plusHours(2), true);
        persistSlot(null, base.plusHours(2), base.plusHours(3), true);
        entityManager.flush();

        assertTrue(timeSlotRepository.findNextAvailable(base).isEmpty());
    }

    // ---------- browsing free slots ----------

    @Test
    void findAvailableSlots_shouldReturnFreeFutureSlotsSoonestFirst() {
        LocalDateTime base = farFuture();
        User tutor = persistTutor();
        Slot second = persistSlot(tutor.getId(), base.plusHours(4), base.plusHours(5), false);
        Slot first = persistSlot(tutor.getId(), base.plusHours(2), base.plusHours(3), false);
        persistSlot(tutor.getId(), base.plusHours(6), base.plusHours(7), true);   // booked: hidden
        persistSlot(tutor.getId(), base.minusHours(5), base.minusHours(4), false); // before "now": hidden
        entityManager.flush();

        List<Slot> result = timeSlotRepository.findAvailableSlots(base, PageRequest.of(0, 10));

        assertEquals(List.of(first.getId(), second.getId()), result.stream().map(Slot::getId).toList());
    }

    @Test
    void findAvailableSlots_shouldHonourThePageSize() {
        LocalDateTime base = farFuture();
        User tutor = persistTutor();
        Slot first = persistSlot(tutor.getId(), base.plusHours(1), base.plusHours(2), false);
        persistSlot(tutor.getId(), base.plusHours(3), base.plusHours(4), false);
        persistSlot(tutor.getId(), base.plusHours(5), base.plusHours(6), false);
        entityManager.flush();

        List<Slot> result = timeSlotRepository.findAvailableSlots(base, PageRequest.of(0, 1));

        assertEquals(List.of(first.getId()), result.stream().map(Slot::getId).toList());
    }

    @Test
    void findAvailableSlotsByTutor_shouldReturnOnlyThatTutorsFreeSlots() {
        LocalDateTime base = farFuture();
        User tutor = persistTutor();
        User otherTutor = persistTutor();
        Slot mine = persistSlot(tutor.getId(), base.plusHours(1), base.plusHours(2), false);
        persistSlot(tutor.getId(), base.plusHours(3), base.plusHours(4), true);        // mine but booked
        persistSlot(otherTutor.getId(), base.plusHours(1), base.plusHours(2), false);  // someone else's
        entityManager.flush();

        List<Slot> result = timeSlotRepository.findAvailableSlotsByTutor(tutor.getId(), base, PageRequest.of(0, 10));

        assertEquals(List.of(mine.getId()), result.stream().map(Slot::getId).toList());
    }

    // ---------- overlap check ----------

    @Test
    void existsOverlappingSlot_shouldDetectOverlapAndAllowBackToBackSlots() {
        LocalDateTime base = farFuture();
        User tutor = persistTutor();
        persistSlot(tutor.getId(), base, base.plusHours(1), false);
        entityManager.flush();

        assertTrue(timeSlotRepository.existsOverlappingSlot(tutor.getId(), base.plusMinutes(30), base.plusMinutes(90)));
        assertTrue(timeSlotRepository.existsOverlappingSlot(tutor.getId(), base, base.plusHours(1)));
        assertTrue(timeSlotRepository.existsOverlappingSlot(tutor.getId(), base.minusHours(1), base.plusHours(2)));

        assertFalse(timeSlotRepository.existsOverlappingSlot(tutor.getId(), base.plusHours(1), base.plusHours(2)));
        assertFalse(timeSlotRepository.existsOverlappingSlot(tutor.getId(), base.minusHours(1), base));

        // A different tutor may be free at the very same time.
        assertFalse(timeSlotRepository.existsOverlappingSlot(persistTutor().getId(), base, base.plusHours(1)));
    }

    // ---------- schema rules from the migration ----------

    @Test
    void database_shouldRefuseASlotThatEndsBeforeItStarts() {
        LocalDateTime base = farFuture();
        Slot backwards = new Slot();
        backwards.setStartTime(base.plusHours(2));
        backwards.setEndTime(base.plusHours(1));

        assertThrows(DataIntegrityViolationException.class, () -> timeSlotRepository.saveAndFlush(backwards));
    }
}
