import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { TFunction } from 'i18next';
import {
  CheckCircle, FileDiff, Link2, Loader2, MessageSquare, Send, Sparkles, XCircle,
} from 'lucide-react';
import { Button } from '../ui/button';
import { Badge } from '../ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { Textarea } from '../ui/textarea';
import { Label } from '../ui/label';
import { Input } from '../ui/input';
import { ErrorState, LoadingState } from '../ui/states';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle,
} from '../ui/dialog';
import {
  DocumentReview, reviewsApi, ReviewDecision, TemplateCompliance,
} from '../../services/drift';
import { documentsApi, DocumentVersion, DocumentVersionSummary } from '../../services/documents';
import { aiApi, GroundingSource } from '../../services/ai';
import { describeError, hasPermission } from '../../services/auth';
import { useAuth } from '../../contexts/AuthContext';
import DocumentAuditTrail from '../document/DocumentAuditTrail';

/**
 * A markdown heading of the version under review.
 *
 * <p>Mirrors the backend's `MarkdownSections` parser: the slug is the
 * lower-cased heading with the punctuation replaced by hyphens.
 */
interface Heading {
  level: number;
  text: string;
  slug: string;
  line: number;
}

const HEADING_RE = /^(#{1,6})\s+(.+?)\s*#*\s*$/;

/**
 * Parse the headings out of a markdown body.
 *
 * <p>Headings inside fenced code blocks are skipped — a shell script
 * with a `#` comment must not be mistaken for a heading.
 */
function parseHeadings(markdown: string): Heading[] {
  if (!markdown) return [];
  const out: Heading[] = [];
  const lines = markdown.split(/\R/);
  let inFence = false;
  const counts = new Map<string, number>();
  lines.forEach((line, index) => {
    const trimmed = line.trim();
    if (trimmed.startsWith('```') || trimmed.startsWith('~~~')) {
      inFence = !inFence;
      return;
    }
    if (inFence) return;
    const m = HEADING_RE.exec(trimmed);
    if (!m) return;
    const text = m[2].trim();
    const base = text
      .toLowerCase()
      .replace(/[^\p{Letter}\p{Number}]+/gu, '-')
      .replace(/^-+/, '')
      .replace(/-+$/, '');
    const seen = (counts.get(base) ?? 0) + 1;
    counts.set(base, seen);
    out.push({
      level: m[1].length,
      text,
      slug: seen > 1 ? `${base}-${seen - 1}` : base,
      line: index,
    });
  });
  return out;
}

interface ReviewDrawerProps {
  workspaceId: string;
  documentId: string;
  versionId: string;
  /** Which tier is acting — decides the endpoint and the available actions. */
  kind: 'staff' | 'manager';
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Called after a verdict was recorded, so the queue can refresh. */
  onDecided: () => void;
}

/**
 * The review screen of one version.
 *
 * <p>Requirements #3, #4, #5, #7, #8 and #10 of the STAFF role all
 * land here:
 * <ul>
 *   <li>the full change log of the document (#3) and the diff against
 *       the published version (#4);</li>
 *   <li>the template compliance of the version (#5);</li>
 *   <li>an inline editor that fixes small errors without a round trip
 *       through the author (#7);</li>
 *   <li>comments anchored to a specific section of the document (#8);</li>
 *   <li>the whole timeline, not a one-line change summary (#10).</li>
 * </ul>
 */
export default function ReviewDrawer({
  workspaceId, documentId, versionId, kind, open, onOpenChange, onDecided,
}: ReviewDrawerProps) {
  const { t } = useTranslation();
  const { user } = useAuth();
  // Reading the trail of a document is a separate capability from
  // reviewing it. The Manager is required to see the trail before
  // granting approval; a Staff reviewer without AUDIT_LOG_VIEW simply
  // does not get the panel rather than a 403 on every drawer open.
  const canViewAuditTrail = hasPermission(user, 'AUDIT_LOG_VIEW');

  const [version, setVersion] = useState<DocumentVersion | null>(null);
  const [timeline, setTimeline] = useState<DocumentVersionSummary[]>([]);
  const [compliance, setCompliance] = useState<TemplateCompliance | null>(null);
  /**
   * The review history of this version — most recent first.
   *
   * <p>This is the record of what the tiers already said about the
   * content: the Staff comments a Manager needs to read before
   * approving, and the previous verdicts of the tier that is acting.
   */
  const [reviews, setReviews] = useState<DocumentReview[]>([]);
  /** The code entities the AI grounded this version in (requirement #2). */
  const [grounding, setGrounding] = useState<GroundingSource[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [acting, setActing] = useState(false);

  const [comment, setComment] = useState('');
  const [selectedHeading, setSelectedHeading] = useState<Heading | null>(null);
  const [editing, setEditing] = useState(false);
  const [draftBody, setDraftBody] = useState('');
  const [changeSummary, setChangeSummary] = useState('');

  const headings = useMemo(
    () => parseHeadings(version?.bodyMarkdown ?? ''),
    [version?.bodyMarkdown],
  );

  const load = useCallback(async () => {
    if (!versionId) return;
    setLoading(true);
    setError(null);
    try {
      const timelineData = await documentsApi.timeline(documentId).catch(
        () => [] as DocumentVersionSummary[],
      );
      setTimeline(timelineData);

      // The timeline projection carries no body, so fetch the version
      // itself for the body the reviewer is looking at.
      const target = timelineData.find((v) => v.id === versionId);
      if (target) {
        const full = await documentsApi.version(documentId, target.versionNumber);
        setVersion(full);
        setDraftBody(full.bodyMarkdown);
      }

      const complianceData = await reviewsApi
        .templateCompliance(workspaceId, documentId, versionId)
        .catch(() => null as TemplateCompliance | null);
      setCompliance(complianceData);

      // The review history: the Staff comments the Manager tier has to
      // read, plus the previous verdicts of the tier that is acting.
      const reviewsData = await reviewsApi
        .forVersion(workspaceId, documentId, versionId)
        .catch(() => [] as DocumentReview[]);
      setReviews(reviewsData);

      // The grounding evidence: which code entities the AI relied on.
      // A version written by hand has none, which is not an error.
      const groundingData = await aiApi
        .fetchGrounding(workspaceId, documentId, versionId)
        .then((r) => r.sources ?? [])
        .catch(() => [] as GroundingSource[]);
      setGrounding(groundingData);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }, [workspaceId, documentId, versionId]);

  useEffect(() => {
    if (open) void load();
  }, [open, load]);

  async function decide(decision: ReviewDecision) {
    setActing(true);
    setError(null);
    try {
      const section = selectedHeading
        ? {
          sectionId: selectedHeading.slug,
          sectionHeading: selectedHeading.text,
          sectionAnchor: `#${selectedHeading.slug}`,
        }
        : undefined;
      if (kind === 'staff') {
        await reviewsApi.staffReview(
          workspaceId, documentId, versionId, decision, comment, section,
        );
      } else {
        await reviewsApi.managerDecision(
          workspaceId, documentId, versionId, decision, comment, section,
        );
      }
      setComment('');
      setSelectedHeading(null);
      onDecided();
      onOpenChange(false);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setActing(false);
    }
  }

  /**
   * Fix a small error in the version under review.
   *
   * The revision creates a new version that supersedes the one being
   * reviewed and stays in the queue, so the reviewer can approve the
   * corrected content right away.
   */
  async function saveRevision() {
    setActing(true);
    setError(null);
    try {
      await reviewsApi.revise(
        workspaceId, documentId, versionId, draftBody,
        changeSummary || undefined,
      );
      setEditing(false);
      setChangeSummary('');
      onDecided();
      onOpenChange(false);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setActing(false);
    }
  }

  const missingSections = compliance?.missingHeadings ?? [];

  /**
   * The reviews that came from the other tier.
   *
   * <p>For the Manager this is the Staff feedback — the whole point of
   * the two-tier workflow is that the Manager decides on the strength of
   * what Staff wrote. For the Staff tier it is the Manager's verdict on
   * an earlier pass, which the Staff member needs to see before
   * commenting again.
   */
  const otherTierReviews = useMemo(
    () => reviews.filter((r) => r.reviewerRole !== (kind === 'staff' ? 'STAFF' : 'MANAGER')),
    [reviews, kind],
  );

  /**
   * The reviews of the tier that is acting — what this reviewer already
   * said about this version.
   */
  const ownTierReviews = useMemo(
    () => reviews.filter((r) => r.reviewerRole === (kind === 'staff' ? 'STAFF' : 'MANAGER')),
    [reviews, kind],
  );

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-4xl max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t('reviews.drawer.title', 'Review version')}</DialogTitle>
          <DialogDescription>
            {version
              ? `${version.versionNumber} · ${t('reviews.drawer.subtitle', 'Check the content before deciding.')}`
              : t('reviews.drawer.subtitle', 'Check the content before deciding.')}
          </DialogDescription>
        </DialogHeader>

        {error && <ErrorState message={error} />}
        {loading && <LoadingState message={t('reviews.loading')} />}

        {!loading && version && (
          <div className="space-y-4">
            {/* Grounding / template summary */}
            <div className="flex flex-wrap gap-2">
              {version.sourceTicketRef && (
                <Badge variant="info">{version.sourceTicketRef}</Badge>
              )}
              {version.sourceCommitSha && (
                <Badge variant="muted">
                  {version.sourceCommitSha.slice(0, 7)}
                </Badge>
              )}
              {compliance?.templateName && (
                <Badge variant={compliance.compliant ? 'success' : 'destructive'}>
                  {compliance.compliant
                    ? t('reviews.drawer.templateOk', 'Template ✓')
                    : t('reviews.drawer.templateMissing', 'Template ✗')}
                </Badge>
              )}
            </div>

            {missingSections.length > 0 && (
              <Card>
                <CardHeader className="pb-2">
                  <CardTitle className="text-sm">
                    {t('reviews.drawer.missingSections', 'Missing sections')}
                  </CardTitle>
                </CardHeader>
                <CardContent>
                  <div className="flex flex-wrap gap-1.5">
                    {missingSections.map((h) => (
                      <Badge key={h} variant="destructive">{h}</Badge>
                    ))}
                  </div>
                </CardContent>
              </Card>
            )}

            {/* Review history — the comments the other tier left, and
                the verdicts this reviewer already recorded. For the
                Manager tier this is the Staff feedback the two-tier
                workflow depends on. */}
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm flex items-center">
                  <MessageSquare className="mr-1 h-4 w-4" />
                  {t('reviews.drawer.reviewHistory', 'Review history')}
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                {reviews.length === 0 ? (
                  <p className="text-xs text-muted-foreground">
                    {t('reviews.drawer.noReviews', 'No one has reviewed this version yet.')}
                  </p>
                ) : (
                  <>
                    {otherTierReviews.length > 0 && (
                      <ReviewList
                        reviews={otherTierReviews}
                        label={kind === 'manager'
                          ? t('reviews.drawer.staffFeedback', 'Staff feedback')
                          : t('reviews.drawer.managerVerdict', 'Manager verdict')}
                        emptyLabel=""
                        t={t}
                      />
                    )}
                    {ownTierReviews.length > 0 && (
                      <ReviewList
                        reviews={ownTierReviews}
                        label={kind === 'manager'
                          ? t('reviews.drawer.yourTier', 'Your previous decisions')
                          : t('reviews.drawer.yourTier', 'Your previous decisions')}
                        emptyLabel=""
                        t={t}
                      />
                    )}
                  </>
                )}
              </CardContent>
            </Card>

            {/* Grounding evidence — which code entities the AI relied on. */}
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm flex items-center">
                  <Sparkles className="mr-1 h-4 w-4" />
                  {t('reviews.drawer.grounding', 'Grounding evidence')}
                </CardTitle>
              </CardHeader>
              <CardContent>
                {grounding.length === 0 ? (
                  <p className="text-xs text-muted-foreground">
                    {t(
                      'reviews.drawer.noGrounding',
                      'No grounding evidence is stored for this version. Versions written by hand, and versions created before the grounding feature shipped, have none.',
                    )}
                  </p>
                ) : (
                  <>
                    <p className="mb-2 text-xs text-muted-foreground">
                      {t(
                        'reviews.drawer.groundingSummary',
                        'The AI referenced {{count}} code entity/entities when generating this version.',
                        { count: grounding.length },
                      )}
                    </p>
                    <ul className="max-h-48 space-y-1.5 overflow-y-auto">
                      {grounding.map((s, i) => (
                        <li
                          key={`${s.qualifiedName}-${i}`}
                          className="rounded border bg-muted/30 p-2"
                        >
                          <div className="flex items-center gap-2">
                            <Badge variant="info">{s.kind}</Badge>
                            <code className="truncate font-mono text-xs">
                              {s.qualifiedName}
                            </code>
                          </div>
                          {s.file && (
                            <div className="mt-1 truncate font-mono text-xs text-muted-foreground">
                              <Link2 className="mr-1 inline h-3 w-3" />
                              {s.file}
                              {s.lines && s.lines.length > 0
                                ? `:${s.lines[0]}-${s.lines[s.lines.length - 1]}`
                                : ''}
                            </div>
                          )}
                          {s.signature && (
                            <pre className="mt-1 whitespace-pre-wrap break-words font-mono text-[11px] text-foreground/80">
                              {s.signature}
                            </pre>
                          )}
                        </li>
                      ))}
                    </ul>
                  </>
                )}
              </CardContent>
            </Card>

            {/* Change log */}
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm flex items-center">
                  <FileDiff className="mr-1 h-4 w-4" />
                  {t('reviews.drawer.changeLog', 'Change log')}
                </CardTitle>
              </CardHeader>
              <CardContent className="max-h-48 overflow-y-auto">
                <ul className="space-y-1.5 text-xs">
                  {timeline.map((v) => (
                    <li
                      key={v.id}
                      className={v.id === versionId
                        ? 'rounded px-2 py-1 bg-primary/10 font-medium'
                        : 'rounded px-2 py-1'}
                    >
                      <span className="font-mono">v{v.versionNumber}</span>
                      <span className="ml-2 text-muted-foreground">
                        {v.actorRole}
                      </span>
                      <span className="ml-2">{v.changeSummary ?? '—'}</span>
                      {v.sourceTicketRef && (
                        <Badge variant="muted" className="ml-2">
                          {v.sourceTicketRef}
                        </Badge>
                      )}
                    </li>
                  ))}
                </ul>
              </CardContent>
            </Card>

            {/* The complete audit trail of the document.
                The MANAGER role is required to "view the complete audit
                trail of a document (AI-generated, auto-updated,
                staff-edited) before granting approval" — so the trail
                belongs right here, next to the content and the Staff
                comments the decision is based on.

                It is only rendered for a reader who holds
                AUDIT_LOG_VIEW; the backend enforces the same rule. */}
            {canViewAuditTrail && (
              <DocumentAuditTrail
                workspaceId={workspaceId}
                documentId={documentId}
                pageSize={20}
              />
            )}

            {/* Section picker + body */}
            <Card>
              <CardHeader className="pb-2">
                <CardTitle className="text-sm flex items-center">
                  <MessageSquare className="mr-1 h-4 w-4" />
                  {t('reviews.drawer.commentOnSection', 'Comment on a section')}
                </CardTitle>
              </CardHeader>
              <CardContent className="space-y-3">
                <div className="flex flex-wrap gap-1.5">
                  <Button
                    size="sm"
                    variant={selectedHeading === null ? 'default' : 'outline'}
                    onClick={() => setSelectedHeading(null)}
                  >
                    {t('reviews.drawer.wholeDocument', 'Whole document')}
                  </Button>
                  {headings.map((h) => (
                    <Button
                      key={h.slug}
                      size="sm"
                      variant={
                        selectedHeading?.slug === h.slug ? 'default' : 'outline'
                      }
                      onClick={() => setSelectedHeading(h)}
                    >
                      {h.text}
                    </Button>
                  ))}
                </div>

                {editing ? (
                  <div className="space-y-2">
                    <div className="space-y-1.5">
                      <Label>{t('reviews.drawer.changeSummary', 'Change summary')}</Label>
                      <Input
                        value={changeSummary}
                        onChange={(e) => setChangeSummary(e.target.value)}
                        placeholder={t(
                          'reviews.drawer.changeSummaryPlaceholder',
                          'What did you fix?',
                        )}
                      />
                    </div>
                    <Textarea
                      rows={16}
                      className="font-mono text-xs"
                      value={draftBody}
                      onChange={(e) => setDraftBody(e.target.value)}
                    />
                  </div>
                ) : (
                  <pre className="max-h-80 overflow-auto whitespace-pre-wrap rounded bg-muted/40 p-3 font-mono text-xs">
                    {version.bodyMarkdown}
                  </pre>
                )}

                <div className="space-y-1.5">
                  <Label>{t('reviews.colComment', 'Comment')}</Label>
                  <Textarea
                    rows={3}
                    className="text-xs"
                    placeholder={t('reviews.placeholder.comment')}
                    value={comment}
                    onChange={(e) => setComment(e.target.value)}
                  />
                  {selectedHeading && (
                    <p className="text-xs text-muted-foreground">
                      {t('reviews.drawer.targetSection', 'Target section')}:
                      {' '}<span className="font-medium">{selectedHeading.text}</span>
                    </p>
                  )}
                </div>
              </CardContent>
            </Card>
          </div>
        )}

        <DialogFooter className="flex-wrap gap-2">
          {version && !editing && (
            <Button
              variant="outline"
              size="sm"
              disabled={acting}
              onClick={() => setEditing(true)}
            >
              {t('reviews.drawer.edit', 'Fix small errors')}
            </Button>
          )}
          {editing && (
            <Button
              size="sm"
              disabled={acting || !draftBody.trim()}
              onClick={() => void saveRevision()}
            >
              {acting
                ? <Loader2 className="mr-1 h-3 w-3 animate-spin" />
                : <Send className="mr-1 h-3 w-3" />}
              {t('reviews.drawer.saveRevision', 'Save revision')}
            </Button>
          )}
          {editing && (
            <Button
              variant="outline"
              size="sm"
              disabled={acting}
              onClick={() => {
                setEditing(false);
                setDraftBody(version?.bodyMarkdown ?? '');
              }}
            >
              {t('common.cancel', 'Cancel')}
            </Button>
          )}
          {!editing && (
            <>
              <Button
                size="sm"
                variant="outline"
                disabled={acting}
                onClick={() => void decide('COMMENT')}
              >
                <MessageSquare className="mr-1 h-3 w-3" />
                {t('reviews.action.comment', 'Comment')}
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={acting}
                onClick={() => void decide('REJECTED')}
              >
                <XCircle className="mr-1 h-3 w-3" />
                {t('reviews.action.reject', 'Reject')}
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={acting}
                onClick={() => void decide('REQUEST_CHANGES')}
              >
                <XCircle className="mr-1 h-3 w-3" />
                {t('reviews.action.requestChanges', 'Request changes')}
              </Button>
              <Button
                size="sm"
                disabled={acting}
                onClick={() => void decide('APPROVED')}
              >
                {acting
                  ? <Loader2 className="mr-1 h-3 w-3 animate-spin" />
                  : <CheckCircle className="mr-1 h-3 w-3" />}
                {t('reviews.action.approve', 'Approve')}
              </Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * The badge colour of a review verdict.
 */
const DECISION_VARIANT: Record<ReviewDecision, 'success' | 'destructive' | 'warning' | 'muted'> = {
  APPROVED: 'success',
  REJECTED: 'destructive',
  REQUEST_CHANGES: 'warning',
  COMMENT: 'muted',
};

interface ReviewListProps {
  reviews: DocumentReview[];
  label: string;
  emptyLabel: string;
  t: TFunction;
}

/**
 * A list of review records.
 *
 * <p>Each entry shows who wrote the comment, in which tier, what they
 * decided, the section the comment is anchored to and when they did it.
 */
function ReviewList({ reviews, label, emptyLabel, t }: ReviewListProps) {
  if (reviews.length === 0) {
    return emptyLabel ? (
      <p className="text-xs text-muted-foreground">{emptyLabel}</p>
    ) : null;
  }
  return (
    <div className="space-y-1.5">
      <p className="text-xs font-medium text-muted-foreground">{label}</p>
      <ul className="space-y-1.5">
        {reviews.map((r) => (
          <li key={r.id} className="rounded border bg-muted/30 p-2">
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant={DECISION_VARIANT[r.decision] ?? 'muted'}>
                {t(`reviews.mine.decision.${r.decision}`, r.decision)}
              </Badge>
              <span className="text-xs font-medium">
                {r.reviewerName ?? r.reviewerUserId}
              </span>
              <Badge variant="outline">{r.reviewerRole}</Badge>
              {r.sectionHeading && (
                <Badge variant="info">
                  {t('reviews.drawer.section', 'Section')}: {r.sectionHeading}
                </Badge>
              )}
              <span className="ml-auto text-[11px] text-muted-foreground">
                {r.decidedAt ? new Date(r.decidedAt).toLocaleString() : ''}
              </span>
            </div>
            {r.comment && (
              <p className="mt-1 whitespace-pre-wrap text-xs">{r.comment}</p>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
