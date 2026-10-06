package com.livingdocs.modules.auth.service;

import com.livingdocs.common.email.EmailMessage;
import com.livingdocs.common.email.EmailService;
import com.livingdocs.common.email.EmailTemplateService;
import com.livingdocs.common.exception.InvalidCodeException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.user.model.EmailVerificationCode;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.EmailVerificationCodeRepository;
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
 * Issues and validates 6-digit email verification codes.
 *
 * <p>Codes are generated with a cryptographically strong PRNG, stored as
 * BCrypt hashes (raw code never persisted), and have a configurable TTL
 * (default 15 minutes). A code can be retried at most {@code maxAttempts}
 * times before it is locked.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final UserRepository userRepository;
    private final EmailVerificationCodeRepository codeRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    private final int ttlMinutes;
    private final int maxAttempts;

    public EmailVerificationService(UserRepository userRepository,
                                    EmailVerificationCodeRepository codeRepository,
                                    EmailService emailService,
                                    PasswordEncoder passwordEncoder,
                                    @Value("${app.auth.email-verification.ttl-minutes:15}") int ttlMinutes,
                                    @Value("${app.auth.email-verification.max-attempts:5}") int maxAttempts) {
        this.userRepository = userRepository;
        this.codeRepository = codeRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.ttlMinutes = ttlMinutes;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Generate a fresh code, persist its hash, and email it to the user.
     *
     * <p>No-op if the account is already verified.
     */
    @Transactional
    public void issueRegistrationCode(User user) {
        if (user.isEmailVerified()) {
            return;
        }
        String code = generateCode();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(ttlMinutes);
        codeRepository.save(new EmailVerificationCode(
                user.getId(), passwordEncoder.encode(code),
                EmailVerificationCode.Purpose.REGISTER, expiresAt));

        emailService.send(EmailTemplateService.registrationVerification(
                user.getEmail(), user.getDisplayName(), code));
        log.info("Issued REGISTER verification code to {}", user.getEmail());
    }

    /**
     * Validate a 6-digit code and mark the user as verified if it matches.
     *
     * @throws InvalidCodeException if the code is wrong, expired, or locked
     * @throws NotFoundException    if no account exists for the email
     */
    @Transactional
    public User verify(String email, String code, EmailVerificationCode.Purpose purpose) {
        String normalized = email.toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmailIgnoreCase(normalized)
                .orElseThrow(() -> new NotFoundException("Account not found"));

        List<EmailVerificationCode> codes = codeRepository
                .findAllByUserIdAndPurposeOrderByCreatedAtDesc(user.getId(), purpose);
        EmailVerificationCode match = codes.stream()
                .filter(c -> !c.isConsumed() && !c.isExpired(OffsetDateTime.now()))
                .findFirst()
                .orElseThrow(() -> new InvalidCodeException("Code expired or already used"));

        if (match.getAttempts() >= maxAttempts) {
            throw new InvalidCodeException("Too many attempts. Please request a new code.");
        }
        match.incrementAttempts();

        if (!passwordEncoder.matches(code, match.getCodeHash())) {
            codeRepository.save(match);
            throw new InvalidCodeException("Invalid verification code");
        }

        match.markConsumed();
        codeRepository.save(match);

        if (purpose == EmailVerificationCode.Purpose.REGISTER) {
            user.markEmailVerified();
        }
        return userRepository.save(user);
    }

    /**
     * Issue a fresh code for the given email. Used by the "resend" endpoint.
     *
     * <p>Returns silently (no exception) if the account does not exist so
     * the API does not leak which addresses are registered.
     */
    @Transactional
    public void resend(String email) {
        String normalized = email.toLowerCase(Locale.ROOT);
        userRepository.findByEmailIgnoreCase(normalized).ifPresent(user -> {
            if (!user.isEmailVerified()) {
                issueRegistrationCode(user);
            }
        });
    }

    private static String generateCode() {
        int n = RNG.nextInt(1_000_000);
        return String.format(Locale.ROOT, "%06d", n);
    }
}
