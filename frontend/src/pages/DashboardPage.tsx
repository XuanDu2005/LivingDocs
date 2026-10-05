import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
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
  label: string;
  value: string;
  hint: string;
  icon: typeof Activity;
  to: string;
}

export default function DashboardPage() {
  const { user } = useAuth();
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
      label: 'Workspaces',
      value: String(workspaces.length),
      hint: 'You own or belong to these',
      icon: Activity,
      to: '/workspaces',
    },
    {
      label: 'Documents',
      value: '—',
      hint: 'Aggregate across workspaces',
      icon: FileText,
      to: firstWs ? `/workspaces/${firstWs.id}/documents` : '/workspaces',
    },
    {
      label: 'Pending reviews',
      value: '—',
      hint: 'Versions waiting on a decision',
      icon: Shield,
      to: firstWs ? `/workspaces/${firstWs.id}/reviews` : '/workspaces',
    },
    {
      label: 'Drift alerts',
      value: '—',
      hint: 'Docs that may have fallen out of date',
      icon: AlertTriangle,
      to: firstWs ? `/workspaces/${firstWs.id}/drift` : '/workspaces',
    },
  ];

  return (
    <div className="space-y-6">
      <PageTitle
        title={`Welcome, ${user?.displayName ?? 'friend'}`}
        subtitle="A snapshot of your docs health and what's waiting on you."
      />

      {error && <ErrorState message={error} />}
      {loading && <LoadingState message="Loading dashboard…" />}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {stats.map((s) => (
          <Card key={s.label}>
            <CardHeader className="pb-2">
              <div className="flex items-center justify-between">
                <CardDescription>{s.label}</CardDescription>
                <s.icon className="h-4 w-4 text-muted-foreground" />
              </div>
            </CardHeader>
            <CardContent>
              <div className="text-3xl font-semibold tracking-tight">{s.value}</div>
              <p className="mt-1 text-xs text-muted-foreground">{s.hint}</p>
              <Button variant="link" size="sm" className="mt-1 h-auto p-0" asChild>
                <Link to={s.to}>
                  Open <TrendingUp className="ml-1 h-3 w-3" />
                </Link>
              </Button>
            </CardContent>
          </Card>
        ))}
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle className="text-base">Your workspaces</CardTitle>
            <CardDescription>Quick access to documentation surfaces.</CardDescription>
          </CardHeader>
          <CardContent>
            {workspaces.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                You don't belong to a workspace yet. Create one to get started.
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
                        {w.slug} · created {format(new Date(w.createdAt), 'MMM d, yyyy')}
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
                        <Link to={`/workspaces/${w.id}`}>Open</Link>
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
            <CardTitle className="text-base">Service health</CardTitle>
            <CardDescription>Backend + AI service status.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-2">
            <HealthRow label="Backend" status={health?.status} />
            <HealthRow label="AI service" status="see dashboard" />
            <Button variant="outline" className="w-full" asChild>
              <Link to={firstWs ? `/workspaces/${firstWs.id}/health` : '/workspaces'}>
                <Stethoscope className="mr-1 h-4 w-4" /> Open health dashboard
              </Link>
            </Button>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Getting started</CardTitle>
        </CardHeader>
        <CardContent>
          <ol className="space-y-2 text-sm text-muted-foreground">
            <li className="flex gap-2">
              <Badge variant="muted" className="h-6 w-6 justify-center">
                1
              </Badge>
              <span>
                Connect a GitHub repository in a workspace to enable code entity parsing.
              </span>
            </li>
            <li className="flex gap-2">
              <Badge variant="muted" className="h-6 w-6 justify-center">
                2
              </Badge>
              <span>
                Generate the first draft from a pull request using{' '}
                <Sparkles className="inline h-3 w-3" /> AI assist.
              </span>
            </li>
            <li className="flex gap-2">
              <Badge variant="muted" className="h-6 w-6 justify-center">
                3
              </Badge>
              <span>Route through review and watch drift alerts surface in real time.</span>
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
