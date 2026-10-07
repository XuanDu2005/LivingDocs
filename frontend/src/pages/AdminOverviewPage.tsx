import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  Activity,
  AlertTriangle,
  Database,
  FileText,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  Users,
} from 'lucide-react';
import {
  Card, CardContent, CardDescription, CardHeader, CardTitle,
} from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { LoadingState, ErrorState } from '../components/ui/states';
import { adminApi } from '../services/adminApi';
import { roleDescription, roleName } from '../services/adminLabels';
import { Role, UserWithRoles } from '../types/admin';
import { describeError } from '../services/auth';

/**
 * Landing page for the admin area.
 *
 * <p>Pulls together a platform snapshot: total / enabled users, role
 * distribution, and recent user activity. We deliberately reuse the
 * existing role + users-with-roles endpoints rather than introducing a
 * dedicated "stats" endpoint — the data is already there and avoids
 * another round-trip on first paint.
 */
export default function AdminOverviewPage() {
  const { t } = useTranslation();
  const [users, setUsers] = useState<UserWithRoles[]>([]);
  const [roles, setRoles] = useState<Role[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void load();
  }, []);

  async function load() {
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
  }

  const stats = useMemo(() => {
    const total = users.length;
    const enabled = users.filter((u) => u.enabled).length;
    const disabled = total - enabled;
    const recentlyCreated = users
      .filter((u): u is UserWithRoles & { createdAt: string } => u.createdAt != null)
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
      .slice(0, 5);
    // Most-used role across the platform. We deliberately skip system
    // roles here so the ranking reflects only roles an admin
    // actually assigned.
    const usage = new Map<string, number>();
    for (const u of users) {
      for (const r of u.roles) {
        usage.set(r, (usage.get(r) ?? 0) + 1);
      }
    }
    const topRoles = Array.from(usage.entries())
      .map(([code, count]) => ({ code, count }))
      .sort((a, b) => b.count - a.count)
      .slice(0, 4);
    return { total, enabled, disabled, recentlyCreated, topRoles };
  }, [users]);

  const recentRoles = useMemo(
    () => [...roles].sort((a, b) => b.displayOrder - a.displayOrder).slice(0, 5),
    [roles],
  );

  if (loading) return <LoadingState message={t('adminOverview.loading')} />;
  if (error) return <ErrorState message={error} />;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">{t('adminOverview.title')}</h1>
        <p className="text-sm text-muted-foreground">
          {t('adminOverview.subtitle')}
        </p>
      </div>

      {/* Stat cards */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          icon={<Users className="h-5 w-5 text-primary" />}
          value={String(stats.total)}
          label={t('adminOverview.totalUsers')}
          hint={t('adminOverview.totalUsersHint')}
          to="/admin/users"
        />
        <StatCard
          icon={<ShieldCheck className="h-5 w-5 text-green-500" />}
          value={String(stats.enabled)}
          label={t('adminOverview.activeUsers')}
          hint={t('adminOverview.activeUsersHint')}
          to="/admin/users"
        />
        <StatCard
          icon={<AlertTriangle className="h-5 w-5 text-orange-500" />}
          value={String(stats.disabled)}
          label={t('adminOverview.disabledUsers')}
          hint={t('adminOverview.disabledUsersHint')}
          to="/admin/users"
        />
        <StatCard
          icon={<ShieldAlert className="h-5 w-5 text-blue-500" />}
          value={String(roles.length)}
          label={t('adminOverview.totalRoles')}
          hint={t('adminOverview.totalRolesHint')}
          to="/admin/roles"
        />
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        {/* Top roles */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2">
              <ShieldCheck className="h-4 w-4" /> {t('adminOverview.topRoles')}
            </CardTitle>
            <CardDescription>{t('adminOverview.topRolesDesc')}</CardDescription>
          </CardHeader>
          <CardContent>
            {stats.topRoles.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t('adminOverview.noRolesAssigned')}</p>
            ) : (
              <ul className="space-y-2">
                {stats.topRoles.map((r) => {
                  const meta = roles.find((x) => x.code === r.code);
                  return (
                    <li key={r.code} className="flex items-center justify-between rounded border bg-muted/30 px-3 py-2">
                      <div>
                        <div className="text-sm font-medium">{roleName(r.code, meta?.name ?? r.code, t)}</div>
                        <div className="text-xs text-muted-foreground">
                          <code>{r.code}</code>
                          {meta?.system && <span className="ml-2">· system</span>}
                        </div>
                      </div>
                      <Badge variant="info">{r.count}</Badge>
                    </li>
                  );
                })}
              </ul>
            )}
          </CardContent>
        </Card>

        {/* Recent users */}
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2">
              <Activity className="h-4 w-4" /> {t('adminOverview.recentUsers')}
            </CardTitle>
            <CardDescription>{t('adminOverview.recentUsersDesc')}</CardDescription>
          </CardHeader>
          <CardContent>
            {stats.recentlyCreated.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t('adminOverview.noUsers')}</p>
            ) : (
              <ul className="space-y-2">
                {stats.recentlyCreated.map((u) => (
                  <li key={u.id} className="flex items-center justify-between rounded border bg-muted/30 px-3 py-2">
                    <div>
                      <div className="text-sm font-medium">{u.email}</div>
                      <div className="text-xs text-muted-foreground">
                        {u.displayName ?? '—'} · {new Date(u.createdAt as string).toLocaleString()}
                      </div>
                    </div>
                    <div className="flex flex-wrap items-center gap-1">
                      {u.roles.slice(0, 3).map((r) => (
                        <Badge key={r} variant="muted" className="text-[10px]">{r}</Badge>
                      ))}
                      {u.roles.length > 3 && (
                        <Badge variant="muted" className="text-[10px]">+{u.roles.length - 3}</Badge>
                      )}
                      <Badge variant={u.enabled ? 'success' : 'destructive'} className="text-[10px]">
                        {u.enabled ? t('adminUsers.active') : t('adminUsers.disabled')}
                      </Badge>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Quick actions */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t('adminOverview.quickActions')}</CardTitle>
          <CardDescription>{t('adminOverview.quickActionsDesc')}</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <QuickAction
              to="/admin/users"
              icon={<Users className="h-4 w-4" />}
              title={t('adminOverview.actionUsersTitle')}
              desc={t('adminOverview.actionUsersDesc')}
            />
            <QuickAction
              to="/admin/roles"
              icon={<ShieldCheck className="h-4 w-4" />}
              title={t('adminOverview.actionRolesTitle')}
              desc={t('adminOverview.actionRolesDesc')}
            />
            <QuickAction
              to="/admin/ai-settings"
              icon={<Sparkles className="h-4 w-4" />}
              title={t('adminOverview.actionAiTitle')}
              desc={t('adminOverview.actionAiDesc')}
            />
            <QuickAction
              to="/admin/audit-retention"
              icon={<Database className="h-4 w-4" />}
              title={t('adminOverview.actionAuditTitle')}
              desc={t('adminOverview.actionAuditDesc')}
            />
          </div>
        </CardContent>
      </Card>

      {/* Recent roles + system flag */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <FileText className="h-4 w-4" /> {t('adminOverview.roleSnapshot')}
          </CardTitle>
          <CardDescription>{t('adminOverview.roleSnapshotDesc')}</CardDescription>
        </CardHeader>
        <CardContent>
          {recentRoles.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t('adminOverview.noRoles')}</p>
          ) : (
            <ul className="space-y-2">
              {recentRoles.map((r) => (
                <li key={r.id} className="flex items-center justify-between rounded border bg-muted/30 px-3 py-2">
                  <div>
                    <div className="text-sm font-medium">
                      {roleName(r.code, r.name, t)}{' '}
                      <span className="text-xs text-muted-foreground font-mono">({r.code})</span>
                    </div>
                    <div className="text-xs text-muted-foreground line-clamp-1">
                      {roleDescription(r.code, r.description, t)}
                    </div>
                  </div>
                  <Badge variant={r.system ? 'success' : 'muted'} className="text-[10px]">
                    {r.system ? t('adminRoles.system') : t('adminRoles.custom')}
                  </Badge>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function StatCard({
  icon, value, label, hint, to,
}: {
  icon: React.ReactNode;
  value: string;
  label: string;
  hint: string;
  to: string;
}) {
  return (
    <Card className="hover:shadow-md transition-shadow">
      <CardContent className="pt-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="text-2xl font-bold tracking-tight">{value}</div>
            <div className="text-xs text-muted-foreground mt-1">{label}</div>
            <p className="mt-2 text-xs text-muted-foreground">{hint}</p>
          </div>
          <div className="rounded-md bg-muted/40 p-2">{icon}</div>
        </div>
        <Link to={to} className="mt-3 inline-block text-xs text-primary hover:underline">
          →
        </Link>
      </CardContent>
    </Card>
  );
}

function QuickAction({
  to, icon, title, desc,
}: {
  to: string;
  icon: React.ReactNode;
  title: string;
  desc: string;
}) {
  return (
    <Link
      to={to}
      className="group rounded-md border bg-card p-4 transition-colors hover:border-primary hover:bg-muted/40"
    >
      <div className="flex items-center gap-2 text-sm font-medium">
        <span className="rounded-md bg-primary/10 p-1 text-primary">{icon}</span>
        {title}
      </div>
      <p className="mt-1 text-xs text-muted-foreground">{desc}</p>
      <p className="mt-2 text-xs text-primary opacity-0 transition-opacity group-hover:opacity-100">
        →
      </p>
    </Link>
  );
}