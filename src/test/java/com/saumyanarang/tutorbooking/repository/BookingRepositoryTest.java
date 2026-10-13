package com.saumyanarang.tutorbooking.repository;

import com.saumyanarang.tutorbooking.config.JpaAuditingConfig;
import com.saumyanarang.tutorbooking.entity.Booking;
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
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Repository tests against a real PostgreSQL (Testcontainers). Each test runs in a transaction that is
 * rolled back, so tests do not see each other's rows.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)   // use the container, not an embedded DB
@Import(JpaAuditingConfig.class)                                              // fills created_by / created_date
@ActiveProfiles("test")
class BookingRepositoryTest extends PostgresContainerSupport {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private BookingRepository bookingRepository;

    // ---------- helpers ----------

    private User persistUser(Role role) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setEmail("user-" + unique + "@example.com");
        user.setUserName("user_" + unique);
        user.setPassword("password");
        user.setRole(role);
        return entityManager.persist(user);
    }

    private Slot persistSlot(Long tutorId, LocalDateTime start, LocalDateTime end, boolean reserved) {
        Slot slot = new Slot();
        slot.setTutorId(tutorId);
        slot.setStartTime(start);
        slot.setEndTime(end);
        slot.setReserved(reserved);
        return entityManager.persist(slot);
    }

    private Booking persistBooking(User student, Slot slot) {
        Booking booking = new Booking();
        booking.setUser(student);
        booking.setSlot(slot);
        booking.setReservedAt(LocalDateTime.now());
        return entityManager.persist(booking);
    }

    // ---------- existsByUserEmailAndStartTimeAfter ----------

    @Test
    void existsByUserEmailAndStartTimeAfter_shouldReturnTrueWhenFutureBookingExists() {
        LocalDateTime now = LocalDateTime.now();
        User student = persistUser(Role.STUDENT);
        Slot slot = persistSlot(null, now.plusHours(2), now.plusHours(3), true);
        persistBooking(student, slot);
        entityManager.flush();

        assertTrue(bookingRepository.existsByUserEmailAndStartTimeAfter(student.getEmail(), now));
    }

    @Test
    void existsByUserEmailAndStartTimeAfter_shouldReturnFalseWhenNoFutureBookingExists() {
        LocalDateTime now = LocalDateTime.now();
        User student = persistUser(Role.STUDENT);
        Slot slot = persistSlot(null, now.minusHours(2), now.minusHours(1), true);
        persistBooking(student, slot);
        entityManager.flush();

        assertFalse(bookingRepository.existsByUserEmailAndStartTimeAfter(student.getEmail(), now));
    }

    // ---------- existsOverlappingBookingForUser ----------

    @Test
    void existsOverlappingBookingForUser_shouldDetectOverlapAndAllowBackToBackLessons() {
        LocalDateTime base = LocalDateTime.now().plusDays(3).withNano(0);
        User student = persistUser(Role.STUDENT);
        Slot booked = persistSlot(null, base, base.plusHours(1), true);
        persistBooking(student, booked);
        entityManager.flush();

        // Half-overlapping, fully inside and fully containing all count as an overlap.
        assertTrue(bookingRepository.existsOverlappingBookingForUser(student.getId(), base.plusMinutes(30), base.plusMinutes(90)));
        assertTrue(bookingRepository.existsOverlappingBookingForUser(student.getId(), base.plusMinutes(10), base.plusMinutes(20)));
        assertTrue(bookingRepository.existsOverlappingBookingForUser(student.getId(), base.minusHours(1), base.plusHours(2)));

        // Lessons that merely touch (one ends exactly when the next starts) do not.
        assertFalse(bookingRepository.existsOverlappingBookingForUser(student.getId(), base.plusHours(1), base.plusHours(2)));
        assertFalse(bookingRepository.existsOverlappingBookingForUser(student.getId(), base.minusHours(1), base));

        // Another student's calendar is unaffected.
        User other = persistUser(Role.STUDENT);
        assertFalse(bookingRepository.existsOverlappingBookingForUser(other.getId(), base, base.plusHours(1)));
    }

    // ---------- listing ----------

    @Test
    void findAllForStudent_shouldReturnOnlyThatStudentsBookingsSoonestFirst() {
        LocalDateTime base = LocalDateTime.now().plusDays(4).withNano(0);
        User tutor = persistUser(Role.TUTOR);
        User alice = persistUser(Role.STUDENT);
        User bob = persistUser(Role.STUDENT);
        Slot later = persistSlot(tutor.getId(), base.plusHours(5), base.plusHours(6), true);
        Slot sooner = persistSlot(tutor.getId(), base, base.plusHours(1), true);
        Slot bobs = persistSlot(tutor.getId(), base.plusHours(2), base.plusHours(3), true);
        Booking laterBooking = persistBooking(alice, later);
        Booking soonerBooking = persistBooking(alice, sooner);
        persistBooking(bob, bobs);
        entityManager.flush();
        entityManager.clear();

        List<Booking> result = bookingRepository.findAllForStudent(alice.getId());

        assertEquals(List.of(soonerBooking.getId(), laterBooking.getId()), result.stream().map(Booking::getId).toList());
    }

    @Test
    void findAllForTutor_shouldReturnOnlyBookingsOnThatTutorsSlots() {
        LocalDateTime base = LocalDateTime.now().plusDays(4).withNano(0);
        User tutor = persistUser(Role.TUTOR);
        User otherTutor = persistUser(Role.TUTOR);
        User student = persistUser(Role.STUDENT);
        Slot mine = persistSlot(tutor.getId(), base, base.plusHours(1), true);
        Slot theirs = persistSlot(otherTutor.getId(), base.plusHours(2), base.plusHours(3), true);
        persistSlot(tutor.getId(), base.plusHours(4), base.plusHours(5), false);   // free: no booking yet
        Booking onMine = persistBooking(student, mine);
        persistBooking(student, theirs);
        entityManager.flush();
        entityManager.clear();

        List<Booking> result = bookingRepository.findAllForTutor(tutor.getId());

        assertEquals(List.of(onMine.getId()), result.stream().map(Booking::getId).toList());
    }

    // ---------- the database backstops ----------

    @Test
    void countBySlotId_shouldCountBookingsForOneSlot() {
        LocalDateTime base = LocalDateTime.now().plusDays(4).withNano(0);
        Slot slot = persistSlot(null, base, base.plusHours(1), true);
        persistBooking(persistUser(Role.STUDENT), slot);
        entityManager.flush();

        assertEquals(1, bookingRepository.countBySlotId(slot.getId()));
    }

    @Test
    void uniqueConstraintOnSlot_shouldRefuseASecondBookingForTheSameSlot() {
        LocalDateTime base = LocalDateTime.now().plusDays(4).withNano(0);
        Slot slot = persistSlot(null, base, base.plusHours(1), true);
        persistBooking(persistUser(Role.STUDENT), slot);
        entityManager.flush();

        Booking second = new Booking();
        second.setUser(persistUser(Role.STUDENT));
        second.setSlot(slot);
        second.setReservedAt(LocalDateTime.now());

        // This is the last line of defence against double-booking: PostgreSQL itself says no.
        assertThrows(DataIntegrityViolationException.class, () -> {
            bookingRepository.saveAndFlush(second);
        });
    }
}
