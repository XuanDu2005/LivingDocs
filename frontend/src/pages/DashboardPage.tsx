import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  Activity,
  AlertTriangle,
  ArrowRight,
  Bell,
  FileText,
  GitBranch,
  Plus,
  Search,
  Shield,
  Sparkles,
  TrendingUp,
} from 'lucide-react';
import { PageTitle } from '../components/PageTitle';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Button } from '../components/ui/button';
import { LoadingState, ErrorState } from '../components/ui/states';
import { useAuth } from '../contexts/AuthContext';
import { describeError } from '../services/auth';
import { listWorkspaces, Workspace } from '../services/workspaces';
import { documentsApi, Document } from '../services/documents';
import { driftApi, reviewsApi, DriftAlert, DocumentReview } from '../services/drift';
import { fetchBackendHealth, HealthStatus } from '../services/health';
import { notificationsApi } from '../services/notifications';
import { codeApi } from '../services/knowledge';
import { formatDistanceToNow } from 'date-fns';

interface StatItem {
  label: string;
  value: number | string | null;
  hint: string;
  icon: typeof Activity;
  to: string;
}

const LAST_WS_KEY = 'livingdocs:last_workspace_id';

export default function DashboardPage() {
  const { user } = useAuth();
  const { t } = useTranslation();

  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [health, setHealth] = useState<HealthStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [docCount, setDocCount] = useState<number | null>(null);
  const [pendingReviews, setPendingReviews] = useState<number | null>(null);
  const [openDrifts, setOpenDrifts] = useState<number | null>(null);
  const [recentDocs, setRecentDocs] = useState<Document[]>([]);
  const [recentDrifts, setRecentDrifts] = useState<DriftAlert[]>([]);
  const [recentReviews, setRecentReviews] = useState<DocumentReview[]>([]);

  // Notification + coverage stats
  const [unreadNotifications, setUnreadNotifications] = useState<number>(0);
  const [totalEntities, setTotalEntities] = useState<number>(0);
  const [documentedEntities, setDocumentedEntities] = useState<number>(0);
  const [recentAiVersions, setRecentAiVersions] = useState<{ docTitle: string; versionNumber: number; createdAt: string; docId: string }[]>([]);

  useEffect(() => {
    async function load() {
      setLoading(true);
      setError(null);
      try {
        const [wsList, hs] = await Promise.all([
          listWorkspaces(),
          fetchBackendHealth().catch(() => null),
        ]);
        setWorkspaces(wsList);
        setHealth(hs);

        // Pick a workspace to surface activity from. Prefer the last-used
        // workspace id (set by WorkspaceSwitcher) so the dashboard feels
        // personalised across visits.
        const last = readLastWorkspace();
        const activeWs = wsList.find((w) => w.id === last) ?? wsList[0];

        loadAggregates(wsList);
        loadRecentActivity(activeWs);
        loadCoverageStats(activeWs);
        loadRecentAiActivity(activeWs);
      } catch (err) {
        setError(describeError(err));
      } finally {
        setLoading(false);
      }
    }
    void load();
  }, []);

  async function loadAggregates(wsList: Workspace[]) {
    if (wsList.length === 0) {
      setDocCount(0);
      setOpenDrifts(0);
      return;
    }
    try {
      const [docCounts, driftCounts] = await Promise.all([
        Promise.all(
          wsList.map((w) => documentsApi.list(w.id).then((d) => d.length).catch(() => 0)),
        ),
        Promise.all(
          wsList.map((w) =>
            driftApi.list(w.id, { status: 'OPEN' }).then((a) => a.length).catch(() => 0),
          ),
        ),
      ]);
      setDocCount(docCounts.reduce((a, b) => a + b, 0));
      setOpenDrifts(driftCounts.reduce((a, b) => a + b, 0));
    } catch {
      /* keep nulls = "—" */
    }

    // Load notification count (independent of workspace)
    try {
      const count = await notificationsApi.unreadCount();
      setUnreadNotifications(count);
    } catch {
      /* ignore */
    }
  }

  async function loadCoverageStats(ws: Workspace | undefined) {
    if (!ws) return;
    try {
      // Get all repos in workspace to count entities
      const { githubApi } = await import('../services/github');
      const repos = await githubApi.listRepositories(ws.id).catch(() => []);
      let total = 0;
      let documented = 0;

      // Count entities from all repos
      const entityCounts = await Promise.allSettled(
        repos.map((r) => codeApi.listEntities(ws.id, r.id).then((entities) => {
          const hasDocs = entities.filter((e) => e.docstring && e.docstring.trim().length > 0).length;
          return { total: entities.length, documented: hasDocs };
        }).catch(() => ({ total: 0, documented: 0 }))),
      );

      for (const result of entityCounts) {
        if (result.status === 'fulfilled') {
          total += result.value.total;
          documented += result.value.documented;
        }
      }

      setTotalEntities(total);
      setDocumentedEntities(documented);
    } catch {
      /* ignore */
    }
  }

  async function loadRecentAiActivity(ws: Workspace | undefined) {
    if (!ws) return;
    try {
      const docs = await documentsApi.list(ws.id).catch(() => []);
      // Get recent AI-generated versions from each doc
      const aiVersions: { docTitle: string; versionNumber: number; createdAt: string; docId: string }[] = [];

      // Sample first 10 docs to avoid too many API calls
      const sampleDocs = docs.slice(0, 10);

      for (const doc of sampleDocs) {
        try {
          const timeline = await documentsApi.timeline(doc.id).catch(() => []);
          // Find AI versions
          const aiVersion = timeline.find((v) => v.actorRole === 'AI');
          if (aiVersion) {
            aiVersions.push({
              docTitle: doc.title,
              versionNumber: aiVersion.versionNumber,
              createdAt: aiVersion.createdAt,
              docId: doc.id,
            });
          }
        } catch {
          /* ignore */
        }
      }

      // Sort by most recent
      aiVersions.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
      setRecentAiVersions(aiVersions.slice(0, 5));
    } catch {
      /* ignore */
    }
  }

  async function loadRecentActivity(ws: Workspace | undefined) {
    try {
      const reviews = await reviewsApi.myReviews().catch(() => [] as DocumentReview[]);
      const pending = reviews.filter((r) => r.decision !== 'APPROVED');
      setPendingReviews(pending.length);
      setRecentReviews(pending.slice(0, 5));
    } catch {
      /* ignore */
    }
    if (!ws) return;
    try {
      const [docs, drifts] = await Promise.all([
        documentsApi.list(ws.id).catch(() => [] as Document[]),
        driftApi.list(ws.id, { status: 'OPEN' }).catch(() => [] as DriftAlert[]),
      ]);
      setRecentDocs(
        [...docs]
          .sort((a, b) => +new Date(b.updatedAt) - +new Date(a.updatedAt))
          .slice(0, 5),
      );
      setRecentDrifts(
        [...drifts]
          .sort((a, b) => +new Date(b.detectedAt) - +new Date(a.detectedAt))
          .slice(0, 5),
      );
    } catch {
      /* ignore */
    }
  }

  const activeWs = useMemo(() => {
    const last = readLastWorkspace();
    return workspaces.find((w) => w.id === last) ?? workspaces[0];
  }, [workspaces]);

  const stats: StatItem[] = [
    {
      label: t('dashboard.statWorkspaces'),
      value: workspaces.length,
      hint: t('dashboard.statWorkspacesHint'),
      icon: Activity,
      to: '/workspaces',
    },
    {
      label: t('dashboard.statDocuments'),
      value: docCount,
      hint: t('dashboard.statDocumentsHint'),
      icon: FileText,
      to: activeWs ? `/workspaces/${activeWs.id}/documents` : '/workspaces',
    },
    {
      label: t('dashboard.statReviews'),
      value: pendingReviews,
      hint: t('dashboard.statReviewsHint'),
      icon: Shield,
      to: activeWs ? `/workspaces/${activeWs.id}/reviews` : '/workspaces',
    },
    {
      label: t('dashboard.statDrift'),
      value: openDrifts,
      hint: t('dashboard.statDriftHint'),
      icon: AlertTriangle,
      to: activeWs ? `/workspaces/${activeWs.id}/drift` : '/workspaces',
    },
    {
      label: t('dashboard.statNotifications'),
      value: unreadNotifications > 0 ? unreadNotifications : null,
      hint: t('dashboard.statNotificationsHint'),
      icon: Bell,
      to: '#notifications',
    },
    {
      label: t('dashboard.statCoverage'),
      value: totalEntities > 0
        ? `${Math.round((documentedEntities / totalEntities) * 100)}%`
        : null,
      hint: t('dashboard.statCoverageHint', {
        documented: documentedEntities,
        total: totalEntities,
      }),
      icon: Sparkles,
      to: activeWs ? `/workspaces/${activeWs.id}/knowledge` : '/workspaces',
    },
  ];

  return (
    <div className="space-y-6">
      <PageTitle
        title={t('dashboard.welcome', { name: user?.displayName ?? t('dashboard.friend') })}
        subtitle={t('dashboard.subtitle')}
        action={
          activeWs ? (
            <Button asChild>
              <Link to={`/workspaces/${activeWs.id}/documents`}>
                <Plus className="mr-1 h-4 w-4" />
                {t('dashboard.newDocument', 'New document')}
              </Link>
            </Button>
          ) : (
            <Button asChild>
              <Link to="/workspaces">
                <Plus className="mr-1 h-4 w-4" />
                {t('dashboard.newWorkspace', 'New workspace')}
              </Link>
            </Button>
          )
        }
      />

      {error && <ErrorState message={error} />}
      {loading && <LoadingState message={t('common.loading')} />}

      {/* Stat cards */}
      <div className="grid gap-4 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-6">
        {stats.map((s) => (
          <Link
            key={s.label}
            to={s.to === '#notifications' ? '#' : s.to}
            onClick={s.to === '#notifications' ? () => {
              // Scroll to notification bell in sidebar
              const bell = document.querySelector('[aria-label*="notification" i], button:has(svg[class*="Bell"])');
              if (bell instanceof HTMLElement) bell.click();
            } : undefined}
            className="group rounded-lg border bg-card p-4 transition-shadow hover:shadow-md"
          >
            <div className="flex items-center justify-between">
              <span className="text-sm text-muted-foreground">{s.label}</span>
              <s.icon className="h-4 w-4 text-muted-foreground" />
            </div>
            <div className="mt-2 text-3xl font-semibold tracking-tight">
              {s.value === null ? '—' : s.value}
            </div>
            <p className="mt-1 line-clamp-1 text-xs text-muted-foreground">{s.hint}</p>
            <ArrowRight className="mt-2 h-4 w-4 text-muted-foreground transition-transform group-hover:translate-x-0.5" />
          </Link>
        ))}
      </div>

      {/* Quick actions */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <QuickAction
          to={activeWs ? `/workspaces/${activeWs.id}/documents` : '/workspaces'}
          icon={Plus}
          title={t('dashboard.qaNewDoc', 'Create document')}
          desc={t('dashboard.qaNewDocDesc', 'Start a new document or AI draft')}
        />
        <QuickAction
          to={activeWs ? `/workspaces/${activeWs.id}/repositories` : '/workspaces'}
          icon={GitBranch}
          title={t('dashboard.qaConnectRepo', 'Connect repository')}
          desc={t('dashboard.qaConnectRepoDesc', 'Link a GitHub repository')}
        />
        <QuickAction
          to={activeWs ? `/workspaces/${activeWs.id}/knowledge` : '/workspaces'}
          icon={Search}
          title={t('dashboard.qaSearch', 'Search knowledge')}
          desc={t('dashboard.qaSearchDesc', 'Semantic search across docs and code')}
        />
        <QuickAction
          to={activeWs ? `/workspaces/${activeWs.id}/drift` : '/workspaces'}
          icon={AlertTriangle}
          title={t('dashboard.qaDrift', 'Review drift')}
          desc={t('dashboard.qaDriftDesc', 'Triage open drift alerts')}
        />
      </div>

      {/* Activity + suggestions */}
      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle className="text-base">{t('dashboard.activity', 'Recent activity')}</CardTitle>
            <CardDescription>
              {activeWs
                ? t('dashboard.activityDesc', { name: activeWs.name })
                : t('dashboard.activityDescEmpty', 'Pick a workspace to see activity')}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <ActivitySection
              title={t('dashboard.recentDocs', 'Recent documents')}
              empty={t('dashboard.recentDocsEmpty', 'No documents yet')}
              items={recentDocs.map((d) => ({
                key: d.id,
                primary: d.title,
                secondary: `${d.docType} · ${formatDistanceToNow(new Date(d.updatedAt), { addSuffix: true })}`,
                to: `/workspaces/${activeWs?.id ?? ''}/documents/${d.id}`,
              }))}
            />
            <ActivitySection
              title={t('dashboard.recentDrift', 'Open drift alerts')}
              empty={t('dashboard.recentDriftEmpty', 'No open drift')}
              items={recentDrifts.map((a) => ({
                key: a.id,
                primary: a.title ?? a.driftKind,
                secondary: `${a.severity} · ${formatDistanceToNow(new Date(a.detectedAt), { addSuffix: true })}`,
                to: `/workspaces/${activeWs?.id ?? ''}/drift`,
                badge: a.severity,
              }))}
            />
            <ActivitySection
              title={t('dashboard.recentReviews', 'Pending reviews')}
              empty={t('dashboard.recentReviewsEmpty', 'Nothing pending')}
              items={recentReviews.map((r) => ({
                key: r.id,
                primary: r.documentVersionId,
                secondary: formatDistanceToNow(new Date(r.decidedAt), { addSuffix: true }),
                to: `/workspaces/${activeWs?.id ?? ''}/reviews`,
              }))}
            />
            {recentAiVersions.length > 0 && (
              <ActivitySection
                title={t('dashboard.recentAiActivity', 'Recent AI activity')}
                empty={t('dashboard.recentAiActivityEmpty', 'No AI activity yet')}
                items={recentAiVersions.map((v) => ({
                  key: `${v.docId}-${v.versionNumber}`,
                  primary: v.docTitle,
                  secondary: `v${v.versionNumber} · ${formatDistanceToNow(new Date(v.createdAt), { addSuffix: true })}`,
                  to: `/workspaces/${activeWs?.id ?? ''}/documents/${v.docId}`,
                  badge: 'AI',
                }))}
              />
            )}
          </CardContent>
        </Card>

        <div className="space-y-4">
          <SuggestionsCard
            hasWorkspaces={workspaces.length > 0}
            activeWsName={activeWs?.name}
            docCount={docCount ?? 0}
            reviewCount={pendingReviews ?? 0}
            driftCount={openDrifts ?? 0}
            healthOk={health?.status === 'UP'}
          />
          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t('dashboard.healthTitle', 'Service health')}</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2 text-sm">
              <HealthRow label={t('dashboard.backend')} status={health?.status} />
              <p className="text-xs text-muted-foreground">
                {t('dashboard.healthDesc', 'Powered by the platform health endpoint.')}
              </p>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}

function readLastWorkspace(): string | null {
  try {
    return localStorage.getItem(LAST_WS_KEY);
  } catch {
    return null;
  }
}

function QuickAction({
  to,
  icon: Icon,
  title,
  desc,
}: {
  to: string;
  icon: typeof Activity;
  title: string;
  desc: string;
}) {
  return (
    <Link
      to={to}
      className="flex items-start gap-3 rounded-lg border bg-card p-3 transition-colors hover:bg-muted/40"
    >
      <div className="rounded-md bg-primary/10 p-2 text-primary">
        <Icon className="h-4 w-4" />
      </div>
      <div className="min-w-0">
        <div className="text-sm font-medium">{title}</div>
        <div className="truncate text-xs text-muted-foreground">{desc}</div>
      </div>
    </Link>
  );
}

interface ActivityRow {
  key: string;
  primary: string;
  secondary: string;
  to: string;
  badge?: string;
}

function ActivitySection({ title, empty, items }: { title: string; empty: string; items: ActivityRow[] }) {
  return (
    <div>
      <div className="mb-1 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        {title}
      </div>
      {items.length === 0 ? (
        <p className="text-sm text-muted-foreground">{empty}</p>
      ) : (
        <ul className="space-y-1">
          {items.map((it) => (
            <li key={it.key}>
              <Link
                to={it.to}
                className="flex items-center justify-between gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-muted/40"
              >
                <div className="min-w-0">
                  <div className="truncate font-medium">{it.primary}</div>
                  <div className="truncate text-xs text-muted-foreground">{it.secondary}</div>
                </div>
                {it.badge && (
                  <Badge
                    variant={it.badge === 'AI' ? 'info' : it.badge === 'CRITICAL' ? 'destructive' : it.badge === 'HIGH' ? 'warning' : 'muted'}
                    className="shrink-0 text-[10px]"
                  >
                    {it.badge}
                  </Badge>
                )}
                <ArrowRight className="h-3 w-3 shrink-0 text-muted-foreground" />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function SuggestionsCard({
  hasWorkspaces,
  activeWsName,
  docCount,
  reviewCount,
  driftCount,
  healthOk,
}: {
  hasWorkspaces: boolean;
  activeWsName?: string;
  docCount: number;
  reviewCount: number;
  driftCount: number;
  healthOk: boolean;
}) {
  const { t } = useTranslation();
  if (!hasWorkspaces) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t('dashboard.gettingStarted', 'Get started')}</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">
            {t('dashboard.noWorkspaces', 'You don\'t belong to a workspace yet. Create one to get started.')}
          </p>
          <Button asChild className="mt-3 w-full">
            <Link to="/workspaces">
              <Plus className="mr-1 h-4 w-4" />
              {t('workspaces.newCta', 'New workspace')}
            </Link>
          </Button>
        </CardContent>
      </Card>
    );
  }
  const suggestions: { title: string; desc: string; cta?: { to: string; label: string } }[] = [];
  if (docCount === 0) {
    suggestions.push({
      title: t('dashboard.sugFirstDoc', 'Create your first document'),
      desc: t('dashboard.sugFirstDocDesc', 'Pick a template and generate an AI draft in one click.'),
    });
  }
  if (reviewCount > 0) {
    suggestions.push({
      title: t('dashboard.sugReviews', { count: reviewCount }),
      desc: t('dashboard.sugReviewsDesc', 'Approve or request changes on pending versions.'),
    });
  }
  if (driftCount > 0) {
    suggestions.push({
      title: t('dashboard.sugDrift', { count: driftCount }),
      desc: t('dashboard.sugDriftDesc', 'Code may have changed without the docs following.'),
    });
  }
  if (!healthOk) {
    suggestions.push({
      title: t('dashboard.sugHealth', 'Backend reported a non-UP status'),
      desc: t('dashboard.sugHealthDesc', 'Some data may be stale until the service recovers.'),
    });
  }
  if (suggestions.length === 0) {
    suggestions.push({
      title: t('dashboard.sugAllGood', 'You\'re all caught up'),
      desc: t('dashboard.sugAllGoodDesc', { name: activeWsName ?? '' }),
    });
  }
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">{t('dashboard.suggestions', 'Suggestions')}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        {suggestions.map((s, i) => (
          <div key={i} className="rounded-md border bg-muted/30 p-3 text-sm">
            <div className="flex items-center gap-2 font-medium">
              <Sparkles className="h-3.5 w-3.5 text-primary" />
              {s.title}
            </div>
            <p className="mt-1 text-xs text-muted-foreground">{s.desc}</p>
          </div>
        ))}
        <Button asChild variant="outline" className="w-full">
          <Link to="/workspaces">
            <TrendingUp className="mr-1 h-4 w-4" />
            {t('dashboard.browseAll', 'Browse all workspaces')}
          </Link>
        </Button>
      </CardContent>
    </Card>
  );
}

function HealthRow({ label, status }: { label: string; status?: string }) {
  const ok = status === 'UP';
  return (
    <div className="flex items-center justify-between rounded-md border bg-muted/20 px-3 py-2 text-sm">
      <span>{label}</span>
      <Badge variant={ok ? 'success' : 'muted'}>
        <span
          className={`mr-1.5 inline-block h-1.5 w-1.5 rounded-full ${
            ok ? 'bg-success-foreground' : 'bg-muted-foreground'
          }`}
        />
        {status ?? 'unknown'}
      </Badge>
    </div>
  );
}
