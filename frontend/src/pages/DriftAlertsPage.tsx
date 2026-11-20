import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { AlertTriangle, ArrowLeft, Check, ChevronDown, ChevronRight, Play, Loader2 } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../components/ui/select';
import { Card, CardContent } from '../components/ui/card';
import {
    Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '../components/ui/dialog';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { driftApi, DriftAlert, DriftKind, DriftResolution, DriftSeverity } from '../services/drift';
import { aiApi, RepositoryDriftScanJobStatus } from '../services/ai';
import UnifiedDiffViewer from '../components/UnifiedDiffViewer';
import { describeError } from '../services/auth';
import { usePermission } from '../hooks/usePermission';
import { PermissionCodes } from '../services/permissions';
import { format } from 'date-fns';

const SEVERITY_BADGE: Record<DriftSeverity, 'destructive' | 'warning' | 'info' | 'muted'> = {
  CRITICAL: 'destructive', HIGH: 'warning', MEDIUM: 'info', LOW: 'muted',
};

const KIND_BADGE: Record<DriftKind, 'default' | 'secondary' | 'muted'> = {
  REFERENTIAL: 'default', SIGNATURE: 'secondary', SEMANTIC: 'muted',
};

const RESOLUTION_BADGE: Record<DriftResolution, 'info' | 'success' | 'warning' | 'muted' | 'destructive'> = {
  OPEN: 'warning', ACCEPTED: 'success', DISMISSED: 'muted', FIXED: 'info',
};

const SEVERITY_KEY: Record<DriftSeverity, string> = {
  CRITICAL: 'drift.severityCritical',
  HIGH: 'drift.severityHigh',
  MEDIUM: 'drift.severityMedium',
  LOW: 'drift.severityLow',
};

const KIND_KEY: Record<DriftKind, string> = {
  REFERENTIAL: 'drift.kindReferential',
  SIGNATURE: 'drift.kindSignature',
  SEMANTIC: 'drift.kindSemantic',
};

const RESOLUTION_KEY: Record<DriftResolution, string> = {
  OPEN: 'drift.resolutionOpen',
  ACCEPTED: 'drift.resolutionAccepted',
  DISMISSED: 'drift.resolutionDismissed',
  FIXED: 'drift.resolutionFixed',
};

interface AlertWithRepo extends DriftAlert {
  repositoryName?: string;
}

function EvidenceRow({ evidenceJson, t }: { evidenceJson: string; t: (k: string) => string }) {
  const [open, setOpen] = useState(false);
  let parsed: unknown;
  try { parsed = JSON.parse(evidenceJson); } catch { parsed = evidenceJson; }

  // The semantic-drift analyzer stores the raw code diff in the evidence
  // object. When it is there we render it with the shared diff viewer
  // instead of a raw JSON dump.
  const diffText = extractDiff(evidenceJson);

  return (
    <div className="mt-2 rounded border bg-muted/50 p-2">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
      >
        {open ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
        {open ? t('drift.hideEvidence') : t('drift.showEvidence')}
      </button>
      {open && (
        diffText ? (
          <div className="mt-2">
            <UnifiedDiffViewer diff={diffText} emptyMessage={t('drift.noDiff')} />
          </div>
        ) : (
          <pre className="mt-2 overflow-auto rounded bg-background p-2 text-xs font-mono max-h-48">
            {JSON.stringify(parsed, null, 2)}
          </pre>
        )
      )}
    </div>
  );
}

/**
 * Pull a unified diff out of a drift alert's evidence JSON.
 *
 * <p>The evidence shape differs by producer: the LLM analyzer stores
 * `{"diff": "..."}`, the AST detector stores the changed symbols. We
 * look for a string value that actually looks like a diff.
 */
function extractDiff(evidenceJson: string): string | null {
  if (!evidenceJson) return null;
  let parsed: unknown;
  try { parsed = JSON.parse(evidenceJson); } catch { return null; }
  if (!parsed || typeof parsed !== 'object') return null;

  const record = parsed as Record<string, unknown>;
  for (const key of ['diff', 'unifiedDiff', 'unified_diff', 'patch', 'codeDiff']) {
    const value = record[key];
    if (typeof value === 'string' && value.includes('@@')) return value;
  }

  // The LLM may nest the diff inside an `evidence` object.
  const nested = record.evidence;
  if (nested && typeof nested === 'object') {
    return extractDiff(JSON.stringify(nested));
  }
  return null;
}

export default function DriftAlertsPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { t } = useTranslation();

  // Everybody in the workspace needs to see the drift alerts; only the
  // review tiers may close them. The backend refuses a resolve from
  // anybody else, so the button is hidden rather than left to fail.
  const canResolve = usePermission(PermissionCodes.DRIFT_RESOLVE);

  const [alerts, setAlerts] = useState<AlertWithRepo[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  const [severityFilter, setSeverityFilter] = useState<string>('ALL');
  const [kindFilter, setKindFilter] = useState<string>('ALL');
  const [statusFilter, setStatusFilter] = useState<string>('OPEN');

  const [resolveAlertId, setResolveAlertId] = useState<string | null>(null);
  const [resolveResolution, setResolveResolution] = useState<DriftResolution>('FIXED');

  // Trigger scan state
  const [scanOpen, setScanOpen] = useState<boolean>(false);
  const [scanRepositoryId, setScanRepositoryId] = useState<string>('');
  const [scanPullRequestId, setScanPullRequestId] = useState<string>('');
  const [scanning, setScanning] = useState<boolean>(false);
  const [scanResult, setScanResult] = useState<string | null>(null);
  const [scanOptions, setScanOptions] = useState<{ repositories: { id: string; fullName: string }[]; documents: { id: string; title: string }[]; pullRequests: { id: string; number: number; title: string }[] }>({ repositories: [], documents: [], pullRequests: [] });
  const [scanOptionsLoading, setScanOptionsLoading] = useState<boolean>(false);
  // The outcome of the last repository-wide scan, shown as a summary
  // of what the scan found.
  const [scanJob, setScanJob] = useState<RepositoryDriftScanJobStatus | null>(null);

  const load = async () => {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    try {
      const params: { status?: DriftResolution; kind?: DriftKind; severity?: DriftSeverity } = {};
      if (statusFilter !== 'ALL') params.status = statusFilter as DriftResolution;
      if (kindFilter !== 'ALL') params.kind = kindFilter as DriftKind;
      if (severityFilter !== 'ALL') params.severity = severityFilter as DriftSeverity;
      const list = await driftApi.list(workspaceId, params);
      setAlerts(list as AlertWithRepo[]);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [workspaceId, severityFilter, kindFilter, statusFilter]);

  async function handleResolve() {
    if (!workspaceId || !resolveAlertId) return;
    try {
      await driftApi.resolve(workspaceId, resolveAlertId, resolveResolution);
      setResolveAlertId(null);
      await load();
    } catch (err) {
      setError(describeError(err));
    }
  }

  async function openScanDialog() {
    if (!workspaceId) return;
    setScanOpen(true);
    setScanResult(null);
    setScanJob(null);
    setScanRepositoryId('');
    setScanPullRequestId('');
    if (scanOptions.repositories.length === 0 && scanOptions.documents.length === 0) {
      setScanOptionsLoading(true);
      try {
        // Lazy import to avoid circular deps
        const [{ githubApi }, { documentsApi }] = await Promise.all([
          import('../services/github'),
          import('../services/documents'),
        ]);
        const [repos, docs] = await Promise.all([
          githubApi.listRepositories(workspaceId).catch(() => []),
          documentsApi.list(workspaceId).catch(() => []),
        ]);
        // The pull requests of the first repository give the scan a
        // concrete "before/after" to compare — without one the analyser
        // has no code change to look at.
        const prs = repos.length > 0
          ? await githubApi.listPullRequests(workspaceId, repos[0].id).catch(() => [])
          : [];
        setScanOptions({
          repositories: repos.map((r) => ({ id: r.id, fullName: r.fullName })),
          documents: docs.map((d) => ({ id: d.id, title: d.title })),
          pullRequests: prs.map((p) => ({ id: p.id, number: p.number, title: p.title })),
        });
      } catch (err) {
        setError(describeError(err));
      } finally {
        setScanOptionsLoading(false);
      }
    }
  }

  /** Load the pull requests of the selected repository. */
  async function loadPullRequestsForRepository(repositoryId: string) {
    if (!workspaceId) return;
    try {
      const { githubApi } = await import('../services/github');
      const prs = await githubApi.listPullRequests(workspaceId, repositoryId).catch(() => []);
      setScanOptions((prev) => ({
        ...prev,
        pullRequests: prs.map((p) => ({ id: p.id, number: p.number, title: p.title })),
      }));
    } catch {
      setScanOptions((prev) => ({ ...prev, pullRequests: [] }));
    }
  }

  /**
   * Scan a whole repository for drift.
   *
   * <p>No document is chosen: the backend walks every document of the
   * repository and compares each one against the code. The run is
   * asynchronous — one LLM call per document — so the button queues a
   * job and the poll loop follows it to the end.
   */
  async function handleTriggerScan() {
    if (!workspaceId || !scanRepositoryId) return;
    setScanning(true);
    setScanResult(null);
    setScanJob(null);
    try {
      const queued = await aiApi.scanRepositoryForDrift(
        workspaceId,
        scanRepositoryId,
        scanPullRequestId || undefined,
      );
      const final = await pollDriftScan(workspaceId, queued.jobId);
      setScanJob(final);
      if (final.status === 'FAILED' && final.errorMessage) {
        // A failed run has a reason — an expired GitHub credential,
        // an unreachable repository. It must not read as "no drift".
        setScanResult(final.errorMessage);
      } else {
        setScanResult(
          t('drift.scanComplete', {
            count: final.alertsCreated,
            defaultValue: `Scan complete. ${final.alertsCreated} new alert(s) ingested.`,
          }),
        );
      }
      await load();
    } catch (err) {
      setScanResult(describeError(err));
    } finally {
      setScanning(false);
    }
  }

  /**
   * Follow a repository drift scan until it finishes.
   *
   * <p>The scan costs one LLM call per document, so it can take a
   * while. The poll loop is generous about the timeout but always ends
   * — a stuck run must not hang the page forever.
   */
  async function pollDriftScan(wsId: string, jobId: string) {
    const terminal = new Set(['SUCCEEDED', 'PARTIAL', 'FAILED']);
    // ~10 minutes worth of polls.
    const maxAttempts = 300;

    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      await new Promise((resolve) => setTimeout(resolve, 2000));
      const job = await aiApi.repositoryDriftScanStatus(wsId, jobId);
      if (terminal.has(job.status)) {
        return job;
      }
      setScanResult(
        t('drift.scanProgress', {
          scanned: job.scannedDocuments,
          total: job.totalDocuments,
          defaultValue: `Scanning… ${job.scannedDocuments}/${job.totalDocuments} documents compared.`,
        }),
      );
    }

    throw new Error(t('drift.scanTimeout', 'The scan is taking longer than expected. Check back shortly.'));
  }

  if (!workspaceId) return <EmptyState title={t('common.back')} />;

  const criticalCount = alerts.filter((a) => a.severity === 'CRITICAL').length;
  const highCount = alerts.filter((a) => a.severity === 'HIGH').length;
  const mediumCount = alerts.filter((a) => a.severity === 'MEDIUM').length;
  const lowCount = alerts.filter((a) => a.severity === 'LOW').length;

  return (
    <div className="space-y-4">
      <Button variant="ghost" size="sm" asChild>
        <Link to={`/workspaces/${workspaceId}`}>
          <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}
        </Link>
      </Button>

      <div className="flex items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">{t('drift.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('drift.subtitle')}</p>
        </div>
        <Dialog open={scanOpen} onOpenChange={(o) => { if (!o) { setScanOpen(false); setScanResult(null); } }}>
          <DialogTrigger asChild>
            <Button onClick={() => void openScanDialog()}>
              <Play className="mr-1 h-4 w-4" /> {t('drift.triggerScan', 'Trigger scan')}
            </Button>
          </DialogTrigger>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>{t('drift.triggerScanTitle', 'Manually trigger drift analysis')}</DialogTitle>
              <DialogDescription>
                {t('drift.triggerScanDesc', 'Run the AI against the latest code entities. This may take a minute and counts toward AI usage.')}
              </DialogDescription>
            </DialogHeader>
            <div className="space-y-3 py-2">
              {scanOptionsLoading ? (
                <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
              ) : (
                <>
                  <div className="space-y-1.5">
                    <label className="text-sm font-medium">{t('drift.scanRepo', 'Repository')}</label>
                    <Select
                      value={scanRepositoryId}
                      onValueChange={(v) => {
                        setScanRepositoryId(v);
                        setScanPullRequestId('');
                        void loadPullRequestsForRepository(v);
                      }}
                    >
                      <SelectTrigger><SelectValue placeholder={t('drift.scanRepoPlaceholder', 'Select a repository')} /></SelectTrigger>
                      <SelectContent>
                        {scanOptions.repositories.map((r) => (
                          <SelectItem key={r.id} value={r.id}>{r.fullName}</SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                  <div className="space-y-1.5">
                    <label className="text-sm font-medium">
                      {t('drift.scanPullRequest', 'Pull request')}
                      <span className="ml-1 text-xs text-muted-foreground">
                        {t('drift.scanPullRequestOptional', 'optional')}
                      </span>
                    </label>
                    <Select value={scanPullRequestId} onValueChange={setScanPullRequestId}>
                      <SelectTrigger>
                        <SelectValue placeholder={t('drift.scanPullRequestPlaceholder', 'No pull request — use the latest code')} />
                      </SelectTrigger>
                      <SelectContent>
                        {scanOptions.pullRequests.map((p) => (
                          <SelectItem key={p.id} value={p.id}>#{p.number} — {p.title}</SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <p className="text-xs text-muted-foreground">
                      {t('drift.scanPullRequestHint', 'With a pull request, the before/after content is read from GitHub, so the comparison is exact.')}
                    </p>
                  </div>
                  <div className="rounded-md border bg-muted/50 p-3 text-xs text-muted-foreground">
                    {t('drift.scanScopeHint', 'Every document of the selected repository is compared against the code. Documents that no longer match produce a drift alert.')}
                  </div>
                </>
              )}
              {scanResult && (
                <div className="rounded-md border border-primary/40 bg-primary/5 p-3 text-sm text-primary">
                  {scanResult}
                </div>
              )}
              {scanJob && scanJob.documents && scanJob.documents.length > 0 && (
                <div className="max-h-56 overflow-auto rounded-md border">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>{t('drift.colTitle')}</TableHead>
                        <TableHead>{t('drift.colStatus')}</TableHead>
                        <TableHead>{t('drift.colAlerts')}</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {scanJob.documents.map((d) => (
                        <TableRow key={d.documentId}>
                          <TableCell className="text-xs">{d.title}</TableCell>
                          <TableCell className="text-xs text-muted-foreground">
                            {d.status === 'SCANNED'
                              ? (d.alerts > 0 ? t('drift.scanDocDrifted') : t('drift.scanDocClean'))
                              : d.status === 'SKIPPED'
                                ? t('drift.scanDocSkipped')
                                : t('drift.scanDocFailed')}
                          </TableCell>
                          <TableCell className="text-xs">{d.alerts}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              )}
            </div>
            <DialogFooter>
              <DialogClose asChild><Button variant="outline" disabled={scanning}>{t('common.cancel')}</Button></DialogClose>
              <Button onClick={() => void handleTriggerScan()} disabled={scanning || !scanRepositoryId}>
                {scanning ? <><Loader2 className="mr-1 h-4 w-4 animate-spin" /> {t('drift.scanning', 'Scanning…')}</> : t('drift.runScan', 'Run scan')}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Card className="border-destructive/50">
          <CardContent className="pt-4">
            <div className="text-2xl font-bold text-destructive">{criticalCount}</div>
            <div className="text-xs text-muted-foreground">{t('drift.severityCritical')}</div>
          </CardContent>
        </Card>
        <Card className="border-orange-500/50">
          <CardContent className="pt-4">
            <div className="text-2xl font-bold text-orange-500">{highCount}</div>
            <div className="text-xs text-muted-foreground">{t('drift.severityHigh')}</div>
          </CardContent>
        </Card>
        <Card className="border-blue-500/50">
          <CardContent className="pt-4">
            <div className="text-2xl font-bold text-blue-500">{mediumCount}</div>
            <div className="text-xs text-muted-foreground">{t('drift.severityMedium')}</div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-4">
            <div className="text-2xl font-bold text-muted-foreground">{lowCount}</div>
            <div className="text-xs text-muted-foreground">{t('drift.severityLow')}</div>
          </CardContent>
        </Card>
      </div>

      <div className="flex flex-wrap gap-2">
        <Select value={statusFilter} onValueChange={setStatusFilter}>
          <SelectTrigger className="w-36"><SelectValue placeholder={t('documents.status')} /></SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">{t('drift.allStatuses')}</SelectItem>
            <SelectItem value="OPEN">{t('drift.resolutionOpen')}</SelectItem>
            <SelectItem value="ACCEPTED">{t('drift.resolutionAccepted')}</SelectItem>
            <SelectItem value="DISMISSED">{t('drift.resolutionDismissed')}</SelectItem>
            <SelectItem value="FIXED">{t('drift.resolutionFixed')}</SelectItem>
          </SelectContent>
        </Select>
        <Select value={severityFilter} onValueChange={setSeverityFilter}>
          <SelectTrigger className="w-36"><SelectValue placeholder={t('drift.severity')} /></SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">{t('drift.allSeverities')}</SelectItem>
            <SelectItem value="CRITICAL">{t('drift.severityCritical')}</SelectItem>
            <SelectItem value="HIGH">{t('drift.severityHigh')}</SelectItem>
            <SelectItem value="MEDIUM">{t('drift.severityMedium')}</SelectItem>
            <SelectItem value="LOW">{t('drift.severityLow')}</SelectItem>
          </SelectContent>
        </Select>
        <Select value={kindFilter} onValueChange={setKindFilter}>
          <SelectTrigger className="w-36"><SelectValue placeholder={t('drift.kind')} /></SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">{t('drift.allKinds')}</SelectItem>
            <SelectItem value="REFERENTIAL">{t('drift.kindReferential')}</SelectItem>
            <SelectItem value="SIGNATURE">{t('drift.kindSignature')}</SelectItem>
            <SelectItem value="SEMANTIC">{t('drift.kindSemantic')}</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {error && <ErrorState message={error} />}
      {loading && <LoadingState message={t('drift.loading')} />}

      {!loading && !error && alerts.length === 0 && (
        <EmptyState
          icon={<AlertTriangle className="h-8 w-8" />}
          title={t('drift.emptyTitle')}
          description={t('drift.emptyDesc')}
        />
      )}

      {!loading && !error && alerts.length > 0 && (
        <Card>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('drift.colSeverity')}</TableHead>
                  <TableHead>{t('drift.colKind')}</TableHead>
                  <TableHead>{t('drift.colTitle')}</TableHead>
                  <TableHead>{t('drift.colDetected')}</TableHead>
                  <TableHead>{t('drift.colStatus')}</TableHead>
                  <TableHead>{t('drift.colActions')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {alerts.map((a) => (
                  <TableRow key={a.id}>
                    <TableCell>
                      <Badge variant={SEVERITY_BADGE[a.severity]}>{t(SEVERITY_KEY[a.severity])}</Badge>
                    </TableCell>
                    <TableCell>
                      <Badge variant={KIND_BADGE[a.driftKind]}>{t(KIND_KEY[a.driftKind])}</Badge>
                    </TableCell>
                    <TableCell>
                      <div className="max-w-[200px]">
                        <div className="truncate font-medium text-sm">{a.title}</div>
                        {a.aiSuggestion && (
                          <div className="mt-1 text-xs text-muted-foreground truncate">
                            {t('drift.aiHint')}: {a.aiSuggestion.slice(0, 80)}
                          </div>
                        )}
                        {a.evidenceJson && <EvidenceRow evidenceJson={a.evidenceJson} t={t} />}
                      </div>
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground whitespace-nowrap">
                      {format(new Date(a.detectedAt), 'MMM d, yyyy HH:mm')}
                    </TableCell>
                    <TableCell>
                      <Badge variant={RESOLUTION_BADGE[a.resolutionStatus]}>{t(RESOLUTION_KEY[a.resolutionStatus])}</Badge>
                    </TableCell>
                    <TableCell>
                      {canResolve ? (
                        <Dialog open={resolveAlertId === a.id} onOpenChange={(o) => { if (!o) setResolveAlertId(null); }}>
                          <DialogTrigger asChild>
                            <Button size="sm" variant="outline" onClick={() => { setResolveAlertId(a.id); setResolveResolution('FIXED'); }}>
                              <Check className="mr-1 h-3 w-3" /> {t('drift.resolve')}
                            </Button>
                          </DialogTrigger>
                          <DialogContent>
                            <DialogHeader>
                              <DialogTitle>{t('drift.resolveDialogTitle')}</DialogTitle>
                              <DialogDescription>{a.title}</DialogDescription>
                            </DialogHeader>
                            <div className="space-y-3 py-2">
                              <Select value={resolveResolution} onValueChange={(v) => setResolveResolution(v as DriftResolution)}>
                                <SelectTrigger><SelectValue /></SelectTrigger>
                                <SelectContent>
                                  <SelectItem value="FIXED">{t('drift.resolutionFixed')}</SelectItem>
                                  <SelectItem value="ACCEPTED">{t('drift.resolutionAcceptedFull')}</SelectItem>
                                  <SelectItem value="DISMISSED">{t('drift.resolutionDismissed')}</SelectItem>
                                  <SelectItem value="OPEN">{t('drift.resolutionOpen')}</SelectItem>
                                </SelectContent>
                              </Select>
                            </div>
                            <DialogFooter>
                              <DialogClose asChild><Button variant="outline">{t('common.cancel')}</Button></DialogClose>
                              <Button onClick={() => void handleResolve()}>{t('drift.confirmResolution')}</Button>
                            </DialogFooter>
                          </DialogContent>
                        </Dialog>
                      ) : (
                        <span className="text-xs text-muted-foreground">
                          {t('drift.resolveHint', 'Only reviewers can resolve a drift alert.')}
                        </span>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
