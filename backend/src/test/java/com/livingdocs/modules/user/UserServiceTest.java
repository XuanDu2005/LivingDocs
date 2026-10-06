package com.livingdocs.modules.user;

import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.exception.UnauthorizedException;
import com.livingdocs.modules.user.dto.RegisterRequest;
import com.livingdocs.modules.user.dto.UpdateProfileRequest;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder encoder;
    private UserService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        encoder = new BCryptPasswordEncoder();
        service = new UserService(userRepository, encoder);
    }

    @Test
    void register_persistsNewUserAndHashesPassword() {
        when(userRepository.existsByEmailIgnoreCase("alice@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setEmailForTest(UUID.randomUUID());
            u.setCreatedAtForTest(OffsetDateTime.now());
            u.setUpdatedAtForTest(OffsetDateTime.now());
            return u;
        });

        RegisterRequest req = new RegisterRequest(
                "Alice@Example.com", "secret-password", "Alice");

        User created = service.register(req);

        assertNotNull(created.getId());
        assertEquals("alice@example.com", created.getEmail());
        assertEquals("Alice", created.getDisplayName());
        assertTrue(created.isEnabled());
        assertNotEquals(req.password(), created.getPasswordHash());
        assertTrue(encoder.matches(req.password(), created.getPasswordHash()));
    }

    @Test
    void register_duplicateEmailThrowsConflict() {
        when(userRepository.existsByEmailIgnoreCase("alice@example.com")).thenReturn(true);

        assertThrows(ConflictException.class,
                () -> service.register(new RegisterRequest("ALICE@example.com", "another-password", "Alice 2")));
    }

    @Test
    void authenticate_returnsUserOnValidCredentials() {
        User existing = new User("alice@example.com", "Alice", encoder.encode("secret-password"));
        setId(existing);
        when(userRepository.findByEmailIgnoreCase("alice@example.com"))
                .thenReturn(Optional.of(existing));

        User u = service.authenticate("alice@example.com", "secret-password");

        assertEquals("alice@example.com", u.getEmail());
    }

    @Test
    void authenticate_wrongPasswordThrowsUnauthorized() {
        User existing = new User("alice@example.com", "Alice", encoder.encode("secret-password"));
        setId(existing);
        when(userRepository.findByEmailIgnoreCase("alice@example.com"))
                .thenReturn(Optional.of(existing));

        assertThrows(UnauthorizedException.class,
                () -> service.authenticate("alice@example.com", "not-the-password"));
    }

    @Test
    void authenticate_unknownEmailThrowsUnauthorized() {
        when(userRepository.findByEmailIgnoreCase("missing@example.com"))
                .thenReturn(Optional.empty());

        assertThrows(UnauthorizedException.class,
                () -> service.authenticate("missing@example.com", "secret-password"));
    }

    @Test
    void updateProfile_updatesDisplayNameAndEmail() {
        User existing = new User("alice@example.com", "Alice", encoder.encode("x"));
        setId(existing);
        when(userRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userRepository.existsByEmailIgnoreCase("alice2@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User updated = service.updateProfile(existing.getId(),
                new UpdateProfileRequest("Alice 2", "alice2@example.com"));

        assertEquals("Alice 2", updated.getDisplayName());
        assertEquals("alice2@example.com", updated.getEmail());
    }

    @Test
    void getById_unknownIdThrowsNotFound() {
        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.getById(UUID.randomUUID()));
    }

    @Test
    void loadUserByUsername_returnsUserDetails() {
        User existing = new User("alice@example.com", "Alice", encoder.encode("pw"));
        setId(existing);
        when(userRepository.findByEmailIgnoreCase(argThat(s -> s.equalsIgnoreCase("alice@example.com"))))
                .thenReturn(Optional.of(existing));

        var details = service.loadUserByUsername("ALICE@example.com");

        assertEquals("alice@example.com", details.getUsername());
        assertEquals(existing.getPasswordHash(), details.getPassword());
    }

    private void setId(User user) {
        user.setEmailForTest(UUID.randomUUID());
    }
}