package com.saumyanarang.tutorbooking.integration;

import com.saumyanarang.tutorbooking.entity.Role;
import com.saumyanarang.tutorbooking.entity.User;
import com.saumyanarang.tutorbooking.exception.BookingNotAvailableException;
import com.saumyanarang.tutorbooking.repository.BookingRepository;
import com.saumyanarang.tutorbooking.repository.TimeSlotRepository;
import com.saumyanarang.tutorbooking.repository.UserRepository;
import com.saumyanarang.tutorbooking.service.BookingTransactionService;
import com.saumyanarang.tutorbooking.service.SlotLockService;
import com.saumyanarang.tutorbooking.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 10-user race, run for real: ten students fire a booking request for the SAME slot at the same
 * instant, against a real PostgreSQL and a real Redis (Testcontainers), through the real HTTP stack.
 * Exactly one must win.
 *
 * The second test removes the Redis lock from the picture and shows that PostgreSQL alone still
 * refuses to double-book - the last of the three protection layers.
 */
class BookingConcurrencyTest extends AbstractIntegrationTest {

    private static final int STUDENTS = 10;

    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private TimeSlotRepository timeSlotRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BookingTransactionService bookingTransactionService;
    @Autowired
    private SlotLockService slotLockService;

    @Test
    void tenStudentsRaceForOneSlot_exactlyOneGetsIt() throws Exception {
        Account tutor = register("race-tutor", Role.TUTOR);
        long slotId = createSlot(tutor, futureHour(5), futureHour(5).plusHours(1)).get("id").asLong();

        List<Account> students = new ArrayList<>();
        for (int i = 0; i < STUDENTS; i++) {
            students.add(register("race-student", Role.STUDENT));
        }

        List<Callable<Integer>> bookingAttempts = new ArrayList<>();
        for (Account student : students) {
            bookingAttempts.add(() -> bookSlotStatus(student, slotId));
        }
        List<Integer> statuses = runAllAtTheSameInstant(bookingAttempts);

        long created = statuses.stream().filter(s -> s == 201).count();
        long conflicts = statuses.stream().filter(s -> s == 409).count();
        assertThat(created).as("students who got the slot, statuses were %s", statuses).isEqualTo(1);
        assertThat(conflicts).as("students told the slot is taken, statuses were %s", statuses).isEqualTo(STUDENTS - 1);

        // The verdict comes from the database, not from the HTTP codes.
        assertThat(bookingRepository.countBySlotId(slotId)).isEqualTo(1);
        assertThat(timeSlotRepository.findById(slotId).orElseThrow().isReserved()).isTrue();

        // And nobody leaked the Redis lock: the slot can be locked again right away.
        String token = slotLockService.tryLock(slotId);
        assertThat(token).as("lock for the slot should have been released").isNotNull();
        slotLockService.unlock(slotId, token);
    }

    @Test
    void postgresAloneStillRefusesToDoubleBook_evenWithoutTheRedisLock() throws Exception {
        Account tutor = register("db-tutor", Role.TUTOR);
        long slotId = createSlot(tutor, futureHour(6), futureHour(6).plusHours(1)).get("id").asLong();

        List<User> students = new ArrayList<>();
        for (int i = 0; i < STUDENTS; i++) {
            Account account = register("db-student", Role.STUDENT);
            students.add(userRepository.findByEmail(account.email()).orElseThrow());
        }

        // Call the transactional step directly, skipping BookingService and therefore the Redis lock.
        List<Callable<Throwable>> attempts = new ArrayList<>();
        for (User student : students) {
            attempts.add(() -> {
                try {
                    bookingTransactionService.createBooking(student, slotId);
                    return null;                       // this one got the slot
                } catch (Throwable t) {
                    return t;                          // refused
                }
            });
        }
        List<Throwable> outcomes = runAllAtTheSameInstant(attempts);

        long winners = outcomes.stream().filter(t -> t == null).count();
        List<Throwable> refusals = outcomes.stream().filter(t -> t != null).toList();

        assertThat(winners).as("transactions that committed a booking, refusals were %s", refusals).isEqualTo(1);
        assertThat(refusals).hasSize(STUDENTS - 1);
        // Losers are refused by the slot re-check, the version check or the UNIQUE(slot_id) constraint.
        assertThat(refusals).allSatisfy(t -> assertThat(t)
                .isInstanceOfAny(DataAccessException.class, BookingNotAvailableException.class, TransactionException.class));

        assertThat(bookingRepository.countBySlotId(slotId)).isEqualTo(1);
    }

    /**
     * Starts one thread per task, holds them all at a starting line, then releases them together so the
     * requests really overlap. Returns the results in the order the tasks were given.
     */
    private <T> List<T> runAllAtTheSameInstant(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch allReady = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);

            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    allReady.countDown();
                    go.await();
                    return task.call();
                }));
            }

            assertThat(allReady.await(30, TimeUnit.SECONDS)).as("all threads started").isTrue();
            go.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
