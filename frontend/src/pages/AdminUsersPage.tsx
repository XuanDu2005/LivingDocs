import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Clock, UserCog, Users } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Label } from '../components/ui/label';
import {
  Card, CardContent, CardHeader, CardTitle,
} from '../components/ui/card';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import {
  Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger,
} from '../components/ui/dialog';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '../components/ui/select';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { useAuth } from '../contexts/AuthContext';
import apiClient from '../services/api';
import { auditApi, AuditLog } from '../services/notifications';
import { describeError } from '../services/auth';
import { format } from 'date-fns';

interface AdminUser {
  id: string;
  email: string;
  displayName: string | null;
  enabled: boolean;
  createdAt: string | null;
  role?: string;
}

export default function AdminUsersPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { user } = useAuth();
  const navigate = useNavigate();

  const [users, setUsers] = useState<AdminUser[]>([]);
  const [auditLogs, setAuditLogs] = useState<AuditLog[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const [editUserId, setEditUserId] = useState<string | null>(null);
  const [editRole, setEditRole] = useState<string>('MEMBER');

  useEffect(() => {
    if (!user) {
      navigate('/login', { replace: true });
      return;
    }
    void load();
  }, [user, navigate]);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const [userList, audit] = await Promise.all([
        apiClient.get<AdminUser[]>('/admin/users').then((r) => r.data),
        workspaceId ? auditApi.list(workspaceId) : Promise.resolve([] as AuditLog[]),
      ]);
      setUsers(userList);
      setAuditLogs(audit);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  async function updateRole(userId: string, role: string) {
    setBusy(userId);
    try {
      await apiClient.put(`/admin/users/${userId}/role`, { role });
      await load();
      setEditUserId(null);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(null);
    }
  }

  async function toggleEnabled(user: AdminUser) {
    setBusy(user.id);
    try {
      await apiClient.put(`/admin/users/${user.id}`, { enabled: !user.enabled });
      await load();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(null);
    }
  }

  if (!workspaceId) return <EmptyState title="Missing workspace id" />;
  if (loading) return <LoadingState message="Loading users…" />;
  if (error) return <ErrorState message={error} />;

  return (
    <div className="space-y-6">
      <Button variant="ghost" size="sm" asChild>
        <Link to={`/workspaces/${workspaceId}`}>
          <ArrowLeft className="mr-1 h-4 w-4" /> Back to workspace
        </Link>
      </Button>

      <div>
        <h1 className="text-2xl font-bold tracking-tight">Admin · Users</h1>
        <p className="text-sm text-muted-foreground">
          Manage platform users and their roles.
        </p>
      </div>

      {/* Users table */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <Users className="h-4 w-4" /> Platform users
            <Badge variant="muted">{users.length}</Badge>
          </CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Email</TableHead>
                <TableHead>Display name</TableHead>
                <TableHead>Enabled</TableHead>
                <TableHead>Created</TableHead>
                <TableHead>Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {users.map((u) => (
                <TableRow key={u.id}>
                  <TableCell className="font-medium">{u.email}</TableCell>
                  <TableCell>{u.displayName ?? '—'}</TableCell>
                  <TableCell>
                    <Badge variant={u.enabled ? 'success' : 'destructive'}>
                      {u.enabled ? 'Active' : 'Disabled'}
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
                            setEditRole((u.role as string) ?? 'MEMBER');
                          } else {
                            setEditUserId(null);
                          }
                        }}
                      >
                        <DialogTrigger asChild>
                          <Button size="sm" variant="outline" className="h-7 text-xs">
                            <UserCog className="mr-1 h-3 w-3" /> Role
                          </Button>
                        </DialogTrigger>
                        <DialogContent>
                          <DialogHeader>
                            <DialogTitle>Edit user role</DialogTitle>
                            <DialogDescription>{u.email}</DialogDescription>
                          </DialogHeader>
                          <div className="space-y-3 py-2">
                            <div className="space-y-1.5">
                              <Label htmlFor={`role-${u.id}`}>Role</Label>
                              <Select value={editRole} onValueChange={setEditRole}>
                                <SelectTrigger id={`role-${u.id}`}>
                                  <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                  <SelectItem value="MEMBER">MEMBER</SelectItem>
                                  <SelectItem value="MANAGER">MANAGER</SelectItem>
                                  <SelectItem value="ADMIN">ADMIN</SelectItem>
                                </SelectContent>
                              </Select>
                            </div>
                          </div>
                          <DialogFooter>
                            <DialogClose asChild><Button variant="outline">Cancel</Button></DialogClose>
                            <Button onClick={() => void updateRole(u.id, editRole)} disabled={busy === u.id}>
                              Save role
                            </Button>
                          </DialogFooter>
                        </DialogContent>
                      </Dialog>

                      <Button
                        size="sm"
                        variant={u.enabled ? 'outline' : 'default'}
                        className="h-7 text-xs"
                        onClick={() => void toggleEnabled(u)}
                        disabled={busy === u.id}
                      >
                        {u.enabled ? 'Disable' : 'Enable'}
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {/* Audit log */}
      {workspaceId && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2">
              <Clock className="h-4 w-4" /> Audit log
            </CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            {auditLogs.length === 0 ? (
              <div className="p-6">
                <EmptyState title="No audit events" description="Recent administrative actions will appear here." />
              </div>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Action</TableHead>
                    <TableHead>Actor</TableHead>
                    <TableHead>Resource</TableHead>
                    <TableHead>Time</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {auditLogs.slice(0, 50).map((log) => (
                    <TableRow key={log.id}>
                      <TableCell>
                        <Badge variant="muted" className="text-xs">{log.action}</Badge>
                      </TableCell>
                      <TableCell className="text-xs">
                        {log.actorRole ?? 'system'}
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground">
                        {log.resourceType} {log.resourceId ? `#${log.resourceId}` : ''}
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground whitespace-nowrap">
                        {format(new Date(log.createdAt), 'MMM d, HH:mm:ss')}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  );
}
