import { useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  ArrowLeft, ShieldCheck, ShieldOff, UserCog, Users,
} from 'lucide-react';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import {
  Card, CardContent, CardHeader, CardTitle,
} from '../components/ui/card';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import {
  Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger,
} from '../components/ui/dialog';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { useAuth } from '../contexts/AuthContext';
import { describeError } from '../services/auth';
import { adminApi } from '../services/adminApi';
import { roleName } from '../services/adminLabels';
import { Role, UserWithRoles } from '../types/admin';
import { format } from 'date-fns';

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
      {!workspaceId && (
        <Button asChild variant="ghost" size="sm">
          <Link to="/admin/users"><ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}</Link>
        </Button>
      )}
      {workspaceId && (
        <Button variant="ghost" size="sm" asChild>
          <Link to={`/workspaces/${workspaceId}`}>
            <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}
          </Link>
        </Button>
      )}

      <div>
        <h1 className="text-2xl font-bold tracking-tight">{t('adminUsers.title')}</h1>
        <p className="text-sm text-muted-foreground">
          {t('adminUsers.subtitle')}
        </p>
      </div>

      {/* Users table */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <Users className="h-4 w-4" /> {t('adminUsers.platformUsers')}
            <Badge variant="muted">{users.length}</Badge>
          </CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('adminUsers.colEmail')}</TableHead>
                <TableHead>{t('adminUsers.colDisplayName')}</TableHead>
                <TableHead>{t('adminUsers.colRoles')}</TableHead>
                <TableHead>{t('adminUsers.colStatus')}</TableHead>
                <TableHead>{t('adminUsers.colCreated')}</TableHead>
                <TableHead>{t('adminUsers.colActions')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {users.length === 0 && (
                <TableRow>
                  <TableCell colSpan={6} className="text-center text-sm text-muted-foreground">
                    {t('adminUsers.noUsers')}
                  </TableCell>
                </TableRow>
              )}
              {users.map((u) => (
                <TableRow key={u.id}>
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
                    <Badge variant={u.enabled ? 'success' : 'destructive'}>
                      {u.enabled ? t('adminUsers.active') : t('adminUsers.disabled')}
                    </Badge>
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
            <CardTitle className="text-base">Audit log</CardTitle>
          </CardHeader>
          <CardContent>
            <EmptyState
              title="Workspace audit log"
              description="The workspace-scoped audit log is still available at /workspaces/{id}/audit-logs."
            />
          </CardContent>
        </Card>
      )}
    </div>
  );
}