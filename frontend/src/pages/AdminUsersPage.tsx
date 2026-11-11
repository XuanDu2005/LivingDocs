import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  KeyRound, ShieldCheck, ShieldOff, Trash2, UserCog, UserPlus, Users, Search, X,
} from 'lucide-react';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import {
  Card, CardContent, CardHeader, CardTitle,
} from '../components/ui/card';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '../components/ui/select';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import {
  Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger,
} from '../components/ui/dialog';
import { PasswordInput } from '../components/ui/password-input';
import { LoadingState, ErrorState } from '../components/ui/states';
import { UserAvatar } from '../components/user/UserAvatar';
import { useAuth } from '../contexts/AuthContext';
import { describeError } from '../services/auth';
import { adminApi } from '../services/adminApi';
import { roleName } from '../services/adminLabels';
import { Role, UserWithRoles } from '../types/admin';
import { format } from 'date-fns';

/**
 * The platform user administration surface.
 *
 * <p>Beyond listing users and toggling their roles, an administrator
 * can run the full account lifecycle here: create an account with a
 * temporary password, reset a password that was lost, and delete an
 * account that is no longer needed.
 */
export default function AdminUsersPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { user } = useAuth();
  const { t } = useTranslation();
  const navigate = useNavigate();

  const [users, setUsers] = useState<UserWithRoles[]>([]);
  const [roles, setRoles] = useState<Role[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const [editUserId, setEditUserId] = useState<string | null>(null);
  const [editRoles, setEditRoles] = useState<string[]>([]);

  // Search + role filter
  const [search, setSearch] = useState<string>('');
  const [roleFilter, setRoleFilter] = useState<string>('ALL');

  const filteredUsers = useMemo(() => {
    const q = search.trim().toLowerCase();
    return users.filter((u) => {
      // Role filter
      if (roleFilter === 'NONE') {
        if (u.roles.length > 0) return false;
      } else if (roleFilter !== 'ALL') {
        if (!u.roles.includes(roleFilter)) return false;
      }
      // Search filter
      if (q.length === 0) return true;
      const email = u.email.toLowerCase();
      const name = (u.displayName ?? '').toLowerCase();
      return email.includes(q) || name.includes(q);
    });
  }, [users, search, roleFilter]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [userList, roleList] = await Promise.all([
        adminApi.listUsersWithRoles(),
        adminApi.listRoles(),
      ]);
      setUsers(userList);
      setRoles(roleList);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!user) {
      navigate('/login', { replace: true });
      return;
    }
    void load();
  }, [user, navigate, load]);

  async function saveRoles(userId: string) {
    setBusy(userId);
    try {
      await adminApi.replaceRoles(userId, { roles: editRoles });
      await load();
      setEditUserId(null);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(null);
    }
  }

  async function toggleEnabled(target: UserWithRoles) {
    setBusy(target.id);
    try {
      await adminApi.setUserEnabled(target.id, !target.enabled);
      await load();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(null);
    }
  }

  function toggleRole(code: string) {
    setEditRoles((prev) =>
      prev.includes(code) ? prev.filter((c) => c !== code) : [...prev, code],
    );
  }

  if (loading) return <LoadingState message={t('adminUsers.loading')} />;
  if (error) return <ErrorState message={error} />;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">{t('adminUsers.title')}</h1>
        <p className="text-sm text-muted-foreground">
          {t('adminUsers.subtitle')}
        </p>
      </div>

      {/* Users table */}
      <Card>
        <CardHeader className="space-y-3">
          <CardTitle className="text-base flex items-center gap-2">
            <Users className="h-4 w-4" /> {t('adminUsers.platformUsers')}
            <Badge variant="muted">
              {filteredUsers.length === users.length
                ? users.length
                : `${filteredUsers.length}/${users.length}`}
            </Badge>
          </CardTitle>
          <div className="flex flex-col sm:flex-row gap-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground pointer-events-none" />
              <Input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder={t('adminUsers.searchPlaceholder')}
                className="pl-8 pr-8"
              />
              {search.length > 0 && (
                <button
                  type="button"
                  onClick={() => setSearch('')}
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                  aria-label={t('adminUsers.clearSearch')}
                >
                  <X className="h-4 w-4" />
                </button>
              )}
            </div>
            <div className="w-full sm:w-56">
              <Select value={roleFilter} onValueChange={setRoleFilter}>
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="ALL">{t('adminUsers.filterAllRoles')}</SelectItem>
                  <SelectItem value="NONE">{t('adminUsers.filterNoRole')}</SelectItem>
                  {roles.map((r) => (
                    <SelectItem key={r.id} value={r.code}>
                      {r.code}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <CreateUserDialog roles={roles} onCreated={load} />
          </div>
        </CardHeader>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-12" />
                <TableHead>{t('adminUsers.colEmail')}</TableHead>
                <TableHead>{t('adminUsers.colDisplayName')}</TableHead>
                <TableHead>{t('adminUsers.colRoles')}</TableHead>
                <TableHead>{t('adminUsers.colStatus')}</TableHead>
                <TableHead>{t('adminUsers.colCreated')}</TableHead>
                <TableHead>{t('adminUsers.colActions')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filteredUsers.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7} className="text-center text-sm text-muted-foreground">
                    {users.length === 0
                      ? t('adminUsers.noUsers')
                      : t('adminUsers.noMatches')}
                  </TableCell>
                </TableRow>
              )}
              {filteredUsers.map((u) => (
                <TableRow key={u.id}>
                  <TableCell>
                    <UserAvatar
                      src={u.avatarUrl}
                      name={u.displayName}
                      email={u.email}
                      size="sm"
                    />
                  </TableCell>
                  <TableCell className="font-medium">{u.email}</TableCell>
                  <TableCell>{u.displayName ?? '—'}</TableCell>
                  <TableCell>
                    <div className="flex flex-wrap gap-1">
                      {u.roles.length === 0 ? (
                        <Badge variant="muted" className="text-[10px]">{t('adminUsers.noRoles')}</Badge>
                      ) : (
                        u.roles.map((r) => (
                          <Badge key={r} variant="muted" className="text-[10px]">{roleName(r, r, t)}</Badge>
                        ))
                      )}
                    </div>
                  </TableCell>
                  <TableCell>
                    <div className="flex flex-wrap items-center gap-1">
                      <Badge variant={u.enabled ? 'success' : 'destructive'}>
                        {u.enabled ? t('adminUsers.active') : t('adminUsers.disabled')}
                      </Badge>
                      {u.mustChangePassword && (
                        <Badge variant="warning" className="text-[10px]">
                          <KeyRound className="mr-1 h-3 w-3" /> {t('adminUsers.mustChangePassword')}
                        </Badge>
                      )}
                    </div>
                  </TableCell>
                  <TableCell className="text-xs text-muted-foreground whitespace-nowrap">
                    {u.createdAt ? format(new Date(u.createdAt), 'MMM d, yyyy') : '—'}
                  </TableCell>
                  <TableCell>
                    <div className="flex gap-1 flex-wrap">
                      <Dialog
                        open={editUserId === u.id}
                        onOpenChange={(o) => {
                          if (o) {
                            setEditUserId(u.id);
                            setEditRoles([...u.roles]);
                          } else {
                            setEditUserId(null);
                          }
                        }}
                      >
                        <DialogTrigger asChild>
                          <Button size="sm" variant="outline" className="h-7 text-xs">
                            <UserCog className="mr-1 h-3 w-3" /> {t('adminUsers.rolesButton')}
                          </Button>
                        </DialogTrigger>
                        <DialogContent>
                          <DialogHeader>
                            <DialogTitle>{t('adminUsers.editRoles')}</DialogTitle>
                            <DialogDescription>{u.email}</DialogDescription>
                          </DialogHeader>
                          <div className="space-y-2 py-2 max-h-80 overflow-y-auto">
                            {roles.map((r) => (
                              <label
                                key={r.id}
                                className="flex items-start gap-3 rounded-md border p-2 hover:bg-muted/40 cursor-pointer"
                              >
                                <input
                                  type="checkbox"
                                  className="mt-1 h-4 w-4 rounded border-border"
                                  checked={editRoles.includes(r.code)}
                                  onChange={() => toggleRole(r.code)}
                                />
                                <div className="flex-1">
                                  <div className="text-sm font-medium">
                                    {r.code}{' '}
                                    <span className="text-xs text-muted-foreground">
                                      ({r.name})
                                    </span>
                                  </div>
                                  {r.description && (
                                    <div className="text-xs text-muted-foreground">
                                      {r.description}
                                    </div>
                                  )}
                                </div>
                              </label>
                            ))}
                          </div>
                          <DialogFooter>
                            <DialogClose asChild><Button variant="outline">{t('common.cancel')}</Button></DialogClose>
                            <Button
                              onClick={() => void saveRoles(u.id)}
                              disabled={busy === u.id || editRoles.length === 0}
                            >
                              {t('adminUsers.saveRoles')}
                            </Button>
                          </DialogFooter>
                          {editRoles.length === 0 && (
                            <p className="text-xs text-destructive">
                              {t('adminUsers.cannotRemoveAllRoles')}
                            </p>
                          )}
                        </DialogContent>
                      </Dialog>

                      <ResetPasswordDialog target={u} onDone={load} />

                      <Button
                        size="sm"
                        variant={u.enabled ? 'outline' : 'default'}
                        className="h-7 text-xs"
                        onClick={() => void toggleEnabled(u)}
                        disabled={busy === u.id || u.id === user?.id}
                        title={u.id === user?.id ? t('adminUsers.cannotDisableSelf') : ''}
                      >
                        {u.enabled
                          ? <><ShieldOff className="mr-1 h-3 w-3" /> {t('adminUsers.disable')}</>
                          : <><ShieldCheck className="mr-1 h-3 w-3" /> {t('adminUsers.enable')}</>}
                      </Button>

                      <DeleteUserDialog target={u} onDone={load} />
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {/* Audit log — accessible from a workspace context */}
      {workspaceId && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{t('adminUsers.auditLogTitle')}</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-sm text-muted-foreground">
              {t('adminUsers.auditLogDesc', { workspaceId })}
            </p>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

/**
 * Create a platform account.
 *
 * <p>The password entered here is temporary: the account owner has to
 * replace it before they can use the account, so the credential the
 * administrator hands over never becomes the permanent one.
 */
function CreateUserDialog({
  roles,
  onCreated,
}: {
  roles: Role[];
  onCreated: () => Promise<void> | void;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [selectedRoles, setSelectedRoles] = useState<string[]>([]);

  function reset() {
    setEmail('');
    setDisplayName('');
    setPassword('');
    setConfirm('');
    setSelectedRoles([]);
    setError(null);
  }

  function toggleRole(code: string) {
    setSelectedRoles((prev) =>
      prev.includes(code) ? prev.filter((c) => c !== code) : [...prev, code],
    );
  }

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (password.length < 12) {
      setError(t('adminUsers.passwordTooShort'));
      return;
    }
    if (password !== confirm) {
      setError(t('adminUsers.passwordMismatch'));
      return;
    }
    if (selectedRoles.length === 0) {
      setError(t('adminUsers.needAtLeastOneRole'));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await adminApi.createUser({
        email: email.trim(),
        displayName: displayName.trim(),
        temporaryPassword: password,
        roles: selectedRoles,
      });
      await onCreated();
      setOpen(false);
      reset();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { setOpen(o); if (!o) reset(); }}>
      <DialogTrigger asChild>
        <Button size="sm" className="h-9">
          <UserPlus className="mr-1 h-4 w-4" /> {t('adminUsers.createUser')}
        </Button>
      </DialogTrigger>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{t('adminUsers.createUser')}</DialogTitle>
          <DialogDescription>{t('adminUsers.createUserDesc')}</DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="create-user-email">{t('adminUsers.email')}</Label>
            <Input
              id="create-user-email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              maxLength={255}
              placeholder={t('adminUsers.emailPlaceholder')}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="create-user-name">{t('adminUsers.displayName')}</Label>
            <Input
              id="create-user-name"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              required
              maxLength={120}
            />
          </div>
          <PasswordInput
            id="create-user-password"
            label={t('adminUsers.temporaryPassword')}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            minLength={12}
            maxLength={72}
            showStrength
          />
          <PasswordInput
            id="create-user-password-confirm"
            label={t('adminUsers.confirmPassword')}
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            required
            minLength={12}
            maxLength={72}
          />
          <div className="space-y-1.5">
            <Label>{t('adminUsers.roles')}</Label>
            <div className="max-h-40 space-y-1 overflow-y-auto rounded-md border p-2">
              {roles.map((r) => (
                <label
                  key={r.id}
                  className="flex items-center gap-2 rounded px-1 py-0.5 text-sm hover:bg-muted/40 cursor-pointer"
                >
                  <input
                    type="checkbox"
                    className="h-4 w-4 rounded border-border"
                    checked={selectedRoles.includes(r.code)}
                    onChange={() => toggleRole(r.code)}
                  />
                  <span className="font-medium">{r.code}</span>
                  <span className="text-xs text-muted-foreground">{r.name}</span>
                </label>
              ))}
            </div>
          </div>
          {error && <ErrorState message={error} />}
          <DialogFooter>
            <DialogClose asChild><Button variant="outline">{t('common.cancel')}</Button></DialogClose>
            <Button type="submit" disabled={busy}>
              {busy ? t('common.actions.saving') : t('adminUsers.createUser')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Replace a user's password with one the administrator chooses.
 *
 * <p>Used when a person lost access to their mailbox and the
 * self-service "forgot password" flow cannot help. The new credential
 * is still temporary — the owner has to replace it too.
 */
function ResetPasswordDialog({
  target,
  onDone,
}: {
  target: UserWithRoles;
  onDone: () => Promise<void> | void;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');

  function reset() {
    setPassword('');
    setConfirm('');
    setError(null);
  }

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (password.length < 12) {
      setError(t('adminUsers.passwordTooShort'));
      return;
    }
    if (password !== confirm) {
      setError(t('adminUsers.passwordMismatch'));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await adminApi.resetUserPassword(target.id, { newPassword: password });
      await onDone();
      setOpen(false);
      reset();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { setOpen(o); if (!o) reset(); }}>
      <DialogTrigger asChild>
        <Button size="sm" variant="outline" className="h-7 text-xs">
          <KeyRound className="mr-1 h-3 w-3" /> {t('adminUsers.resetPassword')}
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t('adminUsers.resetPassword')}</DialogTitle>
          <DialogDescription>
            {t('adminUsers.resetPasswordDesc', { email: target.email })}
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="space-y-4">
          <PasswordInput
            id={`reset-password-${target.id}`}
            label={t('adminUsers.newPassword')}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            minLength={12}
            maxLength={72}
            showStrength
          />
          <PasswordInput
            id={`reset-password-confirm-${target.id}`}
            label={t('adminUsers.confirmPassword')}
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            required
            minLength={12}
            maxLength={72}
          />
          {error && <ErrorState message={error} />}
          <DialogFooter>
            <DialogClose asChild><Button variant="outline">{t('common.cancel')}</Button></DialogClose>
            <Button type="submit" disabled={busy}>
              {busy ? t('common.actions.saving') : t('adminUsers.resetPassword')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Delete a platform account.
 *
 * <p>Destructive and irreversible, so it asks for the email address
 * back. The API refuses the deletion when the account still owns
 * content — the dialog surfaces that refusal rather than hiding it.
 */
function DeleteUserDialog({
  target,
  onDone,
}: {
  target: UserWithRoles;
  onDone: () => Promise<void> | void;
}) {
  const { t } = useTranslation();
  const { user } = useAuth();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmation, setConfirmation] = useState('');

  const isSelf = target.id === user?.id;
  const confirmed = confirmation.trim().toLowerCase() === target.email.toLowerCase();

  function reset() {
    setConfirmation('');
    setError(null);
  }

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!confirmed) return;
    setBusy(true);
    setError(null);
    try {
      await adminApi.deleteUser(target.id);
      await onDone();
      setOpen(false);
      reset();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={(o) => { setOpen(o); if (!o) reset(); }}>
      <DialogTrigger asChild>
        <Button
          size="sm"
          variant="outline"
          className="h-7 text-xs"
          disabled={isSelf}
          title={isSelf ? t('adminUsers.cannotDeleteSelf') : ''}
        >
          <Trash2 className="mr-1 h-3 w-3" /> {t('adminUsers.delete')}
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t('adminUsers.deleteUser')}</DialogTitle>
          <DialogDescription>
            {t('adminUsers.deleteUserDesc', { email: target.email })}
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor={`delete-confirm-${target.id}`}>
              {t('adminUsers.confirmDelete', { email: target.email })}
            </Label>
            <Input
              id={`delete-confirm-${target.id}`}
              type="email"
              value={confirmation}
              onChange={(e) => setConfirmation(e.target.value)}
              placeholder={target.email}
              autoComplete="off"
            />
          </div>
          {error && <ErrorState message={error} />}
          <DialogFooter>
            <DialogClose asChild><Button variant="outline">{t('common.cancel')}</Button></DialogClose>
            <Button type="submit" variant="destructive" disabled={busy || !confirmed}>
              {busy ? t('common.actions.saving') : t('adminUsers.deleteUser')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
