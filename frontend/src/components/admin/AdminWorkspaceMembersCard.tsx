import { FormEvent, useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader2, Trash2, UserPlus, Users } from 'lucide-react';
import { Button } from '../ui/button';
import { Badge } from '../ui/badge';
import { Input } from '../ui/input';
import { Label } from '../ui/label';
import {
  Card, CardContent, CardDescription, CardHeader, CardTitle,
} from '../ui/card';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '../ui/select';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../ui/table';
import {
  Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../ui/dialog';
import { LoadingState, ErrorState, EmptyState } from '../ui/states';
import { describeError } from '../../services/auth';
import { adminApi } from '../../services/adminApi';
import { WorkspaceRole } from '../../services/workspaces';
import { AdminWorkspace, AdminWorkspaceMember } from '../../types/admin';

/**
 * The three roles a member can hold inside a workspace.
 *
 * <p>`DOCUMENTATION_OWNER` owns the documentation governance of the
 * workspace without administering its membership — it grants nothing
 * on its own.
 */
const WORKSPACE_ROLES: WorkspaceRole[] = ['MEMBER', 'DOCUMENTATION_OWNER', 'MANAGER'];

/**
 * Cross-tenant membership management for one workspace.
 *
 * <p>An administrator may not be a member of the workspace they are
 * administering, which is exactly why this panel talks to the
 * `/admin/workspaces/{id}/members` endpoints instead of the regular
 * workspace ones: those enforce "you must be a MANAGER of this
 * workspace", which would lock the administrator out of their own
 * tenant.
 */
export default function AdminWorkspaceMembersCard({
  workspace,
  onChanged,
}: {
  workspace: AdminWorkspace;
  onChanged?: () => void;
}) {
  const { t } = useTranslation();

  const [members, setMembers] = useState<AdminWorkspaceMember[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [busyUserId, setBusyUserId] = useState<string | null>(null);

  // The add-member form.
  const [email, setEmail] = useState<string>('');
  const [role, setRole] = useState<WorkspaceRole>('MEMBER');
  const [adding, setAdding] = useState<boolean>(false);
  const [addError, setAddError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setMembers(await adminApi.listWorkspaceMembers(workspace.id));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }, [workspace.id]);

  useEffect(() => {
    void load();
  }, [load]);

  async function onAdd(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setAdding(true);
    setAddError(null);
    try {
      await adminApi.addWorkspaceMember(workspace.id, {
        email: email.trim(),
        role,
      });
      setEmail('');
      setRole('MEMBER');
      await load();
      onChanged?.();
    } catch (err) {
      setAddError(describeError(err));
    } finally {
      setAdding(false);
    }
  }

  async function onChangeRole(member: AdminWorkspaceMember, next: WorkspaceRole) {
    if (next === member.role) return;
    setBusyUserId(member.userId);
    setError(null);
    try {
      await adminApi.updateWorkspaceMemberRole(workspace.id, member.userId, next);
      await load();
      onChanged?.();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusyUserId(null);
    }
  }

  async function onRemove(member: AdminWorkspaceMember) {
    if (!window.confirm(
      t('adminWorkspaces.confirmRemoveMember', {
        email: member.userEmail ?? member.userId,
      }),
    )) {
      return;
    }
    setBusyUserId(member.userId);
    setError(null);
    try {
      await adminApi.removeWorkspaceMember(workspace.id, member.userId);
      await load();
      onChanged?.();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusyUserId(null);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <Users className="h-4 w-4" />
          {t('adminWorkspaces.membersTitle')}
          <Badge variant="muted">{members.length}</Badge>
        </CardTitle>
        <CardDescription>{t('adminWorkspaces.membersDesc')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* Add member */}
        <form onSubmit={onAdd} className="flex flex-wrap items-end gap-2">
          <div className="min-w-[240px] flex-1 space-y-1.5">
            <Label htmlFor={`admin-add-member-email-${workspace.id}`}>
              {t('adminWorkspaces.memberEmail')}
            </Label>
            <Input
              id={`admin-add-member-email-${workspace.id}`}
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder={t('adminWorkspaces.memberEmailPlaceholder')}
              maxLength={255}
              required
            />
          </div>
          <div className="w-52 space-y-1.5">
            <Label>{t('adminWorkspaces.memberRole')}</Label>
            <Select value={role} onValueChange={(v) => setRole(v as WorkspaceRole)}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {WORKSPACE_ROLES.map((r) => (
                  <SelectItem key={r} value={r}>
                    {t(`adminWorkspaces.role_${r}`)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <Button type="submit" disabled={adding}>
            {adding ? (
              <Loader2 className="mr-1 h-4 w-4 animate-spin" />
            ) : (
              <UserPlus className="mr-1 h-4 w-4" />
            )}
            {t('adminWorkspaces.addMember')}
          </Button>
        </form>
        {addError && (
          <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
            {addError}
          </div>
        )}

        {error && <ErrorState message={error} />}

        {loading ? (
          <LoadingState message={t('adminWorkspaces.loadingMembers')} />
        ) : members.length === 0 ? (
          <EmptyState
            title={t('adminWorkspaces.noMembersTitle')}
            description={t('adminWorkspaces.noMembersDesc')}
          />
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('adminWorkspaces.colMember')}</TableHead>
                <TableHead className="w-[200px]">{t('adminWorkspaces.colRole')}</TableHead>
                <TableHead>{t('adminWorkspaces.colJoined')}</TableHead>
                <TableHead className="w-[100px] text-right">
                  {t('adminWorkspaces.colActions')}
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {members.map((m) => {
                const busy = busyUserId === m.userId;
                return (
                  <TableRow key={m.id}>
                    <TableCell>
                      <div className="font-medium">{m.userEmail ?? '—'}</div>
                      <div className="text-xs text-muted-foreground">
                        {m.displayName ?? m.userId}
                      </div>
                    </TableCell>
                    <TableCell>
                      {m.owner ? (
                        // The owner is protected: the workspace points at
                        // this account through `workspaces.owner_id`, so
                        // it cannot be demoted below MANAGER nor removed.
                        <Badge variant="success" title={t('adminWorkspaces.ownerBadgeHelp')}>
                          {t('adminWorkspaces.ownerBadge')}
                        </Badge>
                      ) : (
                        <Select
                          value={m.role}
                          onValueChange={(v) => void onChangeRole(m, v as WorkspaceRole)}
                          disabled={busy}
                        >
                          <SelectTrigger>
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            {WORKSPACE_ROLES.map((r) => (
                              <SelectItem key={r} value={r}>
                                {t(`adminWorkspaces.role_${r}`)}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      )}
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground whitespace-nowrap">
                      {m.joinedAt ? formatJoined(m.joinedAt) : '—'}
                    </TableCell>
                    <TableCell>
                      <div className="flex justify-end">
                        <RemoveMemberButton
                          member={m}
                          busy={busy}
                          onRemove={() => void onRemove(m)}
                        />
                      </div>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}

/**
 * Removing a member is destructive but recoverable — an administrator
 * can add them back — so it asks for a confirmation rather than the
 * member's email address.
 */
function RemoveMemberButton({
  member,
  busy,
  onRemove,
}: {
  member: AdminWorkspaceMember;
  busy: boolean;
  onRemove: () => void;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);

  if (member.owner) {
    return (
      <Button
        size="sm"
        variant="ghost"
        disabled
        title={t('adminWorkspaces.cannotRemoveOwner')}
      >
        <Trash2 className="mr-1 h-4 w-4" />
        {t('adminWorkspaces.removeMember')}
      </Button>
    );
  }

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <Button
        size="sm"
        variant="ghost"
        className="text-destructive hover:text-destructive"
        disabled={busy}
        onClick={() => setOpen(true)}
      >
        <Trash2 className="mr-1 h-4 w-4" />
        {t('adminWorkspaces.removeMember')}
      </Button>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t('adminWorkspaces.removeMember')}</DialogTitle>
          <DialogDescription>
            {t('adminWorkspaces.confirmRemoveMember', {
              email: member.userEmail ?? member.userId,
            })}
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">{t('common.cancel')}</Button>
          </DialogClose>
          <Button
            variant="destructive"
            disabled={busy}
            onClick={() => {
              setOpen(false);
              onRemove();
            }}
          >
            {busy ? (
              <Loader2 className="mr-1 h-4 w-4 animate-spin" />
            ) : (
              <Trash2 className="mr-1 h-4 w-4" />
            )}
            {t('adminWorkspaces.removeMember')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Format an ISO timestamp as a plain local date.
 *
 * <p>The admin catalogue shows dates, not times: the exact moment a
 * member joined is not what the administrator is looking for.
 */
function formatJoined(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString();
}
