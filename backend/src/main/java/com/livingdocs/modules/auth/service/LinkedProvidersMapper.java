package com.livingdocs.modules.auth.service;

import com.livingdocs.modules.auth.dto.LinkedProvidersResponse;
import com.livingdocs.modules.user.model.UserOAuthIdentity;
import com.livingdocs.modules.user.repository.UserOAuthIdentityRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Maps {@link UserOAuthIdentity} rows into
 * {@link LinkedProvidersResponse.LinkedProvider} DTOs.
 *
 * <p>Lives here (not in the DTO package) so the user module does not
 * have a circular dependency on the auth module's identity repository.
 */
@Component
public class LinkedProvidersMapper {

    private final UserOAuthIdentityRepository identityRepository;

    public LinkedProvidersMapper(UserOAuthIdentityRepository identityRepository) {
        this.identityRepository = identityRepository;
    }

    @Transactional(readOnly = true)
    public List<LinkedProvidersResponse.LinkedProvider> forUser(UUID userId) {
        return identityRepository.findAllByUserId(userId).stream()
                .map(this::toDto)
                .toList();
    }

    private LinkedProvidersResponse.LinkedProvider toDto(UserOAuthIdentity identity) {
        return new LinkedProvidersResponse.LinkedProvider(
                identity.getProvider().wireValue(),
                identity.getProviderEmail(),
                identity.getDisplayName(),
                identity.getAvatarUrl()
        );
    }
}
