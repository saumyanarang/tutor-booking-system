package com.saumyanarang.tutorbooking.repository;

import com.saumyanarang.tutorbooking.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Checks if a user with the given email has any bookings starting after the specified time.
     * Useful for preventing duplicate active bookings.
     *
     * @param email the user's email
     * @param dateTime the date and time to check from (usually current time)
     * @return true if the user has future bookings, false otherwise
     */
    @Query("SELECT CASE WHEN COUNT(r) > 0 THEN true ELSE false END FROM Booking r " +
           "JOIN r.user u JOIN r.slot a " +
           "WHERE u.email = :email AND a.startTime > :dateTime")
    boolean existsByUserEmailAndStartTimeAfter(@Param("email") String email, @Param("dateTime") LocalDateTime dateTime);

    /**
     * Finds all bookings that have expired based on the given threshold time.
     * This is used for cleaning up old bookings.
     *
     * @param thresholdTime Bookings created before this time are considered expired
     * @return List of expired booking entities
     */
    @Query("SELECT r FROM Booking r WHERE r.createdDate < :thresholdTime")
    List<Booking> findExpiredBookings(@Param("thresholdTime") LocalDateTime thresholdTime);
}
