import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, CheckCircle, Loader2, Send, Upload, XCircle } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Card, CardContent } from '../components/ui/card';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../components/ui/tabs';
import { Textarea } from '../components/ui/textarea';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import {
  reviewsApi, ReviewDecision, ReviewQueue, ReviewQueueItem, SubmittedVersion,
} from '../services/drift';
import { documentsApi } from '../services/documents';
import { describeError } from '../services/auth';

/** Which tier a queue row belongs to. */
type QueueKind = 'staff' | 'manager' | 'publish';

/**
 * The badge variant used for each stage of a submitted version.
 *
 * Mirrors the backend's `stage` field, which already collapses the
 * version status and the latest verdict into one label.
 */
const STAGE_VARIANT: Record<
  SubmittedVersion['stage'],
  'muted' | 'warning' | 'success' | 'info' | 'destructive' | 'secondary'
> = {
  WAITING_STAFF: 'warning',
  WAITING_MANAGER: 'warning',
  CHANGES_REQUESTED: 'destructive',
  REJECTED: 'destructive',
  APPROVED: 'success',
  PUBLISHED: 'info',
  DRAFT: 'secondary',
  SUPERSEDED: 'muted',
};

export default function ReviewQueuePage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { t } = useTranslation();

  const [queue, setQueue] = useState<ReviewQueue | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [acting, setActing] = useState<string | null>(null);
  const [comment, setComment] = useState<Record<string, string>>({});

  const load = useCallback(async () => {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    try {
      setQueue(await reviewsApi.pendingOnMe(workspaceId));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }, [workspaceId]);

  useEffect(() => { void load(); }, [load]);

  if (!workspaceId) return <EmptyState title={t('common.back')} />;

  const staffQueue = queue?.staffQueue ?? [];
  const managerQueue = queue?.managerQueue ?? [];
  const publishQueue = queue?.publishQueue ?? [];
  const mySubmissions = queue?.mySubmissions ?? [];

  const canStaff = queue?.canStaffReview ?? false;
  const canManage = queue?.canManagerApprove ?? false;

  async function decide(item: ReviewQueueItem, kind: QueueKind, decision: ReviewDecision) {
    if (!workspaceId) return;
    const key = `${kind}:${item.versionId}`;
    setActing(key);
    setError(null);
    try {
      if (kind === 'publish') {
        await documentsApi.publishVersion(workspaceId, item.documentId, item.versionNumber);
      } else if (kind === 'staff') {
        await reviewsApi.staffReview(
          workspaceId, item.documentId, item.versionId, decision, comment[item.versionId],
        );
      } else {
        await reviewsApi.managerDecision(
          workspaceId, item.documentId, item.versionId, decision, comment[item.versionId],
        );
      }
      await load();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setActing(null);
    }
  }

  /**
   * The tab that is shown first. A reviewer lands on the queue that
   * actually needs their attention; an author — who has no reviewing
   * role — lands on their own submissions.
   */
  function defaultTab(): string {
    if (canStaff && staffQueue.length > 0) return 'staff';
    if (canManage && managerQueue.length > 0) return 'manager';
    if (canManage && publishQueue.length > 0) return 'publish';
    return 'mine';
  }

  const hasAnyQueueTab = canStaff || canManage;

  return (
    <div className="space-y-4">
      <Button variant="ghost" size="sm" asChild>
        <Link to={`/workspaces/${workspaceId}`}>
          <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}
        </Link>
      </Button>

      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">{t('reviews.title')}</h1>
          <p className="text-sm text-muted-foreground">{t('reviews.subtitle')}</p>
        </div>
        {queue && queue.platformRoles.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {queue.platformRoles.map((r) => (
              <Badge key={r} variant="muted">{r}</Badge>
            ))}
          </div>
        )}
      </div>

      {error && <ErrorState message={error} />}
      {loading && <LoadingState message={t('reviews.loading')} />}

      {!loading && !error && queue && (
        <Tabs defaultValue={defaultTab()}>
          <TabsList>
            {canStaff && (
              <TabsTrigger value="staff">
                {t('reviews.queue.staff')}
                {staffQueue.length > 0 && (
                  <Badge variant="warning" className="ml-2">{staffQueue.length}</Badge>
                )}
              </TabsTrigger>
            )}
            {canManage && (
              <TabsTrigger value="manager">
                {t('reviews.queue.manager')}
                {managerQueue.length > 0 && (
                  <Badge variant="warning" className="ml-2">{managerQueue.length}</Badge>
                )}
              </TabsTrigger>
            )}
            {canManage && (
              <TabsTrigger value="publish">
                {t('reviews.queue.publish')}
                {publishQueue.length > 0 && (
                  <Badge variant="muted" className="ml-2">{publishQueue.length}</Badge>
                )}
              </TabsTrigger>
            )}
            <TabsTrigger value="mine">
              {t('reviews.queue.mine')}
              {mySubmissions.length > 0 && (
                <Badge variant="muted" className="ml-2">{mySubmissions.length}</Badge>
              )}
            </TabsTrigger>
          </TabsList>

          {canStaff && (
            <TabsContent value="staff">
              <QueueTable
                items={staffQueue}
                kind="staff"
                workspaceId={workspaceId}
                comment={comment}
                setComment={setComment}
                acting={acting}
                onDecide={decide}
                t={t}
              />
            </TabsContent>
          )}

          {canManage && (
            <TabsContent value="manager">
              <QueueTable
                items={managerQueue}
                kind="manager"
                workspaceId={workspaceId}
                comment={comment}
                setComment={setComment}
                acting={acting}
                onDecide={decide}
                t={t}
              />
            </TabsContent>
          )}

          {canManage && (
            <TabsContent value="publish">
              <QueueTable
                items={publishQueue}
                kind="publish"
                workspaceId={workspaceId}
                comment={comment}
                setComment={setComment}
                acting={acting}
                onDecide={decide}
                t={t}
              />
            </TabsContent>
          )}

          <TabsContent value="mine">
            <MySubmissionsTable
              items={mySubmissions}
              workspaceId={workspaceId}
              t={t}
            />
          </TabsContent>
        </Tabs>
      )}

      {!loading && !error && queue && !hasAnyQueueTab && (
        <p className="text-xs text-muted-foreground">{t('reviews.authorHint')}</p>
      )}
    </div>
  );
}

interface QueueTableProps {
  items: ReviewQueueItem[];
  kind: QueueKind;
  workspaceId: string;
  comment: Record<string, string>;
  setComment: (c: Record<string, string>) => void;
  acting: string | null;
  onDecide: (item: ReviewQueueItem, kind: QueueKind, decision: ReviewDecision) => void;
  t: (k: string) => string;
}

function QueueTable({
  items, kind, workspaceId, comment, setComment, acting, onDecide, t,
}: QueueTableProps) {
  if (items.length === 0) {
    return (
      <Card><CardContent className="pt-6">
        <EmptyState title={t('reviews.nothingPending')} description={t('reviews.nothingPendingDesc')} />
      </CardContent></Card>
    );
  }

  return (
    <Card>
      <CardContent className="p-0">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t('reviews.colDocument')}</TableHead>
              <TableHead>{t('reviews.colVersion')}</TableHead>
              <TableHead>{t('reviews.colDocType')}</TableHead>
              <TableHead>{t('reviews.colConfidence')}</TableHead>
              <TableHead>{t('reviews.colChange')}</TableHead>
              <TableHead className="text-right">{t('reviews.colActions')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {items.map((item) => {
              const key = `${kind}:${item.versionId}`;
              const busy = acting === key;
              return (
                <TableRow key={item.versionId}>
                  <TableCell>
                    <Link
                      to={`/workspaces/${workspaceId}/documents/${item.documentId}`}
                      className="hover:text-primary font-medium"
                    >
                      {item.documentTitle}
                    </Link>
                  </TableCell>
                  <TableCell>v{item.versionNumber}</TableCell>
                  <TableCell><Badge variant="muted">{item.docType}</Badge></TableCell>
                  <TableCell>
                    {item.confidenceScore != null
                      ? `${Math.round(item.confidenceScore * 100)}%`
                      : '—'}
                  </TableCell>
                  <TableCell className="text-xs text-muted-foreground max-w-[18rem] truncate">
                    {item.changeSummary ?? '—'}
                  </TableCell>
                  <TableCell>
                    {kind === 'publish' ? (
                      <div className="flex justify-end">
                        <Button
                          size="sm"
                          disabled={busy}
                          onClick={() => onDecide(item, kind, 'APPROVED')}
                        >
                          {busy ? <Loader2 className="mr-1 h-3 w-3 animate-spin" /> : <Upload className="mr-1 h-3 w-3" />}
                          {t('reviews.action.publish')}
                        </Button>
                      </div>
                    ) : (
                      <div className="flex flex-col items-end gap-2">
                        <Textarea
                          rows={1}
                          className="text-xs"
                          placeholder={t('reviews.placeholder.comment')}
                          value={comment[item.versionId] ?? ''}
                          onChange={(e) =>
                            setComment({ ...comment, [item.versionId]: e.target.value })}
                        />
                        <div className="flex gap-1.5">
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={busy}
                            onClick={() => onDecide(item, kind, 'REQUEST_CHANGES')}
                          >
                            <XCircle className="mr-1 h-3 w-3" />
                            {t('reviews.action.requestChanges')}
                          </Button>
                          <Button
                            size="sm"
                            disabled={busy}
                            onClick={() => onDecide(item, kind, 'APPROVED')}
                          >
                            {busy
                              ? <Loader2 className="mr-1 h-3 w-3 animate-spin" />
                              : kind === 'staff'
                                ? <Send className="mr-1 h-3 w-3" />
                                : <CheckCircle className="mr-1 h-3 w-3" />}
                            {t('reviews.action.approve')}
                          </Button>
                        </div>
                      </div>
                    )}
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}

/**
 * The author-side table: the versions the caller submitted for review and
 * what happened to them.
 *
 * This is the only part of the page a DEVELOPER can act on — they hold
 * no reviewing role, so the three review queues above are empty for
 * them. The table is read-only: the author reacts to the verdict (for
 * example by fixing the draft), they do not make the call.
 */
function MySubmissionsTable({
  items, workspaceId, t,
}: {
  items: SubmittedVersion[];
  workspaceId: string;
  t: (k: string) => string;
}) {
  if (items.length === 0) {
    return (
      <Card><CardContent className="pt-6">
        <EmptyState
          title={t('reviews.mine.nothingSubmitted')}
          description={t('reviews.mine.nothingSubmittedDesc')}
        />
      </CardContent></Card>
    );
  }

  return (
    <Card>
      <CardContent className="p-0">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t('reviews.colDocument')}</TableHead>
              <TableHead>{t('reviews.colVersion')}</TableHead>
              <TableHead>{t('reviews.colDocType')}</TableHead>
              <TableHead>{t('reviews.mine.colStatus')}</TableHead>
              <TableHead>{t('reviews.colDecision')}</TableHead>
              <TableHead>{t('reviews.colComment')}</TableHead>
              <TableHead>{t('reviews.mine.colDecided')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {items.map((item) => (
              <TableRow key={item.versionId}>
                <TableCell>
                  <Link
                    to={`/workspaces/${workspaceId}/documents/${item.documentId}`}
                    className="hover:text-primary font-medium"
                  >
                    {item.documentTitle}
                  </Link>
                  {item.changeSummary && (
                    <p className="text-xs text-muted-foreground mt-0.5 max-w-[22rem] truncate">
                      {item.changeSummary}
                    </p>
                  )}
                </TableCell>
                <TableCell>v{item.versionNumber}</TableCell>
                <TableCell><Badge variant="muted">{item.docType}</Badge></TableCell>
                <TableCell>
                  <Badge variant={STAGE_VARIANT[item.stage] ?? 'muted'}>
                    {t(`reviews.mine.stage.${item.stage}`)}
                  </Badge>
                </TableCell>
                <TableCell>
                  {item.latestDecision
                    ? t(`reviews.mine.decision.${item.latestDecision}`)
                    : '—'}
                  {item.latestReviewerName && (
                    <p className="text-xs text-muted-foreground mt-0.5">
                      {item.latestReviewerName}
                      {item.latestReviewerRole && ` (${item.latestReviewerRole})`}
                    </p>
                  )}
                </TableCell>
                <TableCell className="text-xs text-muted-foreground max-w-[20rem]">
                  {item.latestComment ?? '—'}
                </TableCell>
                <TableCell className="text-xs text-muted-foreground whitespace-nowrap">
                  {item.latestDecidedAt
                    ? new Date(item.latestDecidedAt).toLocaleString()
                    : '—'}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}
