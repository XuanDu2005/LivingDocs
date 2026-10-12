import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  AlertTriangle, ExternalLink, GitBranch, Loader2,
  RefreshCw, Search, Trash2,
} from 'lucide-react';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Input } from '../components/ui/input';
import {
  Card, CardContent, CardDescription, CardHeader, CardTitle,
} from '../components/ui/card';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '../components/ui/select';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import {
  Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter,
  DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { describeError } from '../services/auth';
import { adminApi } from '../services/adminApi';
import { AdminRepository, IndexJobStatus, RepositorySyncStatus } from '../types/admin';
import { format, parseISO } from 'date-fns';

const STATUS_LABEL: Record<RepositorySyncStatus, string> = {
  CONNECTED: 'adminRepos.statusConnected',
  ARCHIVED: 'adminRepos.statusArchived',
  ERROR: 'adminRepos.statusError',
  DISCONNECTED: 'adminRepos.statusDisconnected',
};

const STATUS_VARIANT: Record<RepositorySyncStatus, 'success' | 'muted' | 'destructive' | 'warning'> = {
  CONNECTED: 'success',
  ARCHIVED: 'muted',
  ERROR: 'destructive',
  DISCONNECTED: 'warning',
};

const JOB_LABEL: Record<IndexJobStatus, string> = {
  PENDING: 'adminRepos.jobPending',
  RUNNING: 'adminRepos.jobRunning',
  COMPLETED: 'adminRepos.jobCompleted',
  FAILED: 'adminRepos.jobFailed',
  CANCELLED: 'adminRepos.jobCancelled',
};

const JOB_VARIANT: Record<IndexJobStatus, 'success' | 'muted' | 'destructive' | 'warning' | 'info'> = {
  PENDING: 'muted',
  RUNNING: 'info',
  COMPLETED: 'success',
  FAILED: 'destructive',
  CANCELLED: 'warning',
};

export default function AdminRepositoriesPage() {
  const { t } = useTranslation();
  const [items, setItems] = useState<AdminRepository[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState<string>('');
  const [statusFilter, setStatusFilter] = useState<'ALL' | RepositorySyncStatus>('ALL');
  const [busyId, setBusyId] = useState<string | null>(null);
  const [unlinkTarget, setUnlinkTarget] = useState<AdminRepository | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await adminApi.listAllRepositories();
      setItems(data);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return items.filter((r) => {
      if (statusFilter !== 'ALL' && r.status !== statusFilter) return false;
      if (q.length === 0) return true;
      const hay = `${r.fullName} ${r.owner}/${r.name} ${r.workspaceName} ${r.workspaceSlug} ${r.description ?? ''}`.toLowerCase();
      return hay.includes(q);
    });
  }, [items, search, statusFilter]);

  const stats = useMemo(() => {
    const connected = items.filter((r) => r.status === 'CONNECTED').length;
    const errored = items.filter((r) => r.status === 'ERROR').length;
    const totalOpenDrift = items.reduce((s, r) => s + r.openDriftCount, 0);
    const totalDocs = items.reduce((s, r) => s + r.documentCount, 0);
    return { connected, errored, totalOpenDrift, totalDocs, total: items.length };
  }, [items]);

  async function handleReindex(repo: AdminRepository) {
    setBusyId(repo.id);
    setError(null);
    setInfo(null);
    try {
      const result = await adminApi.forceReindexRepository(repo.id);
      setInfo(t('adminRepos.reindexEnqueued', 'Reindex job {{jobId}} enqueued for {{repo}}.', {
        jobId: result.jobId.slice(0, 8),
        repo: repo.fullName,
      }));
      // Reload to update "last indexed" column.
      void load();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusyId(null);
    }
  }

  async function confirmUnlink() {
    if (!unlinkTarget) return;
    setBusyId(unlinkTarget.id);
    setError(null);
    setInfo(null);
    try {
      await adminApi.unlinkRepository(unlinkTarget.id);
      setInfo(t('adminRepos.unlinked', 'Unlinked {{repo}} from {{workspace}}.', {
        repo: unlinkTarget.fullName,
        workspace: unlinkTarget.workspaceName,
      }));
      setUnlinkTarget(null);
      void load();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">
          {t('adminRepos.title', 'Admin · Repositories')}
        </h1>
        <p className="text-sm text-muted-foreground">
          {t('adminRepos.subtitle', 'Every repository connected to a workspace on the platform. Use this view to investigate cross-tenant issues, force a reindex, or unlink a stale integration.')}
        </p>
      </div>

      {error && <ErrorState message={error} />}
      {info && (
        <div className="flex items-center gap-2 rounded-md border border-emerald-200 bg-emerald-50 dark:bg-emerald-950/20 px-3 py-2 text-sm text-emerald-700 dark:text-emerald-300">
          <RefreshCw className="h-4 w-4" /> {info}
        </div>
      )}

      <div className="grid gap-3 md:grid-cols-4">
        <Stat label={t('adminRepos.statTotal', 'Total')} value={stats.total} />
        <Stat label={t('adminRepos.statConnected', 'Connected')} value={stats.connected} variant="success" />
        <Stat label={t('adminRepos.statErrored', 'Errored')} value={stats.errored} variant={stats.errored > 0 ? 'destructive' : 'muted'} />
        <Stat label={t('adminRepos.statOpenDrift', 'Open drift alerts')} value={stats.totalOpenDrift} variant={stats.totalOpenDrift > 0 ? 'warning' : 'muted'} />
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <GitBranch className="h-4 w-4" /> {t('adminRepos.listTitle', 'Connected repositories')}
            <Badge variant="muted">{filtered.length}</Badge>
          </CardTitle>
          <CardDescription>
            {t('adminRepos.listDesc', 'Sorted by most-recent activity. Drift counts are scoped to this repository.')}
          </CardDescription>
          <div className="flex flex-col sm:flex-row gap-2 pt-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
              <Input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder={t('adminRepos.searchPlaceholder', 'Search by repo, owner, or workspace…')}
                className="pl-8"
              />
            </div>
            <Select value={statusFilter} onValueChange={(v) => setStatusFilter(v as RepositorySyncStatus | 'ALL')}>
              <SelectTrigger className="sm:w-52">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">{t('adminRepos.filterAll', 'All statuses')}</SelectItem>
                {Object.keys(STATUS_LABEL).map((s) => (
                  <SelectItem key={s} value={s}>{t(STATUS_LABEL[s as RepositorySyncStatus])}</SelectItem>
                ))}
              </SelectContent>
            </Select>
            <Button variant="outline" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={`h-4 w-4 mr-1 ${loading ? 'animate-spin' : ''}`} />
              {t('common.refresh', 'Refresh')}
            </Button>
          </div>
        </CardHeader>
        <CardContent className="p-0">
          {loading ? (
            <LoadingState message={t('adminRepos.loading', 'Loading repositories…')} />
          ) : filtered.length === 0 ? (
            <EmptyState
              title={t('adminRepos.emptyTitle', 'No repositories match.')}
              description={t('adminRepos.emptyDesc', 'Adjust the filters or wait for a workspace to connect a repository.')}
            />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('adminRepos.colRepo', 'Repository')}</TableHead>
                  <TableHead>{t('adminRepos.colWorkspace', 'Workspace')}</TableHead>
                  <TableHead>{t('adminRepos.colStatus', 'Status')}</TableHead>
                  <TableHead className="text-right">{t('adminRepos.colDocs', 'Docs')}</TableHead>
                  <TableHead className="text-right">{t('adminRepos.colDrift', 'Open drift')}</TableHead>
                  <TableHead>{t('adminRepos.colSync', 'Last synced')}</TableHead>
                  <TableHead>{t('adminRepos.colIndex', 'Last index')}</TableHead>
                  <TableHead className="text-right">{t('adminRepos.colActions', 'Actions')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((r) => (
                  <TableRow key={r.id}>
                    <TableCell>
                      <div className="flex flex-col">
                        <div className="flex items-center gap-2">
                          <span className="font-mono text-xs text-muted-foreground">{r.owner}/</span>
                          <span className="font-medium">{r.name}</span>
                          {r.isPrivate && <Badge variant="muted" className="text-[10px]">private</Badge>}
                        </div>
                        {r.description && (
                          <span className="text-xs text-muted-foreground line-clamp-1 max-w-xs">
                            {r.description}
                          </span>
                        )}
                        {r.htmlUrl && (
                          <a
                            href={r.htmlUrl}
                            target="_blank"
                            rel="noreferrer"
                            className="text-xs text-muted-foreground hover:underline inline-flex items-center gap-1"
                          >
                            <ExternalLink className="h-3 w-3" />
                            {r.fullName}
                          </a>
                        )}
                      </div>
                    </TableCell>
                    <TableCell>
                      <Link
                        to={`/workspaces/${r.workspaceId}`}
                        className="text-sm hover:underline"
                      >
                        {r.workspaceName}
                      </Link>
                      <div className="text-xs text-muted-foreground font-mono">{r.workspaceSlug}</div>
                    </TableCell>
                    <TableCell>
                      <Badge variant={STATUS_VARIANT[r.status]} className="text-[10px]">
                        {t(STATUS_LABEL[r.status])}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{r.documentCount}</TableCell>
                    <TableCell className="text-right">
                      {r.openDriftCount > 0 ? (
                        <Badge variant="warning" className="text-[10px]">
                          <AlertTriangle className="h-3 w-3 mr-1" />
                          {r.openDriftCount}
                        </Badge>
                      ) : (
                        <span className="text-xs text-muted-foreground">—</span>
                      )}
                      {r.totalDriftCount > 0 && r.openDriftCount !== r.totalDriftCount && (
                        <div className="text-[10px] text-muted-foreground">
                          / {r.totalDriftCount}
                        </div>
                      )}
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground whitespace-nowrap">
                      {r.lastSyncedAt ? format(parseISO(r.lastSyncedAt), 'MMM d, HH:mm') : '—'}
                    </TableCell>
                    <TableCell>
                      {r.lastIndexJobStatus ? (
                        <Badge variant={JOB_VARIANT[r.lastIndexJobStatus]} className="text-[10px]">
                          {t(JOB_LABEL[r.lastIndexJobStatus])}
                        </Badge>
                      ) : (
                        <span className="text-xs text-muted-foreground">—</span>
                      )}
                      {r.lastIndexJobAt && (
                        <div className="text-[10px] text-muted-foreground">
                          {format(parseISO(r.lastIndexJobAt), 'MMM d, HH:mm')}
                        </div>
                      )}
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center justify-end gap-1">
                        <Button
                          size="icon"
                          variant="ghost"
                          title={t('adminRepos.actionReindex', 'Force reindex')}
                          onClick={() => void handleReindex(r)}
                          disabled={busyId === r.id}
                        >
                          {busyId === r.id
                            ? <Loader2 className="h-4 w-4 animate-spin" />
                            : <RefreshCw className="h-4 w-4" />}
                        </Button>
                        <Button
                          size="icon"
                          variant="ghost"
                          title={t('adminRepos.actionUnlink', 'Unlink from workspace')}
                          onClick={() => setUnlinkTarget(r)}
                          disabled={busyId === r.id}
                          className="text-destructive hover:text-destructive"
                        >
                          <Trash2 className="h-4 w-4" />
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

      <Dialog open={!!unlinkTarget} onOpenChange={(o) => !o && setUnlinkTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('adminRepos.unlinkTitle', 'Unlink repository?')}</DialogTitle>
            <DialogDescription>
              {t('adminRepos.unlinkDesc', 'This disconnects {{repo}} from {{workspace}} and removes the sync link. The repository on GitHub is not affected. This action is irreversible from this screen.', {
                repo: unlinkTarget?.fullName ?? '',
                workspace: unlinkTarget?.workspaceName ?? '',
              })}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <DialogClose asChild>
              <Button variant="outline" disabled={busyId === unlinkTarget?.id}>
                {t('common.cancel', 'Cancel')}
              </Button>
            </DialogClose>
            <Button
              variant="destructive"
              onClick={() => void confirmUnlink()}
              disabled={busyId === unlinkTarget?.id}
            >
              {busyId === unlinkTarget?.id
                ? <><Loader2 className="mr-1 h-4 w-4 animate-spin" /> {t('adminRepos.unlinking', 'Unlinking…')}</>
                : <><Trash2 className="mr-1 h-4 w-4" /> {t('adminRepos.confirmUnlink', 'Unlink')}</>}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function Stat({ label, value, variant = 'muted' }: { label: string; value: number; variant?: 'muted' | 'success' | 'destructive' | 'warning' }) {
  const tone =
    variant === 'success' ? 'text-emerald-600 dark:text-emerald-300'
    : variant === 'destructive' ? 'text-red-600 dark:text-red-300'
    : variant === 'warning' ? 'text-amber-600 dark:text-amber-300'
    : 'text-foreground';
  return (
    <Card>
      <CardContent className="p-4">
        <div className="text-xs uppercase tracking-wide text-muted-foreground">{label}</div>
        <div className={`text-2xl font-semibold tabular-nums ${tone}`}>{value}</div>
      </CardContent>
    </Card>
  );
}
