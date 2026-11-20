import apiClient from './api';
import { AddMemberPayload, WorkspaceRole } from './workspaces';
import {
  AdminAiPlatformSummary,
  AdminAiUsageRow,
  AdminRepository,
  AdminRepositoryReindexResponse,
  AdminWorkspace,
  AdminWorkspaceMember,
  AssignRolesPayload,
  AuditRetentionPolicy,
  CreateUserPayload,
  DailyUsagePoint,
  Permission,
  ResetUserPasswordPayload,
  Role,
  RolePermissionMatrix,
  UpdateAdminLimitsPayload,
  UpdateRetentionPolicyPayload,
  UpdateRoleMetadataPayload,
  UpdateRolePermissionsPayload,
  UserWithRoles,
} from '../types/admin';

export const adminApi = {
  // Roles
  listRoles: async (): Promise<Role[]> => {
    const { data } = await apiClient.get<Role[]>('/admin/roles');
    return data;
  },

  // Permission catalogue + role-permission matrix
  listPermissions: async (): Promise<Permission[]> => {
    const { data } = await apiClient.get<Permission[]>('/admin/permissions');
    return data;
  },

  getRolePermissionMatrix: async (roleId: string): Promise<RolePermissionMatrix> => {
    const { data } = await apiClient.get<RolePermissionMatrix>(
      `/admin/permissions/roles/${roleId}/matrix`,
    );
    return data;
  },

  updateRolePermissionMatrix: async (
    roleId: string,
    payload: UpdateRolePermissionsPayload,
  ): Promise<RolePermissionMatrix> => {
    const { data } = await apiClient.put<RolePermissionMatrix>(
      `/admin/permissions/roles/${roleId}/matrix`,
      payload,
    );
    return data;
  },

  // Users-with-roles (admin view)
  listUsersWithRoles: async (): Promise<UserWithRoles[]> => {
    const { data } = await apiClient.get<UserWithRoles[]>('/admin/users-with-roles');
    return data;
  },

  replaceRoles: async (userId: string, payload: AssignRolesPayload): Promise<string[]> => {
    const { data } = await apiClient.put<{ userId: string; roles: string[] }>(
      `/admin/users/${userId}/roles`,
      payload,
    );
    return data.roles;
  },

  /**
   * Edit the presentation of a platform role: its display name, its
   * description and where it sits in the role picker.
   *
   * <p>The role's `code` is not editable — it is the identifier that
   * JWTs, audit payloads and permission grants refer to.
   */
  updateRoleMetadata: async (
    roleId: string,
    payload: UpdateRoleMetadataPayload,
  ): Promise<Role> => {
    const { data } = await apiClient.put<Role>(`/admin/roles/${roleId}`, payload);
    return data;
  },

  // Enable / disable user
  setUserEnabled: async (userId: string, enabled: boolean): Promise<void> => {
    await apiClient.put(`/admin/users/${userId}/enabled`, { enabled });
  },

  // ---- user lifecycle ----

  /**
   * Create a platform account with a temporary password.
   *
   * <p>The account lands in the "must change password" state: the
   * temporary credential only gets the new user to the login screen.
   */
  createUser: async (payload: CreateUserPayload): Promise<UserWithRoles> => {
    const { data } = await apiClient.post<UserWithRoles>('/admin/users', payload);
    return data;
  },

  /**
   * Replace a user's password with one the administrator chose.
   *
   * <p>The account's other sessions are revoked and it is flagged as
   * needing a password change, so the owner replaces the credential.
   */
  resetUserPassword: async (
    userId: string,
    payload: ResetUserPasswordPayload,
  ): Promise<UserWithRoles> => {
    const { data } = await apiClient.post<UserWithRoles>(
      `/admin/users/${userId}/reset-password`,
      payload,
    );
    return data;
  },

  /**
   * Delete a platform account.
   */
  deleteUser: async (userId: string): Promise<void> => {
    await apiClient.delete(`/admin/users/${userId}`);
  },

  // Audit retention
  listRetentionPolicies: async (): Promise<AuditRetentionPolicy[]> => {
    const { data } = await apiClient.get<AuditRetentionPolicy[]>('/admin/audit-retention');
    return data;
  },

  upsertRetentionPolicy: async (
    payload: UpdateRetentionPolicyPayload,
  ): Promise<AuditRetentionPolicy> => {
    const { data } = await apiClient.put<AuditRetentionPolicy>(
      '/admin/audit-retention',
      payload,
    );
    return data;
  },

  deleteRetentionPolicy: async (entityType: string): Promise<void> => {
    await apiClient.delete(`/admin/audit-retention/${entityType}`);
  },

  runPruneNow: async (): Promise<{ totalPruned: number; policiesProcessed: number }> => {
    const { data } = await apiClient.post<{ totalPruned: number; policiesProcessed: number }>(
      '/admin/audit-retention/run'
    );
    return data;
  },

  // Cross-tenant workspace management
  listAllWorkspaces: async (): Promise<AdminWorkspace[]> => {
    const { data } = await apiClient.get<AdminWorkspace[]>('/admin/workspaces');
    return data;
  },

  getAdminWorkspace: async (workspaceId: string): Promise<AdminWorkspace> => {
    const { data } = await apiClient.get<AdminWorkspace>(`/admin/workspaces/${workspaceId}`);
    return data;
  },

  deleteWorkspace: async (workspaceId: string): Promise<void> => {
    await apiClient.delete(`/admin/workspaces/${workspaceId}`);
  },

  /**
   * Cross-tenant membership management.
   *
   * <p>These bypass the `MANAGER`-of-this-workspace requirement that
   * the regular `/workspaces/{id}/members` endpoints enforce: the
   * administrator may not be a member of the workspace they are
   * administering.
   */
  listWorkspaceMembers: async (workspaceId: string): Promise<AdminWorkspaceMember[]> => {
    const { data } = await apiClient.get<AdminWorkspaceMember[]>(
      `/admin/workspaces/${workspaceId}/members`,
    );
    return data;
  },

  addWorkspaceMember: async (
    workspaceId: string,
    payload: AddMemberPayload,
  ): Promise<AdminWorkspaceMember> => {
    const { data } = await apiClient.post<AdminWorkspaceMember>(
      `/admin/workspaces/${workspaceId}/members`,
      payload,
    );
    return data;
  },

  updateWorkspaceMemberRole: async (
    workspaceId: string,
    userId: string,
    role: WorkspaceRole,
  ): Promise<AdminWorkspaceMember> => {
    const { data } = await apiClient.patch<AdminWorkspaceMember>(
      `/admin/workspaces/${workspaceId}/members/${userId}`,
      { role },
    );
    return data;
  },

  removeWorkspaceMember: async (
    workspaceId: string,
    userId: string,
  ): Promise<void> => {
    await apiClient.delete(`/admin/workspaces/${workspaceId}/members/${userId}`);
  },

  // Cross-tenant repository browser
  listAllRepositories: async (): Promise<AdminRepository[]> => {
    const { data } = await apiClient.get<AdminRepository[]>('/admin/repositories');
    return data;
  },

  forceReindexRepository: async (repositoryId: string): Promise<AdminRepositoryReindexResponse> => {
    const { data } = await apiClient.post<AdminRepositoryReindexResponse>(
      `/admin/repositories/${repositoryId}/reindex`,
    );
    return data;
  },

  unlinkRepository: async (repositoryId: string): Promise<void> => {
    await apiClient.delete(`/admin/repositories/${repositoryId}`);
  },

  // AI usage
  getPlatformSummary: async (): Promise<AdminAiPlatformSummary> => {
    const { data } = await apiClient.get<AdminAiPlatformSummary>('/admin/ai-usage/summary');
    return data;
  },

  getPlatformChart: async (): Promise<DailyUsagePoint[]> => {
    const { data } = await apiClient.get<DailyUsagePoint[]>('/admin/ai-usage/chart');
    return data;
  },

  getWorkspaceBreakdown: async (): Promise<AdminAiUsageRow[]> => {
    const { data } = await apiClient.get<AdminAiUsageRow[]>('/admin/ai-usage/workspaces');
    return data;
  },

  getWorkspaceAiChart: async (workspaceId: string): Promise<DailyUsagePoint[]> => {
    const { data } = await apiClient.get<DailyUsagePoint[]>(
      `/admin/ai-usage/workspaces/${workspaceId}/chart`,
    );
    return data;
  },

  updateWorkspaceLimits: async (
    workspaceId: string,
    payload: UpdateAdminLimitsPayload,
  ): Promise<AdminAiUsageRow> => {
    const { data } = await apiClient.put<AdminAiUsageRow>(
      `/admin/ai-usage/workspaces/${workspaceId}/limits`,
      payload,
    );
    return data;
  },
};