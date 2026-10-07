import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  Activity,
  AlertTriangle,
  FileText,
  Shield,
  Sparkles,
  Stethoscope,
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
import { fetchBackendHealth, HealthStatus } from '../services/health';
import { format } from 'date-fns';

interface Stat {
  labelKey: string;
  value: string;
  hintKey: string;
  icon: typeof Activity;
  to: string;
}

export default function DashboardPage() {
  const { user } = useAuth();
  const { t } = useTranslation();
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [health, setHealth] = useState<HealthStatus | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

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
      } catch (err) {
        setError(describeError(err));
      } finally {
        setLoading(false);
      }
    }
    void load();
  }, []);

  const firstWs = workspaces[0];

  const stats: Stat[] = [
    {
      labelKey: 'dashboard.statWorkspaces',
      value: String(workspaces.length),
      hintKey: 'dashboard.statWorkspacesHint',
      icon: Activity,
      to: '/workspaces',
    },
    {
      labelKey: 'dashboard.statDocuments',
      value: '—',
      hintKey: 'dashboard.statDocumentsHint',
      icon: FileText,
      to: firstWs ? `/workspaces/${firstWs.id}/documents` : '/workspaces',
    },
    {
      labelKey: 'dashboard.statReviews',
      value: '—',
      hintKey: 'dashboard.statReviewsHint',
      icon: Shield,
      to: firstWs ? `/workspaces/${firstWs.id}/reviews` : '/workspaces',
    },
    {
      labelKey: 'dashboard.statDrift',
      value: '—',
      hintKey: 'dashboard.statDriftHint',
      icon: AlertTriangle,
      to: firstWs ? `/workspaces/${firstWs.id}/drift` : '/workspaces',
    },
  ];

  return (
    <div className="space-y-6">
      <PageTitle
        title={t('dashboard.welcome', { name: user?.displayName ?? t('dashboard.friend') })}
        subtitle={t('dashboard.subtitle')}
      />

      {error && <ErrorState message={error} />}
      {loading && <LoadingState message={t('common.loading')} />}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {stats.map((s) => (
          <Card key={s.labelKey}>
            <CardHeader className="pb-2">
              <div className="flex items-center justify-between">
                <CardDescription>{t(s.labelKey)}</CardDescription>
                <s.icon className="h-4 w-4 text-muted-foreground" />
              </div>
            </CardHeader>
            <CardContent>
              <div className="text-3xl font-semibold tracking-tight">{s.value}</div>
              <p className="mt-1 text-xs text-muted-foreground">{t(s.hintKey)}</p>
              <Button variant="link" size="sm" className="mt-1 h-auto p-0" asChild>
                <Link to={s.to}>
                  {t('common.open')} <TrendingUp className="ml-1 h-3 w-3" />
                </Link>
              </Button>
            </CardContent>
          </Card>
        ))}
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle className="text-base">{t('dashboard.yourWorkspaces')}</CardTitle>
            <CardDescription>{t('dashboard.yourWorkspacesDesc')}</CardDescription>
          </CardHeader>
          <CardContent>
            {workspaces.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                {t('dashboard.noWorkspaces')}
              </p>
            ) : (
              <div className="space-y-2">
                {workspaces.map((w) => (
                  <div
                    key={w.id}
                    className="flex items-center justify-between rounded-md border bg-muted/30 px-3 py-2"
                  >
                    <div>
                      <div className="text-sm font-medium">{w.name}</div>
                      <div className="text-xs text-muted-foreground">
                        {w.slug} · {t('dashboard.createdOn', { date: format(new Date(w.createdAt), 'MMM d, yyyy') })}
                      </div>
                    </div>
                    <div className="flex gap-1">
                      <Button size="sm" variant="ghost" asChild>
                        <Link to={`/workspaces/${w.id}/documents`}>
                          <FileText className="h-4 w-4" />
                        </Link>
                      </Button>
                      <Button size="sm" variant="ghost" asChild>
                        <Link to={`/workspaces/${w.id}/drift`}>
                          <AlertTriangle className="h-4 w-4" />
                        </Link>
                      </Button>
                      <Button size="sm" variant="outline" asChild>
                        <Link to={`/workspaces/${w.id}`}>{t('common.open')}</Link>
                      </Button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">{t('dashboard.serviceHealth')}</CardTitle>
            <CardDescription>{t('dashboard.serviceHealthDesc')}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-2">
            <HealthRow label={t('dashboard.backend')} status={health?.status} />
            <HealthRow label={t('dashboard.aiService')} status="see dashboard" />
            <Button variant="outline" className="w-full" asChild>
              <Link to={firstWs ? `/workspaces/${firstWs.id}/health` : '/workspaces'}>
                <Stethoscope className="mr-1 h-4 w-4" /> {t('dashboard.openHealthDashboard')}
              </Link>
            </Button>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t('dashboard.gettingStarted')}</CardTitle>
        </CardHeader>
        <CardContent>
          <ol className="space-y-2 text-sm text-muted-foreground">
            <li className="flex gap-2">
              <Badge variant="muted" className="h-6 w-6 justify-center">1</Badge>
              <span>{t('dashboard.step1')}</span>
            </li>
            <li className="flex gap-2">
              <Badge variant="muted" className="h-6 w-6 justify-center">2</Badge>
              <span>{t('dashboard.step2')} <Sparkles className="inline h-3 w-3" /> {t('dashboard.aiAssist')}</span>
            </li>
            <li className="flex gap-2">
              <Badge variant="muted" className="h-6 w-6 justify-center">3</Badge>
              <span>{t('dashboard.step3')}</span>
            </li>
          </ol>
        </CardContent>
      </Card>
    </div>
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