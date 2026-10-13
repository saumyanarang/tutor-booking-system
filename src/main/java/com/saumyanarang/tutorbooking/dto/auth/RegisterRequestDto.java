package com.saumyanarang.tutorbooking.dto.auth;

import com.saumyanarang.tutorbooking.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Data Transfer Object for sign-up requests.
 */
@Data
public class RegisterRequestDto {

    @NotBlank(message = "Email is required")
    @Email(message = "Valid email format is required")
    @Size(max = 255, message = "Email must be at most 255 characters")
    private String email;

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 50, message = "Username must be 3 to 50 characters")
    @Pattern(regexp = "^[A-Za-z0-9_.-]*$", message = "Username may only contain letters, digits, '.', '_' and '-'")
    private String userName;

    // BCrypt only uses the first 72 bytes, so longer passwords are rejected rather than silently truncated.
    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters")
    private String password;

    @NotNull(message = "Role is required (TUTOR or STUDENT)")
    private Role role;
}
