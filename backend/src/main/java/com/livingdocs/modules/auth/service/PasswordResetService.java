package com.livingdocs.modules.auth.service;

import com.livingdocs.common.email.EmailService;
import com.livingdocs.common.email.EmailTemplateService;
import com.livingdocs.common.exception.InvalidCodeException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.user.model.PasswordResetCode;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.PasswordResetCodeRepository;
import com.livingdocs.modules.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/**
 * "Forgot password" flow.
 *
 * <p>Mirrors {@link EmailVerificationService} but with a different TTL and
 * a different side-effect: on success the user's password hash is replaced
 * with the new BCrypt of {@code newPassword}.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetCodeRepository codeRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    private final int ttlMinutes;
    private final int maxAttempts;

    public PasswordResetService(UserRepository userRepository,
                                PasswordResetCodeRepository codeRepository,
                                EmailService emailService,
                                PasswordEncoder passwordEncoder,
                                @Value("${app.auth.password-reset.ttl-minutes:30}") int ttlMinutes,
                                @Value("${app.auth.password-reset.max-attempts:5}") int maxAttempts) {
        this.userRepository = userRepository;
        this.codeRepository = codeRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.ttlMinutes = ttlMinutes;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Generate a fresh reset code and email it. Returns silently if the
     * account does not exist so the API does not leak which addresses
     * are registered.
     */
    @Transactional
    public void requestReset(String email) {
        String normalized = email.toLowerCase(Locale.ROOT);
        userRepository.findByEmailIgnoreCase(normalized).ifPresent(user -> {
            String code = generateCode();
            OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(ttlMinutes);
            codeRepository.save(new PasswordResetCode(
                    user.getId(), passwordEncoder.encode(code), expiresAt));
            emailService.send(EmailTemplateService.passwordReset(
                    user.getEmail(), user.getDisplayName(), code));
            log.info("Issued password reset code to {}", user.getEmail());
        });
    }

    /**
     * Verify a code and apply the new password.
     */
    @Transactional
    public void reset(String email, String code, String newPassword) {
        String normalized = email.toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmailIgnoreCase(normalized)
                .orElseThrow(() -> new NotFoundException("Account not found"));

        List<PasswordResetCode> codes = codeRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId());
        PasswordResetCode match = codes.stream()
                .filter(c -> !c.isConsumed() && !c.isExpired(OffsetDateTime.now()))
                .findFirst()
                .orElseThrow(() -> new InvalidCodeException("Code expired or already used"));

        if (match.getAttempts() >= maxAttempts) {
            throw new InvalidCodeException("Too many attempts. Please request a new code.");
        }
        match.incrementAttempts();

        if (!passwordEncoder.matches(code, match.getCodeHash())) {
            codeRepository.save(match);
            throw new InvalidCodeException("Invalid reset code");
        }

        match.markConsumed();
        codeRepository.save(match);

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("Password reset for user {}", user.getEmail());
    }

    private static String generateCode() {
        int n = RNG.nextInt(1_000_000);
        return String.format(Locale.ROOT, "%06d", n);
    }
}
