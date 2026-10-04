package com.livingdocs.modules.workspace.controller;

import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.workspace.dto.AddMemberRequest;
import com.livingdocs.modules.workspace.dto.CreateWorkspaceRequest;
import com.livingdocs.modules.workspace.dto.UpdateMemberRoleRequest;
import com.livingdocs.modules.workspace.dto.UpdateWorkspaceRequest;
import com.livingdocs.modules.workspace.dto.WorkspaceMemberResponse;
import com.livingdocs.modules.workspace.dto.WorkspaceResponse;
import com.livingdocs.modules.workspace.model.Workspace;
import com.livingdocs.modules.workspace.model.WorkspaceMember;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Workspace REST endpoints. All endpoints require authentication and
 * additionally enforce workspace membership where applicable.
 */
@RestController
@RequestMapping("/api/v1/workspaces")
@Tag(name = "Workspaces", description = "Workspace and membership management")
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final UserRepository userRepository;

    public WorkspaceController(WorkspaceService workspaceService,
                               UserRepository userRepository) {
        this.workspaceService = workspaceService;
        this.userRepository = userRepository;
    }

    @PostMapping
    @Operation(summary = "Create a workspace")
    public ResponseEntity<WorkspaceResponse> create(@Valid @RequestBody CreateWorkspaceRequest req) {
        UUID actor = CurrentUser.requireId();
        Workspace created = workspaceService.create(actor, req);
        return ResponseEntity
                .created(URI.create("/api/v1/workspaces/" + created.getId()))
                .body(WorkspaceResponse.from(created));
    }

    @GetMapping
    @Operation(summary = "List workspaces the caller is a member of")
    public ResponseEntity<List<WorkspaceResponse>> listMine() {
        UUID actor = CurrentUser.requireId();
        List<WorkspaceResponse> body = workspaceService.listForUser(actor).stream()
                .map(WorkspaceResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{workspaceId}")
    @Operation(summary = "Get a workspace by id")
    public ResponseEntity<WorkspaceResponse> get(@PathVariable UUID workspaceId) {
        UUID actor = CurrentUser.requireId();
        workspaceService.requireMember(actor, workspaceId);
        return ResponseEntity.ok(WorkspaceResponse.from(workspaceService.getById(workspaceId)));
    }

    @PutMapping("/{workspaceId}")
    @Operation(summary = "Update a workspace")
    public ResponseEntity<WorkspaceResponse> update(@PathVariable UUID workspaceId,
                                                    @Valid @RequestBody UpdateWorkspaceRequest req) {
        UUID actor = CurrentUser.requireId();
        Workspace updated = workspaceService.update(actor, workspaceId, req);
        return ResponseEntity.ok(WorkspaceResponse.from(updated));
    }

    @DeleteMapping("/{workspaceId}")
    @Operation(summary = "Delete a workspace (owner only)")
    public ResponseEntity<Void> delete(@PathVariable UUID workspaceId) {
        UUID actor = CurrentUser.requireId();
        workspaceService.delete(actor, workspaceId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{workspaceId}/members")
    @Operation(summary = "Add a member to a workspace")
    public ResponseEntity<WorkspaceMemberResponse> addMember(@PathVariable UUID workspaceId,
                                                              @Valid @RequestBody AddMemberRequest req) {
        UUID actor = CurrentUser.requireId();
        WorkspaceMember member = workspaceService.addMember(actor, workspaceId, req);
        return ResponseEntity.ok(toMemberResponse(member));
    }

    @GetMapping("/{workspaceId}/members")
    @Operation(summary = "List members of a workspace")
    public ResponseEntity<List<WorkspaceMemberResponse>> listMembers(@PathVariable UUID workspaceId) {
        UUID actor = CurrentUser.requireId();
        List<WorkspaceMember> members = workspaceService.listMembers(actor, workspaceId);
        List<WorkspaceMemberResponse> body = members.stream()
                .map(this::toMemberResponse)
                .toList();
        return ResponseEntity.ok(body);
    }

    @PatchMapping("/{workspaceId}/members/{userId}")
    @Operation(summary = "Change a member's role")
    public ResponseEntity<WorkspaceMemberResponse> updateMember(@PathVariable UUID workspaceId,
                                                                @PathVariable UUID userId,
                                                                @Valid @RequestBody UpdateMemberRoleRequest req) {
        UUID actor = CurrentUser.requireId();
        WorkspaceMember member = workspaceService.updateMemberRole(actor, workspaceId, userId, req);
        return ResponseEntity.ok(toMemberResponse(member));
    }

    @DeleteMapping("/{workspaceId}/members/{userId}")
    @Operation(summary = "Remove a member from a workspace")
    public ResponseEntity<Void> removeMember(@PathVariable UUID workspaceId,
                                             @PathVariable UUID userId) {
        UUID actor = CurrentUser.requireId();
        workspaceService.removeMember(actor, workspaceId, userId);
        return ResponseEntity.noContent().build();
    }

    private WorkspaceMemberResponse toMemberResponse(WorkspaceMember member) {
        var user = userRepository.findById(member.getUserId()).orElse(null);
        String email = user != null ? user.getEmail() : null;
        String displayName = user != null ? user.getDisplayName() : null;
        return WorkspaceMemberResponse.of(member, email, displayName);
    }
}