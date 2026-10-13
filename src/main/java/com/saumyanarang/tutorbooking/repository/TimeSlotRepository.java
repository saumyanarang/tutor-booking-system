package com.saumyanarang.tutorbooking.repository;

import com.saumyanarang.tutorbooking.entity.Slot;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TimeSlotRepository extends JpaRepository<Slot, Long> {

    /**
     * Plain read (no DB lock) of the next free slots, soonest first.
     * The Redis lock + UNIQUE(slot_id) constraint now protect the booking, not a row lock on this query.
     */
    @Query("SELECT t FROM Slot t WHERE t.isReserved = false AND t.startTime >= :now ORDER BY t.startTime ASC")
    List<Slot> findAvailable(@Param("now") LocalDateTime now, Pageable pageable);

    /** Free slots that have not started yet, from every tutor, soonest first (what a student browses). */
    @Query("SELECT t FROM Slot t WHERE t.isReserved = false AND t.startTime > :now ORDER BY t.startTime ASC")
    List<Slot> findAvailableSlots(@Param("now") LocalDateTime now, Pageable pageable);

    /** Same as {@link #findAvailableSlots} but limited to one tutor. */
    @Query("SELECT t FROM Slot t WHERE t.tutorId = :tutorId AND t.isReserved = false AND t.startTime > :now "
            + "ORDER BY t.startTime ASC")
    List<Slot> findAvailableSlotsByTutor(@Param("tutorId") Long tutorId,
                                         @Param("now") LocalDateTime now,
                                         Pageable pageable);

    /** True if the tutor already has a slot that overlaps [start, end). Back-to-back slots do not overlap. */
    @Query("SELECT CASE WHEN COUNT(t) > 0 THEN true ELSE false END FROM Slot t "
            + "WHERE t.tutorId = :tutorId AND t.startTime < :rangeEnd AND t.endTime > :rangeStart")
    boolean existsOverlappingSlot(@Param("tutorId") Long tutorId,
                                  @Param("rangeStart") LocalDateTime start,
                                  @Param("rangeEnd") LocalDateTime end);

    /** Legacy query from the original project (row lock). Kept so existing code still compiles. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = "SELECT t FROM Slot t WHERE t.isReserved = false AND t.startTime >= :now ORDER BY t.startTime ASC")
    List<Slot> findSlots(@Param("now") LocalDateTime now);

    default Optional<Slot> findNextAvailable(LocalDateTime now) {
        List<Slot> slots = findSlots(now);
        return slots.isEmpty() ? Optional.empty() : Optional.of(slots.getFirst());
    }
}
