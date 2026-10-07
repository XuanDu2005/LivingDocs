import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, ExternalLink, RefreshCw, Search, Trash2 } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Input } from '../components/ui/input';
import {
  Card, CardContent, CardHeader, CardTitle, CardDescription,
} from '../components/ui/card';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { describeError } from '../services/auth';
import { adminApi } from '../services/adminApi';
import { AdminWorkspace } from '../types/admin';
import { format, parseISO } from 'date-fns';

/**
 * Platform-wide workspace catalogue. Unlike the per-user
 * {@code /workspaces} page (which is scoped to the caller's memberships),
 * this view is reserved for administrators and bypasses the owner check
 * when removing a tenant.
 */
export default function AdminWorkspacesPage() {
  const { t } = useTranslation();

  const [items, setItems] = useState<AdminWorkspace[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<string>('');
  const [busyId, setBusyId] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      setItems(await adminApi.listAllWorkspaces());
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  const filtered = useMemo(() => {
    const q = filter.trim().toLowerCase();
    if (!q) return items;
    return items.filter((w) =>
      [w.name, w.slug, w.description, w.ownerId]
        .filter(Boolean)
        .some((v) => String(v).toLowerCase().includes(q)),
    );
  }, [items, filter]);

  async function onDelete(w: AdminWorkspace) {
    if (
      !window.confirm(
        t('adminWorkspaces.confirmDelete', { name: w.name }),
      )
    ) {
      return;
    }
    setBusyId(w.id);
    try {
      await adminApi.deleteWorkspace(w.id);
      setItems((prev) => prev.filter((x) => x.id !== w.id));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusyId(null);
    }
  }

  if (loading) return <LoadingState message={t('adminWorkspaces.loading')} />;
  if (error) return <ErrorState message={error} />;

  return (
    <div className="space-y-6">
      <div>
        <Button variant="ghost" size="sm" asChild>
          <Link to="/admin">
            <ArrowLeft className="mr-1 h-4 w-4" />
            {t('adminWorkspaces.backToAdmin')}
          </Link>
        </Button>
        <h1 className="mt-2 text-2xl font-bold tracking-tight">
          {t('adminWorkspaces.title')}
        </h1>
        <p className="text-sm text-muted-foreground">
          {t('adminWorkspaces.subtitle')}
        </p>
      </div>

      <Card>
        <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-3 space-y-0">
          <div>
            <CardTitle className="flex items-center gap-2 text-base">
              {t('adminWorkspaces.catalogue')}
              <Badge variant="muted">{filtered.length}</Badge>
            </CardTitle>
            <CardDescription>{t('adminWorkspaces.catalogueDesc')}</CardDescription>
          </div>
          <div className="flex items-center gap-2">
            <div className="relative">
              <Search className="absolute left-2 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <Input
                value={filter}
                onChange={(e) => setFilter(e.target.value)}
                placeholder={t('adminWorkspaces.searchPlaceholder')}
                className="w-64 pl-8"
              />
            </div>
            <Button variant="outline" size="sm" onClick={() => void load()}>
              <RefreshCw className="mr-1 h-4 w-4" />
              {t('adminWorkspaces.refresh')}
            </Button>
          </div>
        </CardHeader>
        <CardContent className="p-0">
          {filtered.length === 0 ? (
            <div className="px-6 py-10">
              <EmptyState
                title={t('adminWorkspaces.emptyTitle')}
                description={t('adminWorkspaces.emptyDesc')}
              />
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('adminWorkspaces.colName')}</TableHead>
                  <TableHead>{t('adminWorkspaces.colOwner')}</TableHead>
                  <TableHead className="text-right">
                    {t('adminWorkspaces.colMembers')}
                  </TableHead>
                  <TableHead className="text-right">
                    {t('adminWorkspaces.colManagers')}
                  </TableHead>
                  <TableHead>{t('adminWorkspaces.colCreated')}</TableHead>
                  <TableHead className="w-[140px] text-right">
                    {t('adminWorkspaces.colActions')}
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((w) => (
                  <TableRow key={w.id}>
                    <TableCell>
                      <div className="font-medium">{w.name}</div>
                      <div className="text-xs text-muted-foreground">
                        <code className="mr-1">{w.slug}</code>
                        {w.description && <span>· {w.description}</span>}
                      </div>
                    </TableCell>
                    <TableCell className="font-mono text-xs">
                      {w.ownerId}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {w.memberCount}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {w.managerCount}
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground whitespace-nowrap">
                      {format(parseISO(w.createdAt), 'MMM d, yyyy')}
                    </TableCell>
                    <TableCell>
                      <div className="flex justify-end gap-1">
                        <Button size="sm" variant="ghost" asChild>
                          <Link to={`/workspaces/${w.id}`}>
                            <ExternalLink className="mr-1 h-4 w-4" />
                            {t('adminWorkspaces.open')}
                          </Link>
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          className="text-destructive hover:text-destructive"
                          onClick={() => void onDelete(w)}
                          disabled={busyId === w.id}
                        >
                          <Trash2 className="mr-1 h-4 w-4" />
                          {t('adminWorkspaces.delete')}
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}