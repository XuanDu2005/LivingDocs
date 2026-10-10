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