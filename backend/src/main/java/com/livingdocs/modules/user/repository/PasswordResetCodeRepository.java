package com.livingdocs.modules.user.repository;

import com.livingdocs.modules.user.model.PasswordResetCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PasswordResetCodeRepository extends JpaRepository<PasswordResetCode, UUID> {

    List<PasswordResetCode> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
}
