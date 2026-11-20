// Domain types shared across the admin feature.

import { WorkspaceRole } from '../services/workspaces';

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

/**
 * The editable presentation of a platform role.
 *
 * <p>The `code` is deliberately absent: it is the identifier that
 * JWTs, audit payloads and permission grants refer to, so it is
 * immutable from the API.
 */
export interface UpdateRoleMetadataPayload {
  name?: string;
  description?: string | null;
  displayOrder?: number;
}

// ---------------------------------------------------------------------------
// Admin repository browser.
// ---------------------------------------------------------------------------

export type RepositorySyncStatus =
  | 'CONNECTED'
  | 'ARCHIVED'
  | 'ERROR'
  | 'DISCONNECTED';

export type IndexJobStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED';

export interface AdminRepository {
  id: string;
  githubId: number;
  owner: string;
  name: string;
  fullName: string;
  defaultBranch: string;
  htmlUrl: string | null;
  description: string | null;
  isPrivate: boolean;
  status: RepositorySyncStatus;
  workspaceId: string;
  workspaceName: string;
  workspaceSlug: string;
  connectedBy: string | null;
  connectedAt: string | null;
  lastSyncedAt: string | null;
  documentCount: number;
  openDriftCount: number;
  totalDriftCount: number;
  lastIndexJobStatus: IndexJobStatus | null;
  lastIndexJobAt: string | null;
  updatedAt: string | null;
}

export interface AdminRepositoryReindexResponse {
  jobId: string;
  status: IndexJobStatus;
  createdBy: string;
  workspaceId: string;
}

// ---------------------------------------------------------------------------
// Admin AI usage.
// ---------------------------------------------------------------------------

export interface AdminAiUsageRow {
  workspaceId: string;
  workspaceName: string;
  workspaceSlug: string;
  todayTokens: number;
  monthTokens: number;
  dailyLimit: number | null;
  monthlyLimit: number | null;
  rateLimitPerMinute: number | null;
  last30DaysTokens: number;
  last30DaysRequests: number;
  last30DaysCost: number | string;
  lastRequestAt: string | null;
}

export interface DailyUsagePoint {
  date: string;
  tokens: number;
  requests: number;
  cost: number | string;
}

export interface AdminAiPlatformSummary {
  workspaceCount: number;
  last30DaysTokens: number;
  last30DaysRequests: number;
  last30DaysCost: number | string;
  todayTokens: number;
}

export interface UpdateAdminLimitsPayload {
  dailyLimit: number | null;
  monthlyLimit: number | null;
  rateLimitPerMinute: number | null;
}

export interface UserWithRoles {
  id: string;
  email: string;
  displayName: string | null;
  /** Public URL of the user's profile picture, or `null` when unset. */
  avatarUrl?: string | null;
  enabled: boolean;
  /**
   * `true` when the account still carries a temporary credential and
   * has to replace it before it can be used.
   */
  mustChangePassword?: boolean;
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

export interface CreateUserPayload {
  email: string;
  displayName: string;
  /**
   * The password the account is created with. It is temporary: the
   * account owner has to replace it before they can use the account.
   */
  temporaryPassword: string;
  roles: string[];
  emailVerified?: boolean;
  enabled?: boolean;
}

export interface ResetUserPasswordPayload {
  newPassword: string;
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

/**
 * A member of a workspace, seen from the administrator's side.
 *
 * <p>`owner` marks the account the `workspaces.owner_id` foreign key
 * points at. The owner cannot be demoted below `MANAGER` nor removed,
 * so the UI disables those two actions on that row.
 */
export interface AdminWorkspaceMember {
  id: string;
  workspaceId: string;
  userId: string;
  userEmail: string | null;
  displayName: string | null;
  role: WorkspaceRole;
  owner: boolean;
  joinedAt: string;
}