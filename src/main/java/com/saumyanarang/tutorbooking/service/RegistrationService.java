package com.saumyanarang.tutorbooking.service;

import com.saumyanarang.tutorbooking.dto.auth.RegisterRequestDto;
import com.saumyanarang.tutorbooking.entity.User;
import com.saumyanarang.tutorbooking.exception.UserAlreadyExistsException;
import com.saumyanarang.tutorbooking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Self-service sign-up. Replaces inserting users with SQL.
 */
@Service
@RequiredArgsConstructor
public class RegistrationService {

    private static final Logger logger = LoggerFactory.getLogger(RegistrationService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /** Emails are stored lower-cased and trimmed so "Ada@Example.com" and "ada@example.com" are one account. */
    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public User register(RegisterRequestDto request) {
        String email = normalizeEmail(request.getEmail());
        String userName = request.getUserName().trim();

        if (userRepository.existsByEmail(email)) {
            throw new UserAlreadyExistsException("An account with this email already exists");
        }
        if (userRepository.existsByUserName(userName)) {
            throw new UserAlreadyExistsException("This username is already taken");
        }

        User user = new User();
        user.setEmail(email);
        user.setUserName(userName);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(request.getRole());

        try {
            // saveAndFlush so a lost race on the UNIQUE(email) / UNIQUE(user_name) constraints surfaces here.
            User saved = userRepository.saveAndFlush(user);
            logger.info("Registered new {} account: {}", saved.getRole(), saved.getEmail());
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new UserAlreadyExistsException("An account with this email or username already exists");
        }
    }
}
