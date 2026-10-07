package com.livingdocs.modules.user.repository;

import com.livingdocs.modules.user.model.EmailVerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, UUID> {

    List<EmailVerificationCode> findAllByUserIdAndPurposeOrderByCreatedAtDesc(
            UUID userId, EmailVerificationCode.Purpose purpose);
}
