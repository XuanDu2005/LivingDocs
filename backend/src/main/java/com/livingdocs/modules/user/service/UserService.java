package com.livingdocs.modules.user.service;

import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.exception.UnauthorizedException;
import com.livingdocs.common.security.AuthenticatedUser;
import com.livingdocs.modules.user.dto.RegisterRequest;
import com.livingdocs.modules.user.dto.UpdateProfileRequest;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * Encapsulates user lifecycle: registration, lookup, profile updates.
 *
 * <p>Implements Spring Security's {@link UserDetailsService} so the
 * authentication manager can load users by email at login time.
 */
@Service
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Register a new user with the given credentials.
     *
     * @throws ConflictException if the email is already taken
     */
    @Transactional
    public User register(RegisterRequest req) {
        String normalized = req.email().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(normalized)) {
            throw new ConflictException("An account with this email already exists");
        }
        User user = new User(normalized, req.displayName(), passwordEncoder.encode(req.password()));
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public User getById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    /**
     * Resolve a user by email, throwing {@link UnauthorizedException} when
     * the account does not exist so that login responses don't leak whether
     * an email is registered.
     */
    @Transactional(readOnly = true)
    public User getByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));
    }

    @Transactional
    public User updateProfile(UUID userId, UpdateProfileRequest req) {
        User user = getById(userId);
        user.setDisplayName(req.displayName());
        if (req.email() != null && !req.email().isBlank()) {
            String normalized = req.email().toLowerCase(Locale.ROOT);
            if (!normalized.equals(user.getEmail()) && userRepository.existsByEmailIgnoreCase(normalized)) {
                throw new ConflictException("An account with this email already exists");
            }
            user.setEmail(normalized);
        }
        return user;
    }

    /**
     * Verify that a raw password matches the stored hash for the given email.
     *
     * @throws UnauthorizedException if the credentials don't match
     */
    @Transactional(readOnly = true)
    public User authenticate(String email, String rawPassword) {
        User user = getByEmail(email);
        if (!user.isEnabled() || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }
        return user;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmailIgnoreCase(email.toLowerCase(Locale.ROOT))
                .map(u -> (UserDetails) new AuthenticatedUser(
                        u.getId(), u.getEmail(), u.getPasswordHash(), u.isEnabled()))
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
}