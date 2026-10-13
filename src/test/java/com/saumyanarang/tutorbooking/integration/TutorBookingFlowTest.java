package com.saumyanarang.tutorbooking.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.saumyanarang.tutorbooking.entity.Role;
import com.saumyanarang.tutorbooking.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests of the user journey and the access rules, through the real security filter chain,
 * against a real PostgreSQL and Redis: sign-up, tutor/student roles, slot publishing, booking a specific
 * slot, and who may cancel what.
 */
class TutorBookingFlowTest extends AbstractIntegrationTest {

    @Test
    void signUp_createsAccountsAndRejectsBadOrDuplicateOnes() throws Exception {
        Account ada = register("ada", Role.STUDENT);

        // Same email in a different case is the same account -> 409
        postJson("/api/auth/register", signUpBody(ada.email().toUpperCase(), "someone_else", "supersecret1", "STUDENT"), null)
                .andExpect(status().isConflict());

        // Same username with a different email -> 409
        postJson("/api/auth/register", signUpBody("other-" + UUID.randomUUID() + "@example.com", ada.userName(), "supersecret1", "STUDENT"), null)
                .andExpect(status().isConflict());

        // Password too short -> 400
        postJson("/api/auth/register", signUpBody("short-" + UUID.randomUUID() + "@example.com", "short_pw", "abc", "STUDENT"), null)
                .andExpect(status().isBadRequest());

        // Not a role we have -> 400 (not a 500)
        postJson("/api/auth/register", signUpBody("admin-" + UUID.randomUUID() + "@example.com", "wannabe_admin", "supersecret1", "ADMIN"), null)
                .andExpect(status().isBadRequest());

        // Missing role -> 400
        postJson("/api/auth/register", signUpBody("norole-" + UUID.randomUUID() + "@example.com", "no_role", "supersecret1", null), null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_worksAfterSignUp_withAnyEmailCase_andRejectsWrongPassword() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String mixedCaseEmail = "Mixed.Case-" + suffix + "@Example.COM";
        postJson("/api/auth/register", signUpBody(mixedCaseEmail, "mixed_" + suffix, "supersecret1", "TUTOR"), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("TUTOR"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));

        Map<String, String> goodLogin = new LinkedHashMap<>();
        goodLogin.put("email", mixedCaseEmail.toLowerCase());
        goodLogin.put("password", "supersecret1");
        postJson("/api/auth/login", goodLogin, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("TUTOR"));

        Map<String, String> badLogin = new LinkedHashMap<>();
        badLogin.put("email", mixedCaseEmail.toLowerCase());
        badLogin.put("password", "not-the-password");
        postJson("/api/auth/login", badLogin, null).andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoints_needAValidToken() throws Exception {
        getJson("/api/v1/slots", null).andExpect(status().isUnauthorized());
        getJson("/api/v1/bookings/mine", null).andExpect(status().isUnauthorized());
        postJson("/api/v1/bookings", bookingBody(1), null).andExpect(status().isUnauthorized());
        getJson("/api/v1/slots", "this.is.not-a-real-token").andExpect(status().isUnauthorized());
    }

    @Test
    void tutorsPublishSlots_studentsBookASpecificOne_andRolesAreEnforced() throws Exception {
        Account tutor = register("tutor", Role.TUTOR);
        Account otherTutor = register("tutor", Role.TUTOR);
        Account alice = register("alice", Role.STUDENT);
        Account bob = register("bob", Role.STUDENT);

        LocalDateTime start = futureHour(3);
        LocalDateTime end = start.plusHours(1);

        // A student cannot publish a slot.
        postJson("/api/v1/slots", slotBody(start, end), alice.token()).andExpect(status().isForbidden());

        // A tutor can, and the slot is theirs.
        JsonNode slot = createSlot(tutor, start, end);
        long slotId = slot.get("id").asLong();
        long tutorId = slot.get("tutorId").asLong();
        assertThat(slot.get("tutorName").asText()).isEqualTo(tutor.userName());
        assertThat(slot.get("booked").asBoolean()).isFalse();

        // Slot rules: no overlap with your own slots, but back-to-back is fine.
        postJson("/api/v1/slots", slotBody(start.plusMinutes(30), end.plusMinutes(30)), tutor.token())
                .andExpect(status().isConflict());
        createSlot(tutor, end, end.plusHours(1));
        // Slot rules: must be in the future, must end after it starts, at most 8 hours.
        postJson("/api/v1/slots", slotBody(LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1)), tutor.token())
                .andExpect(status().isBadRequest());
        postJson("/api/v1/slots", slotBody(start.plusDays(1), start.plusDays(1).minusHours(1)), tutor.token())
                .andExpect(status().isBadRequest());
        postJson("/api/v1/slots", slotBody(start.plusDays(2), start.plusDays(2).plusHours(9)), tutor.token())
                .andExpect(status().isBadRequest());

        // Students can browse it.
        assertThat(freeSlotIds(alice, tutorId)).contains(slotId);

        // A tutor cannot book. An unknown slot is a 404.
        assertThat(bookSlotStatus(tutor, slotId)).isEqualTo(403);
        assertThat(bookSlotStatus(alice, 987_654_321L)).isEqualTo(404);

        // Alice books that specific slot.
        postJson("/api/v1/bookings", bookingBody(slotId), alice.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slotId").value(slotId))
                .andExpect(jsonPath("$.tutorId").value(tutorId))
                .andExpect(jsonPath("$.studentName").value(alice.userName()));
        long bookingId = bodyOf(getJson("/api/v1/bookings/mine", alice.token())).get(0).get("bookingId").asLong();

        // It is gone from the free list, and Bob cannot take it.
        assertThat(freeSlotIds(bob, tutorId)).doesNotContain(slotId);
        assertThat(bookSlotStatus(bob, slotId)).isEqualTo(409);

        // Alice cannot hold two lessons at the same time, even with different tutors.
        JsonNode sameTimeOtherTutor = createSlot(otherTutor, start, end);
        assertThat(bookSlotStatus(alice, sameTimeOtherTutor.get("id").asLong())).isEqualTo(409);

        // "My bookings": the student sees her lesson, the tutor sees who booked their slot.
        JsonNode aliceBookings = bodyOf(getJson("/api/v1/bookings/mine", alice.token()));
        assertThat(aliceBookings).hasSize(1);
        JsonNode tutorBookings = bodyOf(getJson("/api/v1/bookings/mine", tutor.token()));
        assertThat(tutorBookings).hasSize(1);
        assertThat(tutorBookings.get(0).get("studentName").asText()).isEqualTo(alice.userName());
        assertThat(bodyOf(getJson("/api/v1/bookings/mine", bob.token()))).isEmpty();

        // Only the booking's student or the slot's tutor may cancel.
        deleteJson("/api/v1/bookings/cancel/" + bookingId, bob.token()).andExpect(status().isForbidden());
        deleteJson("/api/v1/bookings/cancel/" + bookingId, otherTutor.token()).andExpect(status().isForbidden());
        deleteJson("/api/v1/bookings/cancel/" + bookingId, alice.token()).andExpect(status().isNoContent());

        // Cancelling frees the slot, so Bob can now have it.
        assertThat(freeSlotIds(bob, tutorId)).contains(slotId);
        assertThat(bookSlotStatus(bob, slotId)).isEqualTo(201);

        // The tutor can cancel a booking on their own slot too.
        long bobsBookingId = bodyOf(getJson("/api/v1/bookings/mine", bob.token())).get(0).get("bookingId").asLong();
        deleteJson("/api/v1/bookings/cancel/" + bobsBookingId, tutor.token()).andExpect(status().isNoContent());
        deleteJson("/api/v1/bookings/cancel/" + bobsBookingId, tutor.token()).andExpect(status().isNotFound());
    }

    @Test
    void nearestSlotEndpoint_isStudentsOnly_andOnlyForYourOwnAccount() throws Exception {
        Account tutor = register("tutor", Role.TUTOR);
        Account student = register("student", Role.STUDENT);
        Account other = register("student", Role.STUDENT);

        Map<String, String> asTutor = Map.of("email", tutor.email());
        postJson("/api/v1/bookings/reserve", asTutor, tutor.token()).andExpect(status().isForbidden());

        // A student may not book on behalf of someone else's email.
        Map<String, String> forSomeoneElse = Map.of("email", other.email());
        postJson("/api/v1/bookings/reserve", forSomeoneElse, student.token()).andExpect(status().isForbidden());
    }

    // ---------- helpers ----------

    private Map<String, Object> signUpBody(String email, String userName, String password, String role) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("userName", userName);
        body.put("password", password);
        if (role != null) {
            body.put("role", role);
        }
        return body;
    }

    private List<Long> freeSlotIds(Account viewer, long tutorId) throws Exception {
        JsonNode slots = bodyOf(getJson("/api/v1/slots?tutorId=" + tutorId + "&size=100", viewer.token())
                .andExpect(status().isOk()));
        return StreamSupport.stream(slots.spliterator(), false)
                .map(node -> node.get("id").asLong())
                .toList();
    }
}
