import apiClient from './api';
import {
  AdminWorkspace,
  AssignRolesPayload,
  AuditRetentionPolicy,
  Permission,
  Role,
  RolePermissionMatrix,
  UpdateRetentionPolicyPayload,
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

  // Enable / disable user
  setUserEnabled: async (userId: string, enabled: boolean): Promise<void> => {
    await apiClient.put(`/admin/users/${userId}/enabled`, { enabled });
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
};