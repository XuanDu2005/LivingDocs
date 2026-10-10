import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  AlertTriangle, Check, Loader2, Save, ShieldAlert, ShieldCheck, X,
} from 'lucide-react';
import { Button } from './ui/button';
import { Badge } from './ui/badge';
import { Switch } from './ui/switch';
import { Input } from './ui/input';
import {
  Card, CardContent, CardDescription, CardHeader, CardTitle,
} from './ui/card';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from './ui/select';
import { LoadingState, ErrorState } from './ui/states';
import { adminApi } from '../services/adminApi';
import { describeError } from '../services/auth';
import { Role, RolePermissionRow } from '../types/admin';

interface Props {
  roles: Role[];
}

/**
 * Tabbed role-permission matrix.
 *
 * <p>Each role is rendered as a card with a column-style list of
 * permissions, grouped by category. Toggling a switch updates the
 * local dirty-state, and "Save" pushes the full granted set in one
 * PUT request so the server can audit a single change.
 */
export default function RolePermissionMatrix({ roles }: Props) {
  const { t } = useTranslation();
  const sortedRoles = useMemo(
    () => [...roles].sort((a, b) => a.displayOrder - b.displayOrder || a.name.localeCompare(b.name)),
    [roles],
  );
  const [activeRoleId, setActiveRoleId] = useState<string | null>(null);
  const [matrix, setMatrix] = useState<Record<string, RolePermissionRow[]>>({});
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState<boolean>(false);
  const [dirty, setDirty] = useState<boolean>(false);
  const [search, setSearch] = useState<string>('');
  const [categoryFilter, setCategoryFilter] = useState<string>('ALL');
  const [info, setInfo] = useState<string | null>(null);

  // Pick the first role on mount.
  useEffect(() => {
    if (activeRoleId === null && sortedRoles.length > 0) {
      setActiveRoleId(sortedRoles[0].id);
    }
  }, [activeRoleId, sortedRoles]);

  // Load the active role's matrix.
  useEffect(() => {
    if (!activeRoleId) return;
    if (matrix[activeRoleId]) return; // already cached
    let cancelled = false;
    setLoading(true);
    setError(null);
    adminApi
      .getRolePermissionMatrix(activeRoleId)
      .then((res) => {
        if (cancelled) return;
        setMatrix((prev) => ({ ...prev, [activeRoleId]: res.permissions }));
      })
      .catch((err) => {
        if (cancelled) return;
        setError(describeError(err));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [activeRoleId, matrix]);

  const rows = activeRoleId ? matrix[activeRoleId] ?? [] : [];
  const activeRole = sortedRoles.find((r) => r.id === activeRoleId) ?? null;

  const categories = useMemo(() => {
    const set = new Set<string>();
    for (const r of rows) set.add(r.category);
    return Array.from(set).sort();
  }, [rows]);

  const grouped = useMemo(() => {
    const q = search.trim().toLowerCase();
    const out: Record<string, RolePermissionRow[]> = {};
    for (const r of rows) {
      if (categoryFilter !== 'ALL' && r.category !== categoryFilter) continue;
      if (q.length > 0) {
        const haystack = `${r.code} ${r.name} ${r.description ?? ''}`.toLowerCase();
        if (!haystack.includes(q)) continue;
      }
      (out[r.category] ??= []).push(r);
    }
    return out;
  }, [rows, search, categoryFilter]);

  const grantedCount = useMemo(() => rows.filter((r) => r.granted).length, [rows]);
  const totalCount = rows.length;

  function toggleGranted(permissionId: string, next: boolean) {
    if (!activeRoleId || !activeRole) return;
    if (activeRole.code === 'ADMIN') {
      setInfo(t('adminRoles.matrix.adminLocked', 'ADMIN role permissions are pinned to all-granted to keep the platform manageable.'));
      return;
    }
    setInfo(null);
    setMatrix((prev) => {
      const current = prev[activeRoleId] ?? [];
      return {
        ...prev,
        [activeRoleId]: current.map((p) =>
          p.id === permissionId ? { ...p, granted: next } : p,
        ),
      };
    });
    setDirty(true);
  }

  async function save() {
    if (!activeRoleId) return;
    setSaving(true);
    setError(null);
    setInfo(null);
    try {
      const grantedIds = rows.filter((r) => r.granted).map((r) => r.id);
      const updated = await adminApi.updateRolePermissionMatrix(activeRoleId, {
        grantedPermissionIds: grantedIds,
      });
      setMatrix((prev) => ({ ...prev, [activeRoleId]: updated.permissions }));
      setDirty(false);
      setInfo(t('adminRoles.matrix.saved', 'Permissions updated.'));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSaving(false);
    }
  }

  async function resetDirty() {
    if (!activeRoleId) return;
    setMatrix((prev) => {
      const next = { ...prev };
      delete next[activeRoleId];
      return next;
    });
    setDirty(false);
    setInfo(null);
    setError(null);
  }

  if (sortedRoles.length === 0) {
    return <EmptyMessage message={t('adminRoles.matrix.empty', 'No roles yet.')} />;
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-col sm:flex-row gap-2 sm:items-center">
        <Select value={activeRoleId ?? ''} onValueChange={(v) => { setActiveRoleId(v); setDirty(false); setError(null); setInfo(null); }}>
          <SelectTrigger className="sm:w-72">
            <SelectValue placeholder={t('adminRoles.matrix.selectRole', 'Select a role')} />
          </SelectTrigger>
          <SelectContent>
            {sortedRoles.map((r) => (
              <SelectItem key={r.id} value={r.id}>
                {r.name} <span className="text-muted-foreground">({r.code})</span>
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder={t('adminRoles.matrix.searchPlaceholder', 'Search permissions…')}
          className="flex-1 min-w-[180px]"
        />
        <Select value={categoryFilter} onValueChange={setCategoryFilter}>
          <SelectTrigger className="sm:w-56">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">{t('adminRoles.matrix.allCategories', 'All categories')}</SelectItem>
            {categories.map((c) => (
              <SelectItem key={c} value={c}>{c}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {error && <ErrorState message={error} />}
      {info && (
        <div className="flex items-center gap-2 rounded-md border border-emerald-200 bg-emerald-50 dark:bg-emerald-950/20 px-3 py-2 text-sm text-emerald-700 dark:text-emerald-300">
          <Check className="h-4 w-4" /> {info}
        </div>
      )}

      {loading ? (
        <LoadingState message={t('adminRoles.matrix.loading', 'Loading permissions…')} />
      ) : (
        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2 flex-wrap">
              <ShieldCheck className="h-4 w-4" />
              {activeRole ? activeRole.name : t('adminRoles.matrix.selectRole')}
              {activeRole?.code && (
                <Badge variant="muted" className="font-mono text-[11px]">{activeRole.code}</Badge>
              )}
              {activeRole?.system && (
                <Badge variant="success" className="text-[10px]">{t('adminRoles.system', 'System')}</Badge>
              )}
              <Badge variant="muted">
                {grantedCount}/{totalCount}
              </Badge>
            </CardTitle>
            <CardDescription>
              {t('adminRoles.matrix.subtitle', 'Toggle the permissions this role holds. Changes are applied to all current and future role holders.')}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {activeRole?.code === 'ADMIN' && (
              <div className="flex items-start gap-2 rounded-md border border-amber-200 bg-amber-50 dark:bg-amber-950/20 px-3 py-2 text-sm text-amber-800 dark:text-amber-200">
                <AlertTriangle className="h-4 w-4 mt-0.5" />
                <span>
                  {t('adminRoles.matrix.adminNotice', 'The ADMIN role is permanently granted every permission so the platform always has at least one manageable role.')}
                </span>
              </div>
            )}

            {Object.keys(grouped).length === 0 && (
              <p className="text-sm text-muted-foreground text-center py-6">
                {t('adminRoles.matrix.noMatches', 'No permissions match the current filter.')}
              </p>
            )}

            {Object.entries(grouped).map(([cat, items]) => (
              <div key={cat} className="space-y-2">
                <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                  {cat}
                </div>
                <div className="divide-y rounded-md border">
                  {items.map((p) => (
                    <PermissionRow
                      key={p.id}
                      row={p}
                      locked={activeRole?.code === 'ADMIN'}
                      onToggle={(next) => toggleGranted(p.id, next)}
                    />
                  ))}
                </div>
              </div>
            ))}

            <div className="flex items-center gap-2 pt-2 border-t">
              <Button onClick={() => void save()} disabled={!dirty || saving || activeRole?.code === 'ADMIN'}>
                {saving
                  ? <><Loader2 className="mr-1 h-4 w-4 animate-spin" /> {t('adminRoles.matrix.saving', 'Saving…')}</>
                  : <><Save className="mr-1 h-4 w-4" /> {t('adminRoles.matrix.save', 'Save permissions')}</>}
              </Button>
              <Button variant="outline" onClick={() => void resetDirty()} disabled={!dirty || saving}>
                <X className="mr-1 h-4 w-4" /> {t('common.cancel', 'Cancel')}
              </Button>
              {dirty && (
                <span className="text-xs text-amber-600 dark:text-amber-300 flex items-center gap-1">
                  <ShieldAlert className="h-3 w-3" />
                  {t('adminRoles.matrix.unsaved', 'Unsaved changes')}
                </span>
              )}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

function PermissionRow({
  row, locked, onToggle,
}: {
  row: RolePermissionRow;
  locked: boolean;
  onToggle: (next: boolean) => void;
}) {
  const { t } = useTranslation();
  return (
    <div className="flex items-start gap-3 p-3 hover:bg-muted/30">
      <Switch
        checked={row.granted}
        disabled={locked}
        onCheckedChange={onToggle}
        aria-label={t('adminRoles.matrix.togglePermission', 'Toggle permission {{code}}', { code: row.code })}
      />
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-sm font-medium">{row.name}</span>
          <span className="font-mono text-[10px] px-1.5 py-0.5 rounded bg-muted text-muted-foreground">
            {row.code}
          </span>
        </div>
        {row.description && (
          <p className="text-xs text-muted-foreground mt-0.5">{row.description}</p>
        )}
      </div>
    </div>
  );
}

function EmptyMessage({ message }: { message: string }) {
  return (
    <div className="rounded-md border border-dashed p-6 text-center text-sm text-muted-foreground">
      {message}
    </div>
  );
}
