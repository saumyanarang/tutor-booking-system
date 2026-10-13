package com.saumyanarang.tutorbooking.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saumyanarang.tutorbooking.entity.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base class for tests that run the whole application against a REAL PostgreSQL and a REAL Redis
 * (both started by Testcontainers) and drive it through the real security filter chain with MockMvc.
 *
 * It also holds small helpers so tests read like the user journey: register -> publish slot -> book.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest extends PostgresContainerSupport {

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    /** A signed-up user: who they are and the JWT they got back from sign-up. */
    protected record Account(String email, String userName, String token) {
    }

    // ---------- HTTP helpers ----------

    protected ResultActions postJson(String url, Object body, String token) throws Exception {
        MockHttpServletRequestBuilder request = post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(withToken(request, token));
    }

    protected ResultActions getJson(String url, String token) throws Exception {
        return mockMvc.perform(withToken(get(url), token));
    }

    protected ResultActions deleteJson(String url, String token) throws Exception {
        return mockMvc.perform(withToken(delete(url), token));
    }

    private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    protected JsonNode bodyOf(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    // ---------- journey helpers ----------

    /** Signs up through the real endpoint with a unique email/username, and returns the token it hands back. */
    protected Account register(String prefix, Role role) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "-" + suffix + "@example.com";
        String userName = prefix + "_" + suffix;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("userName", userName);
        body.put("password", "supersecret1");
        body.put("role", role.name());

        JsonNode response = bodyOf(postJson("/api/auth/register", body, null).andExpect(status().isCreated()));
        return new Account(email, userName, response.get("token").asText());
    }

    protected Map<String, Object> slotBody(LocalDateTime start, LocalDateTime end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("startTime", start.truncatedTo(ChronoUnit.SECONDS).toString());
        body.put("endTime", end.truncatedTo(ChronoUnit.SECONDS).toString());
        return body;
    }

    /** The tutor publishes a slot; asserts 201 and returns the created slot as JSON. */
    protected JsonNode createSlot(Account tutor, LocalDateTime start, LocalDateTime end) throws Exception {
        return bodyOf(postJson("/api/v1/slots", slotBody(start, end), tutor.token()).andExpect(status().isCreated()));
    }

    protected Map<String, Object> bookingBody(long slotId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slotId", slotId);
        return body;
    }

    /** The student tries to book the slot; returns just the HTTP status so races can be counted. */
    protected int bookSlotStatus(Account student, long slotId) throws Exception {
        return postJson("/api/v1/bookings", bookingBody(slotId), student.token())
                .andReturn().getResponse().getStatus();
    }

    /** A whole-hour start time a few days away, so tests never collide with "now". */
    protected LocalDateTime futureHour(int daysAhead) {
        return LocalDateTime.now().plusDays(daysAhead).truncatedTo(ChronoUnit.HOURS).plusHours(1);
    }
}
