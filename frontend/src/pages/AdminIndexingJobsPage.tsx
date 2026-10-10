import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, Database, RefreshCw, X, Loader2, RotateCw } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import {
  Card, CardContent, CardHeader, CardTitle,
} from '../components/ui/card';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '../components/ui/select';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { describeError } from '../services/auth';
import apiClient from '../services/api';
import { format } from 'date-fns';

type JobStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
type JobKind = 'INDEX' | 'REINDEX_ALL';

const STATUS_KEY: Record<JobStatus, string> = {
  PENDING: 'adminIndexingJobs.statusPending',
  RUNNING: 'adminIndexingJobs.statusRunning',
  COMPLETED: 'adminIndexingJobs.statusCompleted',
  FAILED: 'adminIndexingJobs.statusFailed',
  CANCELLED: 'adminIndexingJobs.statusCancelled',
};

interface IndexJob {
  id: string;
  workspaceId: string;
  documentId: string | null;
  status: JobStatus;
  kind: JobKind;
  totalTargets: number;
  processedTargets: number;
  chunksIndexed: number;
  failedTargets: number;
  errorMessage: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

interface JobListResponse {
  items: IndexJob[];
  page: number;
  size: number;
  total: number;
}

const STATUS_VARIANT: Record<JobStatus, 'default' | 'info' | 'success' | 'warning' | 'destructive' | 'muted'> = {
  PENDING: 'muted',
  RUNNING: 'info',
  COMPLETED: 'success',
  FAILED: 'destructive',
  CANCELLED: 'muted',
};

export default function AdminIndexingJobsPage() {
  const { t } = useTranslation();
  const [jobs, setJobs] = useState<IndexJob[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<'ALL' | JobStatus>('ALL');
  const [busy, setBusy] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const { data } = await apiClient.get<JobListResponse>('/admin/indexing-jobs', {
        params: { page: 0, size: 100 },
      });
      setJobs(data.items);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  // Auto-refresh while any job is PENDING or RUNNING
  useEffect(() => {
    const hasActive = jobs.some((j) => j.status === 'PENDING' || j.status === 'RUNNING');
    if (!hasActive) return;
    const id = setInterval(() => void load(), 3000);
    return () => clearInterval(id);
  }, [jobs]);

  async function cancel(jobId: string) {
    if (!confirm(t('adminIndexingJobs.confirmCancel'))) return;
    setBusy(jobId);
    try {
      await apiClient.post(`/indexing-jobs/${jobId}/cancel`);
      await load();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(null);
    }
  }

  async function retry(jobId: string) {
    setBusy(jobId);
    try {
      await apiClient.post(`/indexing-jobs/${jobId}/retry`);
      await load();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(null);
    }
  }

  const filtered = filter === 'ALL' ? jobs : jobs.filter((j) => j.status === filter);

  const counts = {
    total: jobs.length,
    pending: jobs.filter((j) => j.status === 'PENDING').length,
    running: jobs.filter((j) => j.status === 'RUNNING').length,
    completed: jobs.filter((j) => j.status === 'COMPLETED').length,
    failed: jobs.filter((j) => j.status === 'FAILED').length,
  };

  return (
    <div className="space-y-6">
      <Button variant="ghost" size="sm" asChild>
        <Link to="/admin">
          <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}
        </Link>
      </Button>

      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight flex items-center gap-2">
            <Database className="h-5 w-5" /> {t('adminIndexingJobs.title')}
          </h1>
          <p className="text-sm text-muted-foreground">
            {t('adminIndexingJobs.subtitle')}
          </p>
        </div>
        <Button size="sm" variant="outline" onClick={() => void load()}>
          <RefreshCw className="mr-1 h-3 w-3" /> {t('adminIndexingJobs.refresh')}
        </Button>
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-5">
        <div className="rounded-md border p-3">
          <div className="text-xs text-muted-foreground">{t('adminIndexingJobs.summaryTotal')}</div>
          <div className="text-xl font-bold">{counts.total}</div>
        </div>
        <div className="rounded-md border p-3">
          <div className="text-xs text-muted-foreground">{t('adminIndexingJobs.summaryPending')}</div>
          <div className="text-xl font-bold">{counts.pending}</div>
        </div>
        <div className="rounded-md border p-3">
          <div className="text-xs text-muted-foreground">{t('adminIndexingJobs.summaryRunning')}</div>
          <div className="text-xl font-bold text-blue-500">{counts.running}</div>
        </div>
        <div className="rounded-md border p-3">
          <div className="text-xs text-muted-foreground">{t('adminIndexingJobs.summaryCompleted')}</div>
          <div className="text-xl font-bold text-green-500">{counts.completed}</div>
        </div>
        <div className="rounded-md border p-3">
          <div className="text-xs text-muted-foreground">{t('adminIndexingJobs.summaryFailed')}</div>
          <div className={`text-xl font-bold ${counts.failed > 0 ? 'text-destructive' : ''}`}>
            {counts.failed}
          </div>
        </div>
      </div>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between space-y-0">
          <CardTitle className="text-base">{t('adminIndexingJobs.allJobs')}</CardTitle>
          <Select value={filter} onValueChange={(v) => setFilter(v as 'ALL' | JobStatus)}>
            <SelectTrigger className="w-40">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">{t('adminIndexingJobs.filterAll')}</SelectItem>
              <SelectItem value="PENDING">{t('adminIndexingJobs.filterPending')}</SelectItem>
              <SelectItem value="RUNNING">{t('adminIndexingJobs.filterRunning')}</SelectItem>
              <SelectItem value="COMPLETED">{t('adminIndexingJobs.filterCompleted')}</SelectItem>
              <SelectItem value="FAILED">{t('adminIndexingJobs.filterFailed')}</SelectItem>
              <SelectItem value="CANCELLED">{t('adminIndexingJobs.filterCancelled')}</SelectItem>
            </SelectContent>
          </Select>
        </CardHeader>
        <CardContent className="p-0">
          {loading ? (
            <LoadingState message={t('adminIndexingJobs.loading')} />
          ) : error ? (
            <ErrorState message={error} />
          ) : filtered.length === 0 ? (
            <EmptyState title={t('adminIndexingJobs.emptyTitle')} description={t('adminIndexingJobs.emptyDesc')} />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('adminIndexingJobs.colStatus')}</TableHead>
                  <TableHead>{t('adminIndexingJobs.colKind')}</TableHead>
                  <TableHead>{t('adminIndexingJobs.colWorkspace')}</TableHead>
                  <TableHead>{t('adminIndexingJobs.colProgress')}</TableHead>
                  <TableHead>{t('adminIndexingJobs.colCreated')}</TableHead>
                  <TableHead>{t('adminIndexingJobs.colActions')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((job) => {
                  const progress = job.totalTargets > 0
                    ? Math.round((job.processedTargets / job.totalTargets) * 100)
                    : 0;
                  return (
                    <TableRow key={job.id}>
                      <TableCell>
                        <Badge variant={STATUS_VARIANT[job.status]} className="text-[10px]">
                          {job.status === 'RUNNING' && (
                            <Loader2 className="mr-1 h-3 w-3 animate-spin" />
                          )}
                          {t(STATUS_KEY[job.status])}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        <code className="text-xs">{job.kind}</code>
                      </TableCell>
                      <TableCell>
                        <Link
                          to={`/admin/indexing-jobs`}
                          className="font-mono text-xs text-muted-foreground hover:text-primary"
                        >
                          {job.workspaceId.substring(0, 8)}...
                        </Link>
                      </TableCell>
                      <TableCell>
                        <div className="flex items-center gap-2">
                          <div className="h-2 w-20 rounded-full bg-muted">
                            <div
                              className="h-full rounded-full bg-primary"
                              style={{ width: `${progress}%` }}
                            />
                          </div>
                          <span className="text-xs text-muted-foreground">
                            {job.processedTargets}/{job.totalTargets}
                          </span>
                        </div>
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground whitespace-nowrap">
                        {format(new Date(job.createdAt), 'MMM d, HH:mm')}
                      </TableCell>
                      <TableCell>
                        <div className="flex gap-1">
                          {(job.status === 'PENDING' || job.status === 'RUNNING') && (
                            <Button
                              size="sm"
                              variant="ghost"
                              className="h-7 text-xs"
                              onClick={() => void cancel(job.id)}
                              disabled={busy === job.id}
                            >
                              <X className="h-3 w-3" />
                            </Button>
                          )}
                          {job.status === 'FAILED' && (
                            <Button
                              size="sm"
                              variant="ghost"
                              className="h-7 text-xs"
                              onClick={() => void retry(job.id)}
                              disabled={busy === job.id}
                            >
                              <RotateCw className="h-3 w-3" />
                            </Button>
                          )}
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
    </div>
  );
}
