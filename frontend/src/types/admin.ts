// Domain types shared across the admin feature.

export interface Role {
  id: string;
  code: string;
  name: string;
  description: string | null;
  system: boolean;
  displayOrder: number;
  createdAt: string;
  updatedAt: string;
}

export interface Permission {
  id: string;
  code: string;
  name: string;
  description: string | null;
  category: string;
  displayOrder: number;
  system: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface RolePermissionRow {
  id: string;
  code: string;
  name: string;
  description: string | null;
  category: string;
  displayOrder: number;
  granted: boolean;
  grantedAt: string | null;
}

export interface RolePermissionMatrix {
  roleId: string;
  roleCode: string;
  roleName: string;
  roleSystem: boolean;
  totalPermissions: number;
  grantedCount: number;
  permissions: RolePermissionRow[];
}

export interface UpdateRolePermissionsPayload {
  grantedPermissionIds: string[];
}

export interface UserWithRoles {
  id: string;
  email: string;
  displayName: string | null;
  enabled: boolean;
  createdAt: string | null;
  roles: string[];
}

export type PruneStrategy = 'HARD_DELETE' | 'ARCHIVE' | 'ANONYMIZE';

export interface AuditRetentionPolicy {
  id: string;
  entityType: string;
  description: string | null;
  retentionDays: number;
  pruneStrategy: PruneStrategy;
  enabled: boolean;
  lastPrunedAt: string | null;
  updatedAt: string;
}

export interface UpdateRetentionPolicyPayload {
  entityType: string;
  existingEntityType?: string;
  retentionDays: number;
  description?: string | null;
  enabled?: boolean;
  pruneStrategy?: PruneStrategy;
}

export interface AssignRolesPayload {
  roles: string[];
}

export interface AdminWorkspace {
  id: string;
  name: string;
  slug: string;
  description: string | null;
  ownerId: string;
  memberCount: number;
  managerCount: number;
  createdAt: string;
  updatedAt: string;
}