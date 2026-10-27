package com.livingdocs.modules.user.service;

import com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository;
import com.livingdocs.modules.auth.service.LinkedProvidersMapper;
import com.livingdocs.modules.user.dto.UserResponse;
import com.livingdocs.modules.user.model.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Builds {@link UserResponse} objects for every place that needs to
 * expose a user to the API.
 *
 * <p>Centralising the mapping means the avatar URL is resolved the same
 * way everywhere — the storage key lives on the {@link User} entity, but
 * the public URL depends on the configured base URL, which only
 * {@link AvatarStorageService} knows.
 */
@Component
public class UserResponseMapper {

    private final AvatarStorageService avatarStorageService;
    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final LinkedProvidersMapper linkedProvidersMapper;

    public UserResponseMapper(AvatarStorageService avatarStorageService,
                              UserRoleAssignmentRepository roleAssignmentRepository,
                              LinkedProvidersMapper linkedProvidersMapper) {
        this.avatarStorageService = avatarStorageService;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.linkedProvidersMapper = linkedProvidersMapper;
    }

    /** Full mapping: roles, linked providers and avatar URL. */
    @Transactional(readOnly = true)
    public UserResponse toResponse(User user) {
        List<String> roleCodes = roleAssignmentRepository.findActiveRoleCodesByUserId(user.getId());
        return toResponse(user, roleCodes, linkedProvidersMapper.forUser(user.getId()));
    }

    /** Mapping with an explicit role list (avoids a second query). */
    @Transactional(readOnly = true)
    public UserResponse toResponse(User user, List<String> roleCodes) {
        return toResponse(user, roleCodes, linkedProvidersMapper.forUser(user.getId()));
    }

    /** Mapping with explicit roles and linked providers. */
    public UserResponse toResponse(User user,
                                   List<String> roleCodes,
                                   List<com.livingdocs.modules.auth.dto.LinkedProvidersResponse.LinkedProvider> linkedProviders) {
        return UserResponse.from(user, roleCodes, linkedProviders,
                avatarStorageService.publicUrl(user.getAvatarUrl()));
    }
}
