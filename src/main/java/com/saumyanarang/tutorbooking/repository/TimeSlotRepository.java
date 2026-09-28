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

    /** Legacy query from the original project (row lock). Kept so existing code still compiles. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = "SELECT t FROM Slot t WHERE t.isReserved = false AND t.startTime >= :now ORDER BY t.startTime ASC")
    List<Slot> findSlots(@Param("now") LocalDateTime now);

    default Optional<Slot> findNextAvailable(LocalDateTime now) {
        List<Slot> slots = findSlots(now);
        return slots.isEmpty() ? Optional.empty() : Optional.of(slots.getFirst());
    }
}
