import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { CheckCircle, FileDiff, Loader2, MessageSquare, Send, XCircle } from 'lucide-react';
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
import { reviewsApi, ReviewDecision, TemplateCompliance } from '../../services/drift';
import { documentsApi, DocumentVersion, DocumentVersionSummary } from '../../services/documents';
import { describeError } from '../../services/auth';

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

  const [version, setVersion] = useState<DocumentVersion | null>(null);
  const [timeline, setTimeline] = useState<DocumentVersionSummary[]>([]);
  const [compliance, setCompliance] = useState<TemplateCompliance | null>(null);
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
