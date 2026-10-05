import { FormEvent, useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  AlertTriangle,
  ArrowLeft,
  Check,
  FileText,
  Library,
  Shield,
  Sparkles,
  Stethoscope,
  Trash2,
  UserPlus,
  Users as UsersIcon,
} from 'lucide-react';
import { PageTitle } from '../components/PageTitle';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Textarea } from '../components/ui/textarea';
import { Badge } from '../components/ui/badge';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '../components/ui/card';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../components/ui/select';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { useAuth } from '../contexts/AuthContext';
import { describeError } from '../services/auth';
import {
  addMember,
  deleteWorkspace,
  getWorkspace,
  listMembers,
  removeMember,
  updateMemberRole,
  updateWorkspace,
  Workspace,
  WorkspaceMember,
  WorkspaceRole,
} from '../services/workspaces';
import { RepositoriesPanel } from '../components/RepositoriesPanel';
import { format } from 'date-fns';

const toolLinks = (workspaceId: string) => [
  { to: `/workspaces/${workspaceId}/documents`, icon: FileText, label: 'Documents' },
  { to: `/workspaces/${workspaceId}/templates`, icon: Sparkles, label: 'Templates' },
  { to: `/workspaces/${workspaceId}/drift`, icon: AlertTriangle, label: 'Drift alerts' },
  { to: `/workspaces/${workspaceId}/reviews`, icon: Shield, label: 'Review queue' },
  { to: `/workspaces/${workspaceId}/knowledge`, icon: Library, label: 'Knowledge base' },
  { to: `/workspaces/${workspaceId}/health`, icon: Stethoscope, label: 'Health dashboard' },
];

export default function WorkspaceDetailPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { user } = useAuth();
  const navigate = useNavigate();

  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [members, setMembers] = useState<WorkspaceMember[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  const [name, setName] = useState<string>('');
  const [description, setDescription] = useState<string>('');
  const [saving, setSaving] = useState<boolean>(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [savedMessage, setSavedMessage] = useState<string | null>(null);

  const [memberEmail, setMemberEmail] = useState<string>('');
  const [memberRole, setMemberRole] = useState<WorkspaceRole>('MEMBER');
  const [addingMember, setAddingMember] = useState<boolean>(false);
  const [addMemberError, setAddMemberError] = useState<string | null>(null);

  const loadAll = useCallback(async () => {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    try {
      const [ws, memberList] = await Promise.all([
        getWorkspace(workspaceId),
        listMembers(workspaceId),
      ]);
      setWorkspace(ws);
      setMembers(memberList);
      setName(ws.name);
      setDescription(ws.description ?? '');
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }, [workspaceId]);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  const isOwner = workspace && user && workspace.ownerId === user.id;
  const isManager = members.some((m) => m.userId === user?.id && m.role === 'MANAGER');
  const canManage = !!isManager || !!isOwner;

  async function onSave(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!workspaceId) return;
    setSaving(true);
    setSaveError(null);
    setSavedMessage(null);
    try {
      const updated = await updateWorkspace(workspaceId, {
        name,
        description: description || undefined,
      });
      setWorkspace(updated);
      setSavedMessage('Workspace updated.');
    } catch (err) {
      setSaveError(describeError(err));
    } finally {
      setSaving(false);
    }
  }

  async function onAddMember(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!workspaceId) return;
    setAddingMember(true);
    setAddMemberError(null);
    try {
      await addMember(workspaceId, { email: memberEmail, role: memberRole });
      setMemberEmail('');
      setMemberRole('MEMBER');
      await loadAll();
    } catch (err) {
      setAddMemberError(describeError(err));
    } finally {
      setAddingMember(false);
    }
  }

  async function onChangeRole(_memberId: string, userId: string, role: WorkspaceRole) {
    if (!workspaceId) return;
    try {
      await updateMemberRole(workspaceId, userId, role);
      await loadAll();
    } catch (err) {
      setError(describeError(err));
    }
  }

  async function onRemoveMember(userId: string) {
    if (!workspaceId) return;
    try {
      await removeMember(workspaceId, userId);
      await loadAll();
    } catch (err) {
      setError(describeError(err));
    }
  }

  async function onDeleteWorkspace() {
    if (!workspaceId) return;
    if (!confirm('Delete this workspace? This action cannot be undone.')) return;
    try {
      await deleteWorkspace(workspaceId);
      navigate('/workspaces', { replace: true });
    } catch (err) {
      setError(describeError(err));
    }
  }

  if (loading) return <LoadingState message="Loading workspace…" />;
  if (error && !workspace) return <ErrorState message={error} />;
  if (!workspace) return <EmptyState title="Workspace not found" />;

  return (
    <div className="space-y-6">
      <div>
        <Button variant="ghost" size="sm" asChild>
          <Link to="/workspaces">
            <ArrowLeft className="mr-1 h-4 w-4" /> All workspaces
          </Link>
        </Button>
      </div>

      <PageTitle
        title={workspace.name}
        subtitle={workspace.description ?? undefined}
        badge={<Badge variant="muted">{workspace.slug}</Badge>}
        action={
          isOwner && (
            <Button variant="destructive" size="sm" onClick={() => void onDeleteWorkspace()}>
              <Trash2 className="mr-1 h-4 w-4" /> Delete
            </Button>
          )
        }
      />

      {error && <ErrorState message={error} />}

      {isManager && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Workspace settings</CardTitle>
            <CardDescription>Update the name and description visible to members.</CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={onSave} className="space-y-3">
              <div className="space-y-1.5">
                <Label htmlFor="ws-name">Name</Label>
                <Input
                  id="ws-name"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="ws-desc">Description</Label>
                <Textarea
                  id="ws-desc"
                  value={description}
                  onChange={(e) => setDescription(e.target.value)}
                  rows={2}
                />
              </div>
              {saveError && (
                <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
                  {saveError}
                </div>
              )}
              {savedMessage && (
                <div className="flex items-center gap-2 text-sm text-success">
                  <Check className="h-4 w-4" />
                  {savedMessage}
                </div>
              )}
              <Button type="submit" disabled={saving}>
                {saving ? 'Saving…' : 'Save changes'}
              </Button>
            </form>
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <UsersIcon className="h-4 w-4" />
            Members
            <Badge variant="muted">{members.length}</Badge>
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {members.length === 0 ? (
            <p className="text-sm text-muted-foreground">No members yet.</p>
          ) : (
            <div className="space-y-2">
              {members.map((m) => (
                <div
                  key={m.id}
                  className="flex items-center justify-between rounded-md border bg-muted/30 px-3 py-2"
                >
                  <div>
                    <div className="text-sm font-medium">
                      {m.displayName ?? m.userEmail ?? m.userId}
                    </div>
                    <div className="text-xs text-muted-foreground">
                      {m.userEmail} · joined {format(new Date(m.joinedAt), 'MMM d, yyyy')}
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    {isManager && m.userId !== workspace.ownerId ? (
                      <>
                        <Select
                          value={m.role}
                          onValueChange={(v) =>
                            void onChangeRole(m.id, m.userId, v as WorkspaceRole)
                          }
                        >
                          <SelectTrigger className="h-8 w-32">
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            <SelectItem value="MEMBER">Member</SelectItem>
                            <SelectItem value="MANAGER">Manager</SelectItem>
                          </SelectContent>
                        </Select>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => void onRemoveMember(m.userId)}
                        >
                          Remove
                        </Button>
                      </>
                    ) : (
                      <Badge variant={m.role === 'MANAGER' ? 'info' : 'muted'}>
                        {m.role}
                      </Badge>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}

          {isManager && (
            <form onSubmit={onAddMember} className="space-y-2 border-t pt-3">
              <div className="flex items-center gap-2 text-sm font-medium">
                <UserPlus className="h-4 w-4" /> Invite a member
              </div>
              <div className="flex gap-2">
                <Input
                  type="email"
                  placeholder="email@example.com"
                  value={memberEmail}
                  onChange={(e) => setMemberEmail(e.target.value)}
                  required
                  className="flex-[2]"
                />
                <Select value={memberRole} onValueChange={(v) => setMemberRole(v as WorkspaceRole)}>
                  <SelectTrigger className="flex-1">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="MEMBER">Member</SelectItem>
                    <SelectItem value="MANAGER">Manager</SelectItem>
                  </SelectContent>
                </Select>
                <Button type="submit" disabled={addingMember}>
                  {addingMember ? 'Adding…' : 'Add'}
                </Button>
              </div>
              {addMemberError && (
                <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
                  {addMemberError}
                </div>
              )}
            </form>
          )}
        </CardContent>
      </Card>

      {workspaceId && <RepositoriesPanel workspaceId={workspaceId} canManage={canManage} />}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Documentation tools</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {workspaceId &&
              toolLinks(workspaceId).map((t) => (
                <Button
                  key={t.to}
                  variant="outline"
                  className="h-auto justify-start py-3"
                  asChild
                >
                  <Link to={t.to}>
                    <t.icon className="mr-2 h-4 w-4 text-primary" />
                    {t.label}
                  </Link>
                </Button>
              ))}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
