import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  AlertTriangle,
  Code2,
  FileText,
  GitPullRequest,
  Radar,
} from 'lucide-react';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from './ui/card';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from './ui/table';
import { EmptyState, ErrorState, LoadingState } from './ui/states';
import { describeError } from '../services/auth';
import { getEntityImpact, ImpactAnalysis } from '../services/dependencyGraph';

/**
 * The impact panel of one code entity.
 *
 * <p>This is the panel a reviewer of a pull request actually wants:
 * "I changed this method — which documentation is now stale, what else
 * moves with it, and which pull requests are in flight?"
 *
 * <p>It is used in two places:
 *
 * <ul>
 *   <li>the dependency-graph page, where clicking an entity opens it,</li>
 *   <li>the document detail page, where the "Impact" tab shows the
 *       impact of every entity the document documents.</li>
 * </ul>
 */
interface ImpactPanelProps {
  workspaceId: string;
  entityId: string;
  /** Called when the panel should close (only used in the drawer case). */
  onClose?: () => void;
  className?: string;
}

export function ImpactPanel({ workspaceId, entityId, onClose, className }: ImpactPanelProps) {
  const { t } = useTranslation();

  const [impact, setImpact] = useState<ImpactAnalysis | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!workspaceId || !entityId) return;
    setLoading(true);
    setError(null);
    try {
      setImpact(await getEntityImpact(workspaceId, entityId));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }, [workspaceId, entityId]);

  useEffect(() => {
    void load();
  }, [load]);

  if (loading) {
    return <LoadingState message={t('dependencyGraph.impactLoading', 'Loading impact…')} />;
  }

  if (error) {
    return <ErrorState message={error} />;
  }

  if (!impact) {
    return (
      <EmptyState
        title={t('dependencyGraph.impactEmpty', 'No impact analysis available.')}
      />
    );
  }

  const sameFile = impact.relatedEntities.filter((e) => e.relation === 'SAME_FILE');
  const sameModule = impact.relatedEntities.filter((e) => e.relation === 'SAME_MODULE');

  return (
    <div className={className}>
      <Card>
        <CardHeader>
          <div className="flex items-start justify-between gap-3">
            <div>
              <CardTitle className="flex items-center gap-2 text-base">
                <Radar className="h-4 w-4" />
                {t('dependencyGraph.impactTitle', 'Impact analysis')}
              </CardTitle>
              <CardDescription className="mt-1">
                <code className="font-mono text-xs">{impact.entity.qualifiedName}</code>
              </CardDescription>
            </div>
            {onClose && (
              <Button size="sm" variant="ghost" onClick={onClose}>
                {t('common.close')}
              </Button>
            )}
          </div>
        </CardHeader>
        <CardContent className="space-y-4">
          {/* ── The entity itself ── */}
          <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
            <Badge variant="muted">{impact.entity.entityType}</Badge>
            <span className="font-mono">{impact.entity.filePath}</span>
            {impact.entity.startLine !== null && (
              <span>
                :{impact.entity.startLine}
                {impact.entity.endLine !== null && impact.entity.endLine !== impact.entity.startLine
                  ? `-${impact.entity.endLine}`
                  : ''}
              </span>
            )}
            {impact.entity.modulePath && (
              <Badge variant="muted" className="text-[10px]">
                {impact.entity.modulePath}
              </Badge>
            )}
          </div>

          {/* ── The affected documents ── */}
          <section className="space-y-2">
            <h4 className="flex items-center gap-1.5 text-sm font-medium">
              <FileText className="h-4 w-4 text-blue-500" />
              {t('dependencyGraph.affectedDocuments', 'Affected documentation')}
              <Badge variant="muted" className="text-[10px]">
                {impact.documents.length}
              </Badge>
            </h4>
            {impact.documents.length === 0 ? (
              <p className="text-xs text-muted-foreground">
                {t(
                  'dependencyGraph.noAffectedDocuments',
                  'No document documents this entity yet.',
                )}
              </p>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t('dependencyGraph.colDocument', 'Document')}</TableHead>
                    <TableHead>{t('dependencyGraph.colType', 'Type')}</TableHead>
                    <TableHead>{t('dependencyGraph.colLinkKind', 'Link')}</TableHead>
                    <TableHead>{t('dependencyGraph.colConfidence', 'Confidence')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {impact.documents.map((doc) => (
                    <TableRow key={doc.id}>
                      <TableCell>
                        <Link
                          to={`/workspaces/${workspaceId}/documents/${doc.id}`}
                          className="text-sm font-medium hover:underline"
                        >
                          {doc.title}
                        </Link>
                      </TableCell>
                      <TableCell>
                        <Badge variant="muted" className="text-[10px]">
                          {doc.docType}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        <Badge variant="muted" className="text-[10px]">
                          {doc.linkKind ?? '—'}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-xs">
                        {doc.confidence !== null
                          ? `${(doc.confidence * 100).toFixed(0)}%`
                          : '—'}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </section>

          {/* ── The drift alerts ── */}
          {impact.driftAlerts.length > 0 && (
            <section className="space-y-2">
              <h4 className="flex items-center gap-1.5 text-sm font-medium">
                <AlertTriangle className="h-4 w-4 text-amber-500" />
                {t('dependencyGraph.relatedDrift', 'Related drift alerts')}
                <Badge variant="muted" className="text-[10px]">
                  {impact.driftAlerts.length}
                </Badge>
              </h4>
              <ul className="space-y-1">
                {impact.driftAlerts.map((alert) => (
                  <li
                    key={alert.id}
                    className="flex items-center gap-2 rounded-md border p-2 text-xs"
                  >
                    <Badge
                      variant={
                        alert.severity === 'CRITICAL'
                          ? 'destructive'
                          : alert.severity === 'HIGH'
                            ? 'warning'
                            : 'muted'
                      }
                      className="text-[10px]"
                    >
                      {alert.severity}
                    </Badge>
                    <span className="min-w-0 flex-1 truncate">{alert.title}</span>
                    <Badge variant="muted" className="text-[10px]">
                      {alert.resolutionStatus}
                    </Badge>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {/* ── The open pull requests ── */}
          {impact.pullRequests.length > 0 && (
            <section className="space-y-2">
              <h4 className="flex items-center gap-1.5 text-sm font-medium">
                <GitPullRequest className="h-4 w-4 text-violet-500" />
                {t('dependencyGraph.openPullRequests', 'Open pull requests')}
                <Badge variant="muted" className="text-[10px]">
                  {impact.pullRequests.length}
                </Badge>
              </h4>
              <ul className="space-y-1">
                {impact.pullRequests.map((pr) => (
                  <li key={pr.id} className="flex items-center gap-2 text-xs">
                    <Badge variant="muted" className="text-[10px]">
                      #{pr.number}
                    </Badge>
                    {pr.htmlUrl ? (
                      <a
                        href={pr.htmlUrl}
                        target="_blank"
                        rel="noreferrer"
                        className="min-w-0 flex-1 truncate hover:underline"
                      >
                        {pr.title}
                      </a>
                    ) : (
                      <span className="min-w-0 flex-1 truncate">{pr.title}</span>
                    )}
                    {pr.authorLogin && (
                      <span className="text-muted-foreground">@{pr.authorLogin}</span>
                    )}
                  </li>
                ))}
              </ul>
            </section>
          )}

          {/* ── The related entities ── */}
          {(sameFile.length > 0 || sameModule.length > 0) && (
            <section className="space-y-2">
              <h4 className="flex items-center gap-1.5 text-sm font-medium">
                <Code2 className="h-4 w-4 text-emerald-500" />
                {t('dependencyGraph.relatedEntities', 'Entities affected alongside')}
                <Badge variant="muted" className="text-[10px]">
                  {impact.relatedEntities.length}
                </Badge>
              </h4>
              <ul className="space-y-1">
                {sameFile.map((entity) => (
                  <li key={entity.id} className="flex items-center gap-2 text-xs">
                    <Badge variant="muted" className="text-[10px]">
                      {t('dependencyGraph.sameFile', 'Same file')}
                    </Badge>
                    <span className="min-w-0 flex-1 truncate font-mono">
                      {entity.qualifiedName}
                    </span>
                    <span className="truncate text-muted-foreground">{entity.filePath}</span>
                  </li>
                ))}
                {sameModule.map((entity) => (
                  <li key={entity.id} className="flex items-center gap-2 text-xs">
                    <Badge variant="muted" className="text-[10px]">
                      {t('dependencyGraph.sameModule', 'Same module')}
                    </Badge>
                    <span className="min-w-0 flex-1 truncate font-mono">
                      {entity.qualifiedName}
                    </span>
                    <span className="truncate text-muted-foreground">{entity.filePath}</span>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

/**
 * The impact of every entity a document documents.
 *
 * <p>Used by the "Impact" tab of the document detail page: the reader
 * opens a document and wants to know which of its code entities are
 * fragile — which ones already carry drift alerts, and which pull
 * request is touching them right now.
 */
interface DocumentImpactPanelProps {
  workspaceId: string;
  /** The code entities the document links to. */
  entityIds: string[];
  className?: string;
}

export function DocumentImpactPanel({
  workspaceId,
  entityIds,
  className,
}: DocumentImpactPanelProps) {
  const { t } = useTranslation();

  const [impacts, setImpacts] = useState<Record<string, ImpactAnalysis>>({});
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    if (entityIds.length === 0) {
      setImpacts({});
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    void (async () => {
      const results = await Promise.allSettled(
        entityIds.map((id) => getEntityImpact(workspaceId, id)),
      );
      if (cancelled) return;
      const collected: Record<string, ImpactAnalysis> = {};
      let firstError: string | null = null;
      results.forEach((result, index) => {
        if (result.status === 'fulfilled') {
          collected[entityIds[index]] = result.value;
        } else if (!firstError) {
          firstError = describeError(result.reason);
        }
      });
      setImpacts(collected);
      // A partial failure is not fatal: the entities that could be
      // resolved are still worth showing.
      setError(firstError);
      setLoading(false);
    })();
    return () => {
      cancelled = true;
    };
  }, [workspaceId, entityIds]);

  if (loading) {
    return <LoadingState message={t('dependencyGraph.impactLoading', 'Loading impact…')} />;
  }

  if (entityIds.length === 0) {
    return (
      <EmptyState
        title={t(
          'dependencyGraph.impactNoEntities',
          'This document does not link any code entity yet.',
        )}
        description={t(
          'dependencyGraph.impactNoEntitiesDesc',
          'Link the entities the document describes in the "Links" tab — then this tab tells you which of them are drifting.',
        )}
      />
    );
  }

  const rows = Object.values(impacts);

  return (
    <div className={className}>
      {error && (
        <div className="mb-3">
          <ErrorState message={error} />
        </div>
      )}
      {rows.length === 0 ? (
        <EmptyState
          title={t('dependencyGraph.impactEmpty', 'No impact analysis available.')}
        />
      ) : (
        <div className="space-y-3">
          {rows.map((impact) => (
            <Card key={impact.entity.id}>
              <CardHeader className="pb-2">
                <CardTitle className="flex items-center gap-2 text-sm">
                  <Code2 className="h-4 w-4 text-emerald-500" />
                  <code className="font-mono">{impact.entity.qualifiedName}</code>
                </CardTitle>
                <CardDescription className="text-xs">
                  {impact.entity.filePath}
                  {impact.entity.startLine !== null ? `:${impact.entity.startLine}` : ''}
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-2">
                <div className="flex flex-wrap gap-3 text-xs">
                  <span className="flex items-center gap-1">
                    <FileText className="h-3 w-3 text-blue-500" />
                    {t('dependencyGraph.affectedDocuments', 'Affected documentation')}:{' '}
                    <strong>{impact.documents.length}</strong>
                  </span>
                  <span className="flex items-center gap-1">
                    <AlertTriangle className="h-3 w-3 text-amber-500" />
                    {t('dependencyGraph.relatedDrift', 'Related drift alerts')}:{' '}
                    <strong>{impact.driftAlerts.length}</strong>
                  </span>
                  <span className="flex items-center gap-1">
                    <Code2 className="h-3 w-3 text-emerald-500" />
                    {t('dependencyGraph.relatedEntities', 'Entities affected alongside')}:{' '}
                    <strong>{impact.relatedEntities.length}</strong>
                  </span>
                  <span className="flex items-center gap-1">
                    <GitPullRequest className="h-3 w-3 text-violet-500" />
                    {t('dependencyGraph.openPullRequests', 'Open pull requests')}:{' '}
                    <strong>{impact.pullRequests.length}</strong>
                  </span>
                </div>

                {impact.driftAlerts.length > 0 && (
                  <ul className="space-y-1">
                    {impact.driftAlerts.map((alert) => (
                      <li
                        key={alert.id}
                        className="flex items-center gap-2 rounded-md border p-2 text-xs"
                      >
                        <Badge
                          variant={
                            alert.severity === 'CRITICAL'
                              ? 'destructive'
                              : alert.severity === 'HIGH'
                                ? 'warning'
                                : 'muted'
                          }
                          className="text-[10px]"
                        >
                          {alert.severity}
                        </Badge>
                        <span className="min-w-0 flex-1 truncate">{alert.title}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}

export default ImpactPanel;
