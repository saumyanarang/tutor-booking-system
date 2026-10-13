package com.saumyanarang.tutorbooking.service;

import com.saumyanarang.tutorbooking.dto.auth.RegisterRequestDto;
import com.saumyanarang.tutorbooking.entity.Role;
import com.saumyanarang.tutorbooking.entity.User;
import com.saumyanarang.tutorbooking.exception.UserAlreadyExistsException;
import com.saumyanarang.tutorbooking.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;

    private RegistrationService registrationService;

    @BeforeEach
    void setUp() {
        registrationService = new RegistrationService(userRepository, passwordEncoder);
    }

    private RegisterRequestDto request(String email, String userName, Role role) {
        RegisterRequestDto request = new RegisterRequestDto();
        request.setEmail(email);
        request.setUserName(userName);
        request.setPassword("supersecret1");
        request.setRole(role);
        return request;
    }

    @Test
    void shouldCreateAnAccountWithAHashedPasswordAndTheChosenRole() {
        when(passwordEncoder.encode("supersecret1")).thenReturn("hashed");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User created = registrationService.register(request("ada@example.com", "ada", Role.TUTOR));

        assertEquals("ada@example.com", created.getEmail());
        assertEquals("ada", created.getUserName());
        assertEquals("hashed", created.getPassword());     // never the plain text
        assertEquals(Role.TUTOR, created.getRole());
    }

    @Test
    void shouldStoreTheEmailTrimmedAndLowerCased() {
        when(passwordEncoder.encode("supersecret1")).thenReturn("hashed");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        registrationService.register(request("  Ada.Lovelace@Example.COM ", " ada ", Role.STUDENT));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertEquals("ada.lovelace@example.com", saved.getValue().getEmail());
        assertEquals("ada", saved.getValue().getUserName());
        verify(userRepository).existsByEmail("ada.lovelace@example.com");   // the duplicate check uses the same form
    }

    @Test
    void shouldRejectAnEmailThatIsAlreadyRegistered() {
        when(userRepository.existsByEmail("ada@example.com")).thenReturn(true);

        assertThrows(UserAlreadyExistsException.class,
                () -> registrationService.register(request("ada@example.com", "ada", Role.STUDENT)));

        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void shouldRejectAUsernameThatIsAlreadyTaken() {
        when(userRepository.existsByEmail("ada@example.com")).thenReturn(false);
        when(userRepository.existsByUserName("ada")).thenReturn(true);

        assertThrows(UserAlreadyExistsException.class,
                () -> registrationService.register(request("ada@example.com", "ada", Role.STUDENT)));

        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void shouldReportAConflictWhenTheDatabaseConstraintLosesARace() {
        // Two sign-ups with the same email slip past the exists-check together; Postgres UNIQUE stops the second.
        when(passwordEncoder.encode("supersecret1")).thenReturn("hashed");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThrows(UserAlreadyExistsException.class,
                () -> registrationService.register(request("ada@example.com", "ada", Role.STUDENT)));
    }
}
