import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, ChevronLeft, ChevronRight, RefreshCw, ScrollText } from 'lucide-react';
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
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '../components/ui/select';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { describeError } from '../services/auth';
import {
  ChangeLogActorRole,
  ChangeLogDirection,
  ChangeLogEntry,
  ChangeLogSort,
  listChangeLog,
} from '../services/changeLog';
import { documentsApi, Document } from '../services/documents';
import { githubApi, Repository } from '../services/github';
import { format, parseISO } from 'date-fns';

const PAGE_SIZE = 50;

/** The roles a change can be attributed to, in the order the filter offers them. */
const ACTOR_ROLES: ChangeLogActorRole[] = ['AI', 'STAFF', 'DEVELOPER', 'MANAGER', 'SYSTEM'];

const SORT_OPTIONS: ChangeLogSort[] = ['CREATED_AT', 'ACTOR_ROLE', 'VERSION_NUMBER'];

const DIRECTIONS: ChangeLogDirection[] = ['DESC', 'ASC'];

/**
 * The workspace-level change log.
 *
 * <p>The requirement asks for a change log across the repository of a
 * workspace and for it to be filterable by role, document, repository
 * and date range. The per-document timeline cannot serve that need —
 * it is scoped to one document — so this page shows the whole
 * workspace and lets the reader narrow it down.
 *
 * <p>The filters are applied by the backend, not in the browser: the
 * log of a busy workspace is far larger than one page, so filtering
 * client-side would show the wrong answer.
 */
export default function ChangeLogPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { t } = useTranslation();

  const [entries, setEntries] = useState<ChangeLogEntry[]>([]);
  const [total, setTotal] = useState<number>(0);
  const [totalPages, setTotalPages] = useState<number>(0);
  const [page, setPage] = useState<number>(0);

  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  // The documents and the repositories of the workspace fill the two
  // select filters. They are loaded once per workspace.
  const [documents, setDocuments] = useState<Document[]>([]);
  const [repositories, setRepositories] = useState<Repository[]>([]);

  // The filter panel state.
  const [actorRole, setActorRole] = useState<ChangeLogActorRole | 'ALL'>('ALL');
  const [documentId, setDocumentId] = useState<string>('ALL');
  const [repositoryId, setRepositoryId] = useState<string>('ALL');
  const [fromDate, setFromDate] = useState<string>('');
  const [toDate, setToDate] = useState<string>('');
  const [sort, setSort] = useState<ChangeLogSort>('CREATED_AT');
  const [direction, setDirection] = useState<ChangeLogDirection>('DESC');

  // The filter that is actually applied to the request. It is kept
  // separate from the panel state so that changing a filter does not
  // fire a request per keystroke — the request runs when the reader
  // presses "Apply" (or Enter).
  const [applied, setApplied] = useState<{
    actorRole: ChangeLogActorRole | 'ALL';
    documentId: string;
    repositoryId: string;
    fromDate: string;
    toDate: string;
    sort: ChangeLogSort;
    direction: ChangeLogDirection;
  }>({
    actorRole: 'ALL',
    documentId: 'ALL',
    repositoryId: 'ALL',
    fromDate: '',
    toDate: '',
    sort: 'CREATED_AT',
    direction: 'DESC',
  });

  const load = useCallback(async () => {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    try {
      const result = await listChangeLog(
        workspaceId,
        {
          actorRole: applied.actorRole === 'ALL' ? null : applied.actorRole,
          documentId: applied.documentId === 'ALL' ? null : applied.documentId,
          repositoryId: applied.repositoryId === 'ALL' ? null : applied.repositoryId,
          // The date inputs are plain `YYYY-MM-DD`; the range is
          // inclusive, so the upper bound is pushed to the end of the
          // day the reader picked.
          from: applied.fromDate ? `${applied.fromDate}T00:00:00Z` : null,
          to: applied.toDate ? `${applied.toDate}T23:59:59Z` : null,
          sort: applied.sort,
          direction: applied.direction,
        },
        page,
        PAGE_SIZE,
      );
      setEntries(result.items ?? []);
      setTotal(result.total ?? 0);
      setTotalPages(result.totalPages ?? 0);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }, [workspaceId, applied, page]);

  useEffect(() => {
    void load();
  }, [load]);

  // The filter options of the workspace.
  useEffect(() => {
    if (!workspaceId) return;
    let cancelled = false;
    void (async () => {
      const [docs, repos] = await Promise.all([
        documentsApi.list(workspaceId).catch(() => [] as Document[]),
        githubApi.listRepositories(workspaceId).catch(() => [] as Repository[]),
      ]);
      if (cancelled) return;
      setDocuments(docs);
      setRepositories(repos);
    })();
    return () => {
      cancelled = true;
    };
  }, [workspaceId]);

  function applyFilters() {
    // Any change of the filter starts the log again from its first
    // page — page 3 of the old result set is meaningless for the new
    // one.
    setPage(0);
    setApplied({
      actorRole,
      documentId,
      repositoryId,
      fromDate,
      toDate,
      sort,
      direction,
    });
  }

  function resetFilters() {
    setActorRole('ALL');
    setDocumentId('ALL');
    setRepositoryId('ALL');
    setFromDate('');
    setToDate('');
    setSort('CREATED_AT');
    setDirection('DESC');
    setPage(0);
    setApplied({
      actorRole: 'ALL',
      documentId: 'ALL',
      repositoryId: 'ALL',
      fromDate: '',
      toDate: '',
      sort: 'CREATED_AT',
      direction: 'DESC',
    });
  }

  const hasActiveFilter = useMemo(
    () =>
      applied.actorRole !== 'ALL' ||
      applied.documentId !== 'ALL' ||
      applied.repositoryId !== 'ALL' ||
      applied.fromDate !== '' ||
      applied.toDate !== '',
    [applied],
  );

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <Button variant="ghost" size="sm" asChild>
            <Link to={`/workspaces/${workspaceId}`}>
              <ArrowLeft className="mr-1 h-4 w-4" />
              {t('changeLogPage.backToWorkspace')}
            </Link>
          </Button>
          <h1 className="mt-2 flex items-center gap-2 text-2xl font-bold tracking-tight">
            <ScrollText className="h-5 w-5" />
            {t('changeLogPage.title')}
          </h1>
          <p className="text-sm text-muted-foreground">
            {t('changeLogPage.subtitle')}
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={() => void load()}>
          <RefreshCw className="mr-1 h-4 w-4" />
          {t('changeLogPage.refresh')}
        </Button>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t('changeLogPage.filtersTitle')}</CardTitle>
          <CardDescription>{t('changeLogPage.filtersDesc')}</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground">
                {t('changeLogPage.filterActorRole')}
              </label>
              <Select
                value={actorRole}
                onValueChange={(v) => setActorRole(v as ChangeLogActorRole | 'ALL')}
              >
                <SelectTrigger>
                  <SelectValue placeholder={t('changeLogPage.anyRole')} />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="ALL">{t('changeLogPage.anyRole')}</SelectItem>
                  {ACTOR_ROLES.map((role) => (
                    <SelectItem key={role} value={role}>
                      {t(`changeLogPage.role.${role}`)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground">
                {t('changeLogPage.filterDocument')}
              </label>
              <Select value={documentId} onValueChange={setDocumentId}>
                <SelectTrigger>
                  <SelectValue placeholder={t('changeLogPage.anyDocument')} />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="ALL">{t('changeLogPage.anyDocument')}</SelectItem>
                  {documents.map((doc) => (
                    <SelectItem key={doc.id} value={doc.id}>
                      {doc.title}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground">
                {t('changeLogPage.filterRepository')}
              </label>
              <Select value={repositoryId} onValueChange={setRepositoryId}>
                <SelectTrigger>
                  <SelectValue placeholder={t('changeLogPage.anyRepository')} />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="ALL">{t('changeLogPage.anyRepository')}</SelectItem>
                  {repositories.map((repo) => (
                    <SelectItem key={repo.id} value={repo.id}>
                      {repo.fullName}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground">
                {t('changeLogPage.filterFrom')}
              </label>
              <Input
                type="date"
                value={fromDate}
                onChange={(e) => setFromDate(e.target.value)}
              />
            </div>

            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground">
                {t('changeLogPage.filterTo')}
              </label>
              <Input
                type="date"
                value={toDate}
                onChange={(e) => setToDate(e.target.value)}
              />
            </div>

            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground">
                {t('changeLogPage.filterSort')}
              </label>
              <div className="flex gap-2">
                <Select
                  value={sort}
                  onValueChange={(v) => setSort(v as ChangeLogSort)}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {SORT_OPTIONS.map((option) => (
                      <SelectItem key={option} value={option}>
                        {t(`changeLogPage.sort.${option}`)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <Select
                  value={direction}
                  onValueChange={(v) => setDirection(v as ChangeLogDirection)}
                >
                  <SelectTrigger className="w-28">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {DIRECTIONS.map((option) => (
                      <SelectItem key={option} value={option}>
                        {t(`changeLogPage.direction.${option}`)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>
          </div>

          <div className="mt-4 flex items-center gap-2">
            <Button size="sm" onClick={applyFilters}>
              {t('changeLogPage.apply')}
            </Button>
            <Button size="sm" variant="ghost" onClick={resetFilters}>
              {t('changeLogPage.reset')}
            </Button>
            {hasActiveFilter && (
              <Badge variant="muted" className="text-[10px]">
                {t('changeLogPage.filterActive')}
              </Badge>
            )}
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            {t('changeLogPage.entriesTitle')}
            <Badge variant="muted">{total}</Badge>
          </CardTitle>
          <CardDescription>{t('changeLogPage.entriesDesc')}</CardDescription>
        </CardHeader>
        <CardContent className="p-0">
          {loading ? (
            <LoadingState message={t('changeLogPage.loading')} />
          ) : error ? (
            <ErrorState message={error} />
          ) : entries.length === 0 ? (
            <div className="px-6 py-10">
              <EmptyState
                title={t('changeLogPage.emptyTitle')}
                description={t('changeLogPage.emptyDesc')}
              />
            </div>
          ) : (
            <>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-[160px]">{t('changeLogPage.colCreated')}</TableHead>
                    <TableHead>{t('changeLogPage.colDocument')}</TableHead>
                    <TableHead className="w-[90px]">{t('changeLogPage.colVersion')}</TableHead>
                    <TableHead>{t('changeLogPage.colActor')}</TableHead>
                    <TableHead>{t('changeLogPage.colSummary')}</TableHead>
                    <TableHead className="w-[110px]">{t('changeLogPage.colStatus')}</TableHead>
                    <TableHead className="w-[150px]">{t('changeLogPage.colSource')}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {entries.map((entry) => (
                    <TableRow key={entry.id}>
                      <TableCell className="whitespace-nowrap text-xs text-muted-foreground">
                        {format(parseISO(entry.createdAt), 'MMM d, yyyy HH:mm')}
                      </TableCell>
                      <TableCell>
                        {entry.documentId ? (
                          <Link
                            to={`/workspaces/${workspaceId}/documents/${entry.documentId}`}
                            className="text-sm font-medium hover:underline"
                          >
                            {entry.documentTitle ?? entry.documentSlug ?? '—'}
                          </Link>
                        ) : (
                          <span className="text-sm text-muted-foreground">—</span>
                        )}
                      </TableCell>
                      <TableCell className="text-xs">
                        v{entry.versionNumber}
                      </TableCell>
                      <TableCell className="text-xs">
                        <div className="flex items-center gap-1">
                          <span>
                            {entry.actorName ??
                              (entry.actorUserId
                                ? entry.actorUserId.slice(0, 8)
                                : '—')}
                          </span>
                          <Badge variant="muted" className="text-[10px]">
                            {t(`changeLogPage.role.${entry.actorRole}`)}
                          </Badge>
                        </div>
                      </TableCell>
                      <TableCell className="text-xs">
                        {entry.changeSummary ?? '—'}
                      </TableCell>
                      <TableCell>
                        <Badge variant="muted" className="text-[10px]">
                          {t(`changeLogPage.status.${entry.status}`)}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-[11px]">
                        {entry.sourceCommitSha && (
                          <code className="text-muted-foreground">
                            {entry.sourceCommitSha.slice(0, 7)}
                          </code>
                        )}
                        {entry.sourcePrId && (
                          <div className="text-muted-foreground">
                            {t('changeLogPage.pr')} {entry.sourcePrId.slice(0, 8)}
                          </div>
                        )}
                        {entry.sourceTicketRef && (
                          <div className="text-muted-foreground">
                            {entry.sourceTicketRef}
                          </div>
                        )}
                        {!entry.sourceCommitSha &&
                          !entry.sourcePrId &&
                          !entry.sourceTicketRef && '—'}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>

              {totalPages > 1 && (
                <div className="flex items-center justify-between border-t px-4 py-3">
                  <span className="text-xs text-muted-foreground">
                    {t('changeLogPage.pageInfo', { page: page + 1, totalPages })}
                  </span>
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={page <= 0 || loading}
                      onClick={() => setPage((p) => Math.max(0, p - 1))}
                    >
                      <ChevronLeft className="mr-1 h-4 w-4" />
                      {t('changeLogPage.prev')}
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={page + 1 >= totalPages || loading}
                      onClick={() => setPage((p) => p + 1)}
                    >
                      {t('changeLogPage.next')}
                      <ChevronRight className="ml-1 h-4 w-4" />
                    </Button>
                  </div>
                </div>
              )}
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
