package com.saumyanarang.tutorbooking.controller;

import com.saumyanarang.tutorbooking.dto.auth.LoginRequestDto;
import com.saumyanarang.tutorbooking.dto.auth.LoginResponseDto;
import com.saumyanarang.tutorbooking.dto.auth.RegisterRequestDto;
import com.saumyanarang.tutorbooking.entity.User;
import com.saumyanarang.tutorbooking.repository.UserRepository;
import com.saumyanarang.tutorbooking.security.util.JwtUtil;
import com.saumyanarang.tutorbooking.service.RegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Sign up and log in; both return a JWT")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;
    private final RegistrationService registrationService;

    @Operation(summary = "Sign up as a tutor or a student",
            description = "Creates the account and returns a JWT, so the new user is logged in straight away")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Account created",
            content = @Content(schema = @Schema(implementation = LoginResponseDto.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request (bad email, short password, unknown role...)"),
        @ApiResponse(responseCode = "409", description = "Email or username already taken")
    })
    @PostMapping("/register")
    public ResponseEntity<LoginResponseDto> register(@Valid @RequestBody RegisterRequestDto request) {
        User user = registrationService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toTokenResponse(user));
    }

    @Operation(summary = "User login", description = "Authenticates a user and provides a JWT token")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Successful login",
            content = @Content(schema = @Schema(implementation = LoginResponseDto.class))),
        @ApiResponse(responseCode = "401", description = "Invalid credentials"),
        @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequestDto loginRequest) {
        String email = RegistrationService.normalizeEmail(loginRequest.getEmail());
        logger.debug("Login attempt for email: {}", email);

        return userRepository.findByEmail(email)
                .filter(user -> passwordEncoder.matches(loginRequest.getPassword(), user.getPassword()))
                .map(user -> {
                    logger.info("Successful login for user: {}", user.getEmail());
                    return ResponseEntity.ok(toTokenResponse(user));
                })
                .orElseGet(() -> {
                    logger.warn("Failed login attempt for email: {}", email);
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
                });
    }

    private LoginResponseDto toTokenResponse(User user) {
        String token = jwtUtil.generateToken(user.getEmail());
        return LoginResponseDto.builder()
                .token(token)
                .tokenType("Bearer")
                .email(user.getEmail())
                .userName(user.getUserName())
                .role(user.getRole())
                .expiresAt(jwtUtil.getExpirationDate(token))
                .build();
    }
}
