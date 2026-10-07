import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, RefreshCw, Search } from 'lucide-react';
import { useTranslation } from 'react-i18next';
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
import { AuditLog, listAuditLogs } from '../services/auditLogs';
import { getWorkspace } from '../services/workspaces';
import { format, parseISO } from 'date-fns';

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
 * Manager-only view of the audit log for a single workspace. The page
 * shows the latest events first and lets the user filter by actor,
 * action or resource type. JSON payloads can be expanded inline so
 * reviewers can see exactly what changed without leaving the app.
 */
export default function WorkspaceAuditLogPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { t } = useTranslation();

  const [logs, setLogs] = useState<AuditLog[]>([]);
  const [workspaceName, setWorkspaceName] = useState<string>('');
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  const [filter, setFilter] = useState<string>('');
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  async function load() {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    try {
      const [list, ws] = await Promise.all([
        listAuditLogs(workspaceId),
        getWorkspace(workspaceId).catch(() => null),
      ]);
      setLogs(list);
      setWorkspaceName(ws?.name ?? '');
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaceId]);

  const filtered = useMemo(() => {
    const q = filter.trim().toLowerCase();
    if (!q) return logs;
    return logs.filter((l) =>
      [l.action, l.resourceType, l.resourceId, l.actorRole, l.payload]
        .filter(Boolean)
        .some((v) => String(v).toLowerCase().includes(q)),
    );
  }, [logs, filter]);

  function toggleExpand(id: string) {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  if (loading) return <LoadingState message={t('auditLogPage.loading')} />;
  if (error) return <ErrorState message={error} />;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <Button variant="ghost" size="sm" asChild>
            <Link to={`/workspaces/${workspaceId}`}>
              <ArrowLeft className="mr-1 h-4 w-4" />
              {workspaceName || t('auditLogPage.backToWorkspace')}
            </Link>
          </Button>
          <h1 className="mt-2 text-2xl font-bold tracking-tight">
            {t('auditLogPage.title')}
          </h1>
          <p className="text-sm text-muted-foreground">
            {t('auditLogPage.subtitle')}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <div className="relative">
            <Search className="absolute left-2 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
              placeholder={t('auditLogPage.searchPlaceholder')}
              className="w-64 pl-8"
            />
          </div>
          <Button variant="outline" size="sm" onClick={() => void load()}>
            <RefreshCw className="mr-1 h-4 w-4" />
            {t('auditLogPage.refresh')}
          </Button>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            {t('auditLogPage.entriesTitle')}
            <Badge variant="muted">{filtered.length}</Badge>
          </CardTitle>
          <CardDescription>{t('auditLogPage.entriesDesc')}</CardDescription>
        </CardHeader>
        <CardContent className="p-0">
          {filtered.length === 0 ? (
            <div className="px-6 py-10">
              <EmptyState
                title={t('auditLogPage.emptyTitle')}
                description={t('auditLogPage.emptyDesc')}
              />
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[170px]">{t('auditLogPage.colCreated')}</TableHead>
                  <TableHead>{t('auditLogPage.colAction')}</TableHead>
                  <TableHead>{t('auditLogPage.colResource')}</TableHead>
                  <TableHead>{t('auditLogPage.colActor')}</TableHead>
                  <TableHead className="w-[80px]">{t('auditLogPage.colPayload')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((log) => {
                  const isOpen = expanded.has(log.id);
                  const payload = safeParse(log.payload);
                  return (
                    <>
                      <TableRow key={log.id}>
                        <TableCell className="text-xs text-muted-foreground whitespace-nowrap">
                          {format(parseISO(log.createdAt), 'MMM d, yyyy HH:mm:ss')}
                        </TableCell>
                        <TableCell>
                          <code className="text-xs">{log.action}</code>
                        </TableCell>
                        <TableCell>
                          <div className="text-sm">{log.resourceType}</div>
                          {log.resourceId && (
                            <code className="text-[10px] text-muted-foreground break-all">
                              {log.resourceId}
                            </code>
                          )}
                        </TableCell>
                        <TableCell className="text-xs">
                          <div className="font-mono">
                            {log.actorUserId ? log.actorUserId.slice(0, 8) : '—'}
                          </div>
                          {log.actorRole && (
                            <Badge variant="muted" className="text-[10px] mt-1">
                              {log.actorRole}
                            </Badge>
                          )}
                        </TableCell>
                        <TableCell>
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => toggleExpand(log.id)}
                            disabled={!payload}
                          >
                            {isOpen
                              ? t('auditLogPage.hidePayload')
                              : t('auditLogPage.showPayload')}
                          </Button>
                        </TableCell>
                      </TableRow>
                      {isOpen && payload && (
                        <TableRow key={`${log.id}-payload`} className="bg-muted/30">
                          <TableCell colSpan={5} className="p-3">
                            <pre className="overflow-x-auto rounded bg-background p-3 text-[11px] leading-relaxed">
                              {JSON.stringify(payload, null, 2)}
                            </pre>
                          </TableCell>
                        </TableRow>
                      )}
                    </>
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