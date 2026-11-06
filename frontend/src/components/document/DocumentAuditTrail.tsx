import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ChevronLeft, ChevronRight, History, Loader2, ScrollText,
} from 'lucide-react';
import { Badge } from '../ui/badge';
import { Button } from '../ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../ui/card';
import { EmptyState, ErrorState, LoadingState } from '../ui/states';
import {
  listDocumentAuditTrail, AuditTrailEntry,
} from '../../services/auditLogs';
import { describeError } from '../../services/auth';
import { format, parseISO } from 'date-fns';

/**
 * How an entry of the trail is presented.
 *
 * <p>The colour tells the reader which kind of change the entry records:
 * the AI produced something, a person edited something, or somebody made
 * a governance decision.
 */
type EntryKind = 'ai' | 'human' | 'decision' | 'system';

const ACTION_KIND: Record<string, EntryKind> = {
  'document.created': 'human',
  'document.updated': 'human',
  'document.deleted': 'decision',
  'document.version.created': 'human',
  'document.version.ai_created': 'ai',
  'document.ai_generated': 'ai',
  'document.auto_updated': 'ai',
  'document.version.auto_published': 'ai',
  'document.version.published': 'decision',
  'document.version.dismissed': 'decision',
  'document.version.rolled_back': 'decision',
  'document.submitted_for_review': 'human',
  'document.review': 'decision',
  'document.approval': 'decision',
  'document.review_revised': 'human',
  'document.auto_update.toggle': 'human',
};

const KIND_VARIANT: Record<EntryKind, 'info' | 'muted' | 'success' | 'warning'> = {
  ai: 'info',
  human: 'muted',
  decision: 'success',
  system: 'warning',
};

/**
 * Parse a payload string into a record, or {@code null} when it is not
 * a JSON object.
 */
function safeParse(json: string): Record<string, unknown> | null {
  try {
    const parsed = JSON.parse(json);
    return typeof parsed === 'object' && parsed !== null
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

/**
 * A short, human-readable description of what the entry recorded.
 *
 * <p>The payload holds the context of the action — the version number,
 * the decision, the confidence — and that context is what makes the
 * trail readable without expanding the raw JSON.
 */
function summarise(entry: AuditTrailEntry): string | null {
  const payload = safeParse(entry.payload);
  if (!payload) return null;
  const parts: string[] = [];
  if (payload.versionNumber !== undefined && payload.versionNumber !== null) {
    parts.push(`v${String(payload.versionNumber)}`);
  }
  if (payload.decision) parts.push(String(payload.decision));
  if (payload.enabled !== undefined && payload.enabled !== null) {
    parts.push(payload.enabled ? 'enabled' : 'disabled');
  }
  if (payload.confidence !== undefined && payload.confidence !== null) {
    const value = Number(payload.confidence);
    if (!Number.isNaN(value)) parts.push(`${Math.round(value * 100)}% confidence`);
  }
  if (payload.sourceCommitSha) {
    parts.push(String(payload.sourceCommitSha).slice(0, 7));
  }
  if (payload.restoredFromVersion !== undefined && payload.restoredFromVersion !== null) {
    parts.push(`← v${String(payload.restoredFromVersion)}`);
  }
  return parts.length > 0 ? parts.join(' · ') : null;
}

interface DocumentAuditTrailProps {
  workspaceId: string;
  documentId: string;
  /** How many entries one page holds. */
  pageSize?: number;
}

/**
 * The complete audit trail of one document.
 *
 * <p>The Manager role has to be able to "view the complete audit trail
 * of a document (AI-generated, auto-updated, staff-edited) before
 * granting approval". This panel is where that happens: it shows the
 * creation of the document, every version the AI produced or a person
 * wrote, every review verdict and every publication — with the actor
 * and the time — and it can page back to the very first entry.
 *
 * <p>The endpoint behind it is gated by `AUDIT_LOG_VIEW`; a reader
 * without that permission gets a 403, which is surfaced as an error
 * rather than silently hidden.
 */
export default function DocumentAuditTrail({
  workspaceId, documentId, pageSize = 50,
}: DocumentAuditTrailProps) {
  const { t } = useTranslation();

  const [entries, setEntries] = useState<AuditTrailEntry[]>([]);
  const [page, setPage] = useState<number>(0);
  const [total, setTotal] = useState<number>(0);
  const [totalPages, setTotalPages] = useState<number>(0);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const load = useCallback(async () => {
    if (!workspaceId || !documentId) return;
    setLoading(true);
    setError(null);
    try {
      const data = await listDocumentAuditTrail(
        workspaceId, documentId, page, pageSize,
      );
      setEntries(data.items ?? []);
      setTotal(data.total ?? 0);
      setTotalPages(data.totalPages ?? 0);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }, [workspaceId, documentId, page, pageSize]);

  useEffect(() => {
    void load();
  }, [load]);

  function toggleExpand(id: string) {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center text-base">
          <ScrollText className="mr-2 h-4 w-4" />
          {t('documentDetail.auditTrail', 'Audit trail')}
          <Badge variant="muted" className="ml-2">{total}</Badge>
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {loading && <LoadingState message={t('documentDetail.auditTrailLoading', 'Loading the audit trail…')} />}
        {!loading && error && <ErrorState message={error} />}
        {!loading && !error && entries.length === 0 && (
          <EmptyState
            title={t('documentDetail.auditTrailEmpty', 'No entries yet')}
            description={t(
              'documentDetail.auditTrailEmptyDesc',
              'Every change to this document will be recorded here — the AI generations, the auto-updates, the edits and the review decisions.',
            )}
          />
        )}
        {!loading && !error && entries.length > 0 && (
          <>
            <ul className="space-y-1.5">
              {entries.map((entry) => {
                const kind = ACTION_KIND[entry.action] ?? 'system';
                const payload = safeParse(entry.payload);
                const summary = summarise(entry);
                const isOpen = expanded.has(entry.id);
                return (
                  <li key={entry.id} className="rounded border bg-muted/20">
                    <div className="flex flex-wrap items-center gap-2 p-2">
                      <Badge variant={KIND_VARIANT[kind]} className="text-[10px]">
                        {entry.action}
                      </Badge>
                      {summary && (
                        <span className="text-xs text-muted-foreground">{summary}</span>
                      )}
                      <span className="text-xs font-medium">
                        {entry.actorName ?? entry.actorUserId ?? '—'}
                      </span>
                      {entry.actorRole && (
                        <Badge variant="outline" className="text-[10px]">
                          {entry.actorRole}
                        </Badge>
                      )}
                      <span className="ml-auto text-[11px] text-muted-foreground whitespace-nowrap">
                        {format(parseISO(entry.createdAt), 'MMM d, yyyy HH:mm:ss')}
                      </span>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => toggleExpand(entry.id)}
                        disabled={!payload}
                      >
                        {isOpen
                          ? t('auditLogPage.hidePayload', 'Hide')
                          : t('auditLogPage.showPayload', 'Show')}
                      </Button>
                    </div>
                    {isOpen && payload && (
                      <pre className="overflow-x-auto border-t bg-background p-2 text-[11px] leading-relaxed">
                        {JSON.stringify(payload, null, 2)}
                      </pre>
                    )}
                  </li>
                );
              })}
            </ul>

            {totalPages > 1 && (
              <div className="flex items-center justify-between pt-1">
                <span className="text-xs text-muted-foreground">
                  {t('documentDetail.auditTrailPage', 'Page {{page}} of {{pages}}', {
                    page: page + 1, pages: totalPages,
                  })}
                </span>
                <div className="flex gap-1.5">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={page <= 0 || loading}
                    onClick={() => setPage((p) => Math.max(0, p - 1))}
                  >
                    <ChevronLeft className="mr-1 h-3 w-3" />
                    {t('common.previous', 'Previous')}
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={page >= totalPages - 1 || loading}
                    onClick={() => setPage((p) => p + 1)}
                  >
                    {t('common.next', 'Next')}
                    <ChevronRight className="ml-1 h-3 w-3" />
                  </Button>
                </div>
              </div>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}

/**
 * The icon used next to the audit trail in the document header.
 */
export function AuditTrailIcon() {
  return <History className="h-4 w-4" />;
}

export { Loader2 };
