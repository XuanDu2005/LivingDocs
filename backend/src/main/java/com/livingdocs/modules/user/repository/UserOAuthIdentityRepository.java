package com.livingdocs.modules.user.repository;

import com.livingdocs.modules.user.model.OAuthProvider;
import com.livingdocs.modules.user.model.UserOAuthIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserOAuthIdentityRepository extends JpaRepository<UserOAuthIdentity, UUID> {

    Optional<UserOAuthIdentity> findByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);

    List<UserOAuthIdentity> findAllByUserId(UUID userId);

    Optional<UserOAuthIdentity> findByUserIdAndProvider(UUID userId, OAuthProvider provider);

    boolean existsByUserIdAndProvider(UUID userId, OAuthProvider provider);
}
