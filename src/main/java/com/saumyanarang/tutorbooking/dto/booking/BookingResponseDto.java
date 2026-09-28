package com.saumyanarang.tutorbooking.dto.booking;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BookingResponseDto {
    private String requestId;
    private String status;
}
