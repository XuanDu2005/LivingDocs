import apiClient from './api';

export type WorkspaceRole = 'MANAGER' | 'MEMBER';

export interface Workspace {
  id: string;
  name: string;
  slug: string;
  description: string | null;
  ownerId: string;
  createdAt: string;
  updatedAt: string;
}

export interface WorkspaceMember {
  id: string;
  workspaceId: string;
  userId: string;
  userEmail: string | null;
  displayName: string | null;
  role: WorkspaceRole;
  joinedAt: string;
}

export interface CreateWorkspacePayload {
  name: string;
  slug: string;
  description?: string;
}

export interface UpdateWorkspacePayload {
  name: string;
  description?: string;
}

export interface AddMemberPayload {
  email: string;
  role: WorkspaceRole;
}

export async function listWorkspaces(): Promise<Workspace[]> {
  const { data } = await apiClient.get<Workspace[]>('/workspaces');
  return data;
}

export async function getWorkspace(workspaceId: string): Promise<Workspace> {
  const { data } = await apiClient.get<Workspace>(`/workspaces/${workspaceId}`);
  return data;
}

export async function createWorkspace(
  payload: CreateWorkspacePayload,
): Promise<Workspace> {
  const { data } = await apiClient.post<Workspace>('/workspaces', payload);
  return data;
}

export async function updateWorkspace(
  workspaceId: string,
  payload: UpdateWorkspacePayload,
): Promise<Workspace> {
  const { data } = await apiClient.put<Workspace>(
    `/workspaces/${workspaceId}`,
    payload,
  );
  return data;
}

export async function deleteWorkspace(workspaceId: string): Promise<void> {
  await apiClient.delete(`/workspaces/${workspaceId}`);
}

export async function listMembers(workspaceId: string): Promise<WorkspaceMember[]> {
  const { data } = await apiClient.get<WorkspaceMember[]>(
    `/workspaces/${workspaceId}/members`,
  );
  return data;
}

export async function addMember(
  workspaceId: string,
  payload: AddMemberPayload,
): Promise<WorkspaceMember> {
  const { data } = await apiClient.post<WorkspaceMember>(
    `/workspaces/${workspaceId}/members`,
    payload,
  );
  return data;
}

export async function updateMemberRole(
  workspaceId: string,
  userId: string,
  role: WorkspaceRole,
): Promise<WorkspaceMember> {
  const { data } = await apiClient.patch<WorkspaceMember>(
    `/workspaces/${workspaceId}/members/${userId}`,
    { role },
  );
  return data;
}

export async function removeMember(
  workspaceId: string,
  userId: string,
): Promise<void> {
  await apiClient.delete(`/workspaces/${workspaceId}/members/${userId}`);
}