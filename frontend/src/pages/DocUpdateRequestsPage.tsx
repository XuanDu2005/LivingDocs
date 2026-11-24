import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  ArrowLeft,
  CheckCircle2,
  ExternalLink,
  GitCommit,
  GitBranch,
  Loader2,
  Shield,
  ShieldCheck,
  XCircle,
  FileCode2,
  FileText,
  Eye,
} from 'lucide-react';
import { format } from 'date-fns';

import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Card, CardContent } from '../components/ui/card';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '../components/ui/select';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import { Textarea } from '../components/ui/textarea';
import { Label } from '../components/ui/label';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../components/ui/dialog';

import { docUpdateRequestApi, DocUpdateRequest, DocUpdateStatus } from '../services/docUpdateRequests';
import { githubApi, Repository } from '../services/github';
import { useAuth } from '../contexts/AuthContext';
import { hasPermission, describeError } from '../services/auth';
import { reviewsApi, WorkspaceCapabilities } from '../services/drift';
const STATUS_VARIANT: Record<DocUpdateStatus, 'warning' | 'success' | 'destructive' | 'info'> = {
  PENDING_REVIEW: 'warning',
  APPROVED: 'info',
  REJECTED: 'destructive',
  APPLIED: 'success',
};

const STATUS_KEY: Record<DocUpdateStatus, string> = {
  PENDING_REVIEW: 'docUpdates.statusPending',
  APPROVED: 'docUpdates.statusApproved',
  REJECTED: 'docUpdates.statusRejected',
  APPLIED: 'docUpdates.statusApplied',
};

const STATUS_OPTIONS: DocUpdateStatus[] = ['PENDING_REVIEW', 'APPROVED', 'REJECTED', 'APPLIED'];

interface ReviewDialogState {
  open: boolean;
  request: DocUpdateRequest | null;
  comment: string;
  busy: boolean;
  error: string | null;
  info: string | null;
}

/**
 * Doc Update Requests page.
 *
 * Lists the "review-before-push" proposals produced by the system when
 * a code change is detected.
 *
 * <p>A proposal is not a queue of its own any more: when the file it
 * concerns is published by a LivingDocs document, the proposal is
 * materialised as a version of that document and submitted for review.
 * The approve/reject buttons here therefore drive the same two-tier
 * Staff → Manager workflow as the shared review queue, and the proposal
 * shows up in the document's timeline.
 *
 * <p>Staff (or any member with the review capability) can approve/reject;
 * Managers can additionally call `apply` to push the content as a real
 * PR on GitHub.
 */
export default function DocUpdateRequestsPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { t } = useTranslation();
  const { user } = useAuth();

  const [repos, setRepos] = useState<Repository[]>([]);
  const [repoId, setRepoId] = useState<string>('');
  const [statusFilter, setStatusFilter] = useState<DocUpdateStatus | 'ALL'>('PENDING_REVIEW');

  const [requests, setRequests] = useState<DocUpdateRequest[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * Whether the caller may push an approved proposal to GitHub.
   *
   * <p>This is a two-axis check, and getting either axis wrong produces
   * a 403 on click:
   * <ul>
   *   <li><b>workspace role</b> — the backend demands
   *       {@code WorkspaceRole.MANAGER} in <i>this</i> workspace. A
   *       platform MANAGER who is only a plain member of the workspace
   *       is refused, and a workspace manager whose platform role is
   *       DEVELOPER is allowed.</li>
   *   <li><b>platform capability</b> — {@code SCM_COMMIT} is what
   *       actually authorises writing to an external repository.</li>
   * </ul>
   *
   * <p>The previous check looked at the platform role alone, which
   * blocked the first case and let the second through to a 403.
   */
  const [capabilities, setCapabilities] = useState<WorkspaceCapabilities | null>(null);

  const [review, setReview] = useState<ReviewDialogState>({
    open: false, request: null, comment: '', busy: false, error: null, info: null,
  });

  // Load the caller's capabilities in this workspace so the Apply button
  // matches what the backend will actually accept.
  useEffect(() => {
    if (!workspaceId) return;
    let alive = true;
    void reviewsApi.capabilities(workspaceId)
      .then((c: WorkspaceCapabilities) => { if (alive) setCapabilities(c); })
      .catch(() => { if (alive) setCapabilities(null); });
    return () => { alive = false; };
  }, [workspaceId]);

  const canApply = !!capabilities?.workspaceManager && hasPermission(user, 'SCM_COMMIT');
  const isManager = canApply;
  /**
   * Whether the caller may record a review verdict on a proposal.
   *
   * <p>The proposal is reviewed through the same Staff tier as a
   * document version, so it needs the same capability. Rendering the
   * button for somebody without it would offer an action the API
   * refuses.
   */
  const canReview = hasPermission(user, 'DOCUMENT_VERSION_REVIEW');

  // Load repositories for the workspace
  useEffect(() => {
    if (!workspaceId) return;
    let alive = true;
    (async () => {
      try {
        const list = await githubApi.listRepositories(workspaceId);
        if (!alive) return;
        setRepos(list);
        if (list.length > 0 && !repoId) setRepoId(list[0].id);
      } catch (err) {
        if (!alive) return;
        setError(describeError(err));
      }
    })();
    return () => { alive = false; };
  }, [workspaceId]);

  // Load doc update requests whenever repo or filter changes
  const load = async () => {
    if (!workspaceId || !repoId) return;
    setLoading(true);
    setError(null);
    try {
      const data = await docUpdateRequestApi.list(
        workspaceId,
        repoId,
        statusFilter === 'ALL' ? undefined : statusFilter,
      );
      setRequests(data);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [workspaceId, repoId, statusFilter]);

  const counts = useMemo(() => ({
    PENDING_REVIEW: requests.filter((r) => r.status === 'PENDING_REVIEW').length,
    APPROVED: requests.filter((r) => r.status === 'APPROVED').length,
    REJECTED: requests.filter((r) => r.status === 'REJECTED').length,
    APPLIED: requests.filter((r) => r.status === 'APPLIED').length,
  }), [requests]);

  /**
   * Whether the proposal is waiting for the Manager tier.
   *
   * <p>The proposal's own `APPROVED` status only records the Staff
   * decision. Pushing to Git needs the Manager approval as well, and
   * that decision lives on the materialised version — so the button is
   * gated on the version's status, not on the proposal's.
   *
   * <p>A proposal without a version (a file no document publishes) has
   * no second tier, so it is ready to push.
   */
  const awaitingManagerApproval = useMemo(() => {
    const req = review.request;
    if (!req || req.status !== 'APPROVED') return false;
    if (!req.documentVersionId || !req.versionStatus) return false;
    return req.versionStatus !== 'APPROVED' && req.versionStatus !== 'PUBLISHED';
  }, [review.request]);

  const openReview = (r: DocUpdateRequest) =>
    setReview({ open: true, request: r, comment: '', busy: false, error: null, info: null });

  const closeReview = () =>
    setReview((prev) => ({ ...prev, open: false, error: null, info: null }));

  async function doApprove() {
    if (!workspaceId || !review.request || !repoId) return;
    setReview((p) => ({ ...p, busy: true, error: null, info: null }));
    try {
      await docUpdateRequestApi.approve(workspaceId, repoId, review.request.id, {
        comment: review.comment || undefined,
      });
      setReview((p) => ({ ...p, info: t('docUpdates.review.approveSuccess') }));
      await load();
      setTimeout(closeReview, 700);
    } catch (err) {
      setReview((p) => ({ ...p, error: describeError(err), busy: false }));
    }
  }

  async function doReject() {
    if (!workspaceId || !review.request || !repoId) return;
    setReview((p) => ({ ...p, busy: true, error: null, info: null }));
    try {
      await docUpdateRequestApi.reject(workspaceId, repoId, review.request.id, {
        comment: review.comment || undefined,
      });
      setReview((p) => ({ ...p, info: t('docUpdates.review.rejectSuccess') }));
      await load();
      setTimeout(closeReview, 700);
    } catch (err) {
      setReview((p) => ({ ...p, error: describeError(err), busy: false }));
    }
  }

  async function doApply() {
    if (!workspaceId || !review.request || !repoId) return;
    setReview((p) => ({ ...p, busy: true, error: null, info: null }));
    try {
      const updated = await docUpdateRequestApi.apply(workspaceId, repoId, review.request.id);
      setReview((p) => ({
        ...p,
        request: updated,
        info: t('docUpdates.review.applySuccess'),
        busy: false,
      }));
      await load();
    } catch (err) {
      setReview((p) => ({ ...p, error: describeError(err), busy: false }));
    }
  }

  if (!workspaceId) return <EmptyState title={t('common.back')} />;

  return (
    <div className="space-y-4">
      <Button variant="ghost" size="sm" asChild>
        <Link to={`/workspaces/${workspaceId}`}>
          <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}
        </Link>
      </Button>

      <div>
        <h1 className="text-2xl font-bold tracking-tight">{t('docUpdates.title')}</h1>
        <p className="text-sm text-muted-foreground">{t('docUpdates.subtitle')}</p>
      </div>

      <Card>
        <CardContent className="flex flex-wrap items-end gap-3 pt-4">
          <div className="min-w-[220px] flex-1">
            <Label className="text-xs text-muted-foreground">{t('docUpdates.selectRepository')}</Label>
            <Select value={repoId} onValueChange={setRepoId}>
              <SelectTrigger>
                <SelectValue placeholder={t('docUpdates.selectRepository')} />
              </SelectTrigger>
              <SelectContent>
                {repos.map((r) => (
                  <SelectItem key={r.id} value={r.id}>
                    {r.fullName}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="min-w-[200px]">
            <Label className="text-xs text-muted-foreground">{t('docUpdates.colStatus')}</Label>
            <Select
              value={statusFilter}
              onValueChange={(v) => setStatusFilter(v as DocUpdateStatus | 'ALL')}
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">{t('docUpdates.allStatuses')}</SelectItem>
                {STATUS_OPTIONS.map((s) => (
                  <SelectItem key={s} value={s}>
                    {t(STATUS_KEY[s])}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="ml-auto flex flex-wrap gap-2">
            <Badge variant="warning">{t('docUpdates.statusPending')}: {counts.PENDING_REVIEW}</Badge>
            <Badge variant="info">{t('docUpdates.statusApproved')}: {counts.APPROVED}</Badge>
            <Badge variant="destructive">{t('docUpdates.statusRejected')}: {counts.REJECTED}</Badge>
            <Badge variant="success">{t('docUpdates.statusApplied')}: {counts.APPLIED}</Badge>
          </div>
        </CardContent>
      </Card>

      {error && <ErrorState message={error} />}
      {loading && <LoadingState message={t('docUpdates.loading')} />}

      {!loading && !error && repoId && requests.length === 0 && (
        <EmptyState
          title={t('docUpdates.emptyTitle')}
          description={t('docUpdates.emptyDesc')}
        />
      )}

      {!loading && !error && requests.length > 0 && (
        <Card>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('docUpdates.colFile')}</TableHead>
                  <TableHead>{t('docUpdates.colDocument')}</TableHead>
                  <TableHead>{t('docUpdates.colStatus')}</TableHead>
                  <TableHead>{t('docUpdates.colTrigger')}</TableHead>
                  <TableHead>{t('docUpdates.colRequestedAt')}</TableHead>
                  <TableHead>{t('docUpdates.colReviewer')}</TableHead>
                  <TableHead className="text-right">{}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {requests.map((r) => (
                  <TableRow key={r.id}>
                    <TableCell className="max-w-[240px]">
                      <div className="flex items-center gap-2">
                        <FileCode2 className="h-4 w-4 text-muted-foreground" />
                        <span className="truncate font-mono text-xs">{r.filePath}</span>
                      </div>
                    </TableCell>
                    {/* The document that publishes this file. The link is
                        the whole point: the proposal is a version of that
                        document, so a reviewer can go straight to the
                        document — and its timeline — from here. */}
                    <TableCell className="max-w-[220px]">
                      {r.documentId ? (
                        <Link
                          to={`/workspaces/${workspaceId}/documents/${r.documentId}`}
                          className="flex items-center gap-1 truncate text-xs font-medium text-primary hover:underline"
                        >
                          <FileText className="h-3 w-3 shrink-0" />
                          <span className="truncate">{t('docUpdates.openDocument')}</span>
                        </Link>
                      ) : (
                        <span className="text-xs text-muted-foreground">
                          {t('docUpdates.noDocument')}
                        </span>
                      )}
                    </TableCell>
                    <TableCell>
                      <Badge variant={STATUS_VARIANT[r.status]}>
                        {t(STATUS_KEY[r.status])}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground">
                      <div className="flex flex-col gap-0.5">
                        {r.triggerBranch && (
                          <span className="flex items-center gap-1">
                            <GitBranch className="h-3 w-3" />
                            {r.triggerBranch}
                          </span>
                        )}
                        {r.triggerCommitSha && (
                          <span className="flex items-center gap-1 font-mono">
                            <GitCommit className="h-3 w-3" />
                            {r.triggerCommitSha.slice(0, 7)}
                          </span>
                        )}
                      </div>
                    </TableCell>
                    <TableCell className="text-xs">
                      {format(new Date(r.requestedAt), 'yyyy-MM-dd HH:mm')}
                    </TableCell>
                    <TableCell className="text-xs">
                      {r.reviewedByUserId ? (
                        <span className="flex items-center gap-1">
                          <ShieldCheck className="h-3 w-3 text-green-600" />
                          {r.reviewedByUserId.slice(0, 8)}
                        </span>
                      ) : (
                        <span className="text-muted-foreground">—</span>
                      )}
                    </TableCell>
                    <TableCell className="text-right">
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => openReview(r)}
                      >
                        <Eye className="mr-1 h-3 w-3" />
                        {t('docUpdates.openReview')}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}

      <Dialog
        open={review.open}
        onOpenChange={(open) => { if (!open) closeReview(); }}
      >
        <DialogContent className="max-w-4xl">
          <DialogHeader>
            <DialogTitle>
              <div className="flex items-center gap-2">
                <Shield className="h-4 w-4" />
                {t('docUpdates.review.title')}
              </div>
            </DialogTitle>
            <DialogDescription>
              {review.request && (
                <span className="font-mono text-xs">{review.request.filePath}</span>
              )}
            </DialogDescription>
          </DialogHeader>

          {review.request && (
            <div className="space-y-4">
              {/* The document this proposal belongs to. The proposal is
                  materialised as a version of that document, so it is
                  reviewed in the shared queue and shows up in the
                  document's timeline. */}
              <div className="flex items-center gap-2 rounded-md border bg-muted/30 p-2 text-xs">
                <FileText className="h-4 w-4 text-muted-foreground" />
                {review.request.documentId ? (
                  <>
                    <span className="text-muted-foreground">
                      {t('docUpdates.review.linkedDocument')}
                    </span>
                    <Link
                      to={`/workspaces/${workspaceId}/documents/${review.request.documentId}`}
                      className="font-medium text-primary hover:underline"
                    >
                      {t('docUpdates.openDocument')}
                    </Link>
                    <span className="text-muted-foreground">
                      {t('docUpdates.review.reviewedInQueue')}
                    </span>
                  </>
                ) : (
                  <span className="text-muted-foreground">
                    {t('docUpdates.review.noLinkedDocument')}
                  </span>
                )}
              </div>

              {/* Trigger metadata */}
              <div className="grid grid-cols-2 gap-3 text-xs">
                <div className="rounded-md border bg-muted/30 p-2">
                  <div className="text-muted-foreground">{t('docUpdates.review.branch')}</div>
                  <div className="mt-0.5 flex items-center gap-1 font-medium">
                    <GitBranch className="h-3 w-3" />
                    {review.request.triggerBranch ?? '—'}
                  </div>
                </div>
                <div className="rounded-md border bg-muted/30 p-2">
                  <div className="text-muted-foreground">{t('docUpdates.review.commit')}</div>
                  <div className="mt-0.5 flex items-center gap-1 font-mono font-medium">
                    <GitCommit className="h-3 w-3" />
                    {review.request.triggerCommitSha
                      ? review.request.triggerCommitSha.slice(0, 12)
                      : '—'}
                  </div>
                </div>
              </div>

              {/* Diff summary */}
              {review.request.diffSummary && (
                <div>
                  <Label className="text-xs">{t('docUpdates.review.diffSummary')}</Label>
                  <div className="mt-1 rounded-md border bg-muted/30 p-2 text-xs">
                    {review.request.diffSummary}
                  </div>
                </div>
              )}
              {!review.request.diffSummary && (
                <div className="text-xs text-muted-foreground">
                  {t('docUpdates.review.noDiffSummary')}
                </div>
              )}

              {/* Side-by-side content */}
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <Label className="text-xs">{t('docUpdates.review.currentContent')}</Label>
                  <pre className="mt-1 max-h-72 overflow-auto rounded-md border bg-muted/30 p-2 text-[11px] font-mono">
                    {review.request.currentContent || t('docUpdates.review.noCurrent')}
                  </pre>
                </div>
                <div>
                  <Label className="text-xs">{t('docUpdates.review.proposedContent')}</Label>
                  <pre className="mt-1 max-h-72 overflow-auto rounded-md border bg-primary/5 p-2 text-[11px] font-mono">
                    {review.request.proposedContent}
                  </pre>
                </div>
              </div>

              {/* Comment + actions */}
              <div>
                <Label className="text-xs">{t('docUpdates.review.commentLabel')}</Label>
                <Textarea
                  className="mt-1"
                  rows={2}
                  placeholder={t('docUpdates.review.commentPlaceholder')}
                  value={review.comment}
                  onChange={(e) => setReview((p) => ({ ...p, comment: e.target.value }))}
                  disabled={review.busy}
                />
              </div>

              {review.error && (
                <div className="rounded-md border border-destructive/50 bg-destructive/5 p-2 text-xs text-destructive">
                  {review.error}
                </div>
              )}
              {review.info && (
                <div className="rounded-md border border-green-500/50 bg-green-500/5 p-2 text-xs text-green-700 dark:text-green-400">
                  {review.info}
                </div>
              )}

              {review.request.appliedPrUrl && (
                <div className="flex items-center gap-2 text-xs">
                  <CheckCircle2 className="h-4 w-4 text-green-600" />
                  <span>PR:</span>
                  <a
                    href={review.request.appliedPrUrl}
                    target="_blank"
                    rel="noreferrer"
                    className="flex items-center gap-1 text-primary underline"
                  >
                    {review.request.appliedPrUrl}
                    <ExternalLink className="h-3 w-3" />
                  </a>
                </div>
              )}
            </div>
          )}

          <DialogFooter className="flex flex-wrap items-center gap-2">
            {review.request?.status === 'PENDING_REVIEW' && canReview && (
              <>
                <Button
                  variant="destructive"
                  onClick={doReject}
                  disabled={review.busy}
                >
                  {review.busy ? <Loader2 className="mr-1 h-3 w-3 animate-spin" /> : <XCircle className="mr-1 h-3 w-3" />}
                  {t('docUpdates.review.reject')}
                </Button>
                <Button
                  variant="default"
                  onClick={doApprove}
                  disabled={review.busy}
                >
                  {review.busy ? <Loader2 className="mr-1 h-3 w-3 animate-spin" /> : <CheckCircle2 className="mr-1 h-3 w-3" />}
                  {t('docUpdates.review.approve')}
                </Button>
              </>
            )}

            {review.request?.status === 'APPROVED' && (
              <Button
                onClick={doApply}
                disabled={review.busy || !isManager || awaitingManagerApproval}
                title={!isManager
                  ? t('docUpdates.review.applyManagerOnly')
                  : awaitingManagerApproval
                    ? t('docUpdates.review.applyNeedsManagerApproval')
                    : undefined}
              >
                {review.busy
                  ? <Loader2 className="mr-1 h-3 w-3 animate-spin" />
                  : <GitBranch className="mr-1 h-3 w-3" />}
                {isManager
                  ? t('docUpdates.review.applyAsManager')
                  : t('docUpdates.review.apply')}
              </Button>
            )}

            {review.request?.status === 'APPLIED' && (
              <Badge variant="success">{t('docUpdates.statusApplied')}</Badge>
            )}

            {review.request?.status === 'REJECTED' && (
              <Badge variant="destructive">{t('docUpdates.statusRejected')}</Badge>
            )}

            <Button variant="ghost" onClick={closeReview} disabled={review.busy}>
              {t('common.cancel') ?? 'Cancel'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
