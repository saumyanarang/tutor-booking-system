package com.saumyanarang.tutorbooking.dto.auth;

import com.saumyanarang.tutorbooking.entity.Role;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Data Transfer Object returned by both login and sign-up: the JWT plus who it belongs to.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LoginResponseDto {
    private String token;
    private String tokenType;
    private String email;
    private String userName;
    private Role role;
    private LocalDateTime expiresAt;
}
