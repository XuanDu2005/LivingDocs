import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  ArrowLeft,
  BarChart3,
  CheckCircle2,
  Database,
  FileText,
  GitBranch,
  RefreshCw,
  ShieldAlert,
  Users,
  XCircle,
} from 'lucide-react';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '../components/ui/select';
import { LoadingState, ErrorState } from '../components/ui/states';
import { describeError } from '../services/auth';
import { AdminAnalytics, adminAnalyticsApi } from '../services/adminAnalytics';

const DAY_OPTIONS = [7, 30, 90, 180, 365] as const;

/**
 * Admin-only analytics dashboard.
 *
 * <p>Aggregates platform-wide metrics — number of users, workspaces,
 * documents, drift alerts, indexing jobs, and audit events — into a
 * single snapshot that the admin can browse without picking a
 * workspace first.
 */
export default function AdminAnalyticsPage() {
  const { t } = useTranslation();
  const [days, setDays] = useState<number>(30);
  const [data, setData] = useState<AdminAnalytics | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      setData(await adminAnalyticsApi.get(days));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, [days]);

  if (loading && !data) return <LoadingState message={t('adminAnalytics.loading')} />;
  if (error && !data) return <ErrorState message={error} />;
  if (!data) return null;

  const driftSeverityEntries = Object.entries(data.driftSeverityBreakdown)
      .sort((a, b) => a[0].localeCompare(b[0]));
  const documentStatusEntries = Object.entries(data.documentStatusBreakdown)
      .sort((a, b) => a[0].localeCompare(b[0]));

  return (
    <div className="space-y-6">
      <div>
        <Button variant="ghost" size="sm" asChild>
          <Link to="/admin">
            <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}
          </Link>
        </Button>
        <h1 className="mt-2 text-2xl font-bold tracking-tight flex items-center gap-2">
          <BarChart3 className="h-5 w-5" /> {t('adminAnalytics.title')}
        </h1>
        <p className="text-sm text-muted-foreground">{t('adminAnalytics.subtitle')}</p>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <Select value={String(days)} onValueChange={(v) => setDays(Number(v))}>
          <SelectTrigger className="w-40"><SelectValue /></SelectTrigger>
          <SelectContent>
            {DAY_OPTIONS.map((d) => (
              <SelectItem key={d} value={String(d)}>
                {t('adminAnalytics.lastDays', { count: d })}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Button variant="outline" size="sm" onClick={() => void load()}>
          <RefreshCw className="mr-1 h-4 w-4" /> {t('common.refresh')}
        </Button>
        <span className="text-xs text-muted-foreground">
          {data.startDate} → {data.endDate}
        </span>
      </div>

      {/* Top counters */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          icon={Users}
          label={t('adminAnalytics.users')}
          value={data.totalUsers}
          sub={`${data.totalEnabledUsers} ${t('adminAnalytics.enabled')}`}
        />
        <StatCard
          icon={GitBranch}
          label={t('adminAnalytics.workspaces')}
          value={data.totalWorkspaces}
        />
        <StatCard
          icon={FileText}
          label={t('adminAnalytics.documents')}
          value={data.totalDocuments}
        />
        <StatCard
          icon={ShieldAlert}
          label={t('adminAnalytics.driftAlerts')}
          value={data.totalDriftAlerts}
          sub={`${data.openDriftAlerts} ${t('adminAnalytics.open')}`}
        />
        <StatCard
          icon={Database}
          label={t('adminAnalytics.indexJobs')}
          value={data.totalIndexJobs}
          sub={`${data.failedIndexJobs} ${t('adminAnalytics.failed')}`}
          subTone={data.failedIndexJobs > 0 ? 'destructive' : 'muted'}
        />
        <StatCard
          icon={CheckCircle2}
          label={t('adminAnalytics.auditEvents')}
          value={data.totalAuditEvents}
        />
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <TrendCard
          title={t('adminAnalytics.docGrowthTitle')}
          data={data.documentGrowth}
          color="bg-primary"
        />
        <TrendCard
          title={t('adminAnalytics.indexingTitle')}
          data={data.indexingActivity}
          color="bg-blue-500"
        />
        <TrendCard
          title={t('adminAnalytics.activeUsersTitle')}
          data={data.activeUsers}
          color="bg-emerald-500"
        />
        <BreakdownCard
          title={t('adminAnalytics.docStatusTitle')}
          entries={documentStatusEntries}
          total={data.totalDocuments}
        />
        <BreakdownCard
          title={t('adminAnalytics.driftSeverityTitle')}
          entries={driftSeverityEntries}
          total={data.openDriftAlerts}
        />
      </div>
    </div>
  );
}

interface StatCardProps {
  icon: typeof BarChart3;
  label: string;
  value: number;
  sub?: string;
  subTone?: 'muted' | 'destructive' | 'success';
}

function StatCard({ icon: Icon, label, value, sub, subTone = 'muted' }: StatCardProps) {
  return (
    <Card>
      <CardHeader className="pb-2 flex flex-row items-center justify-between space-y-0">
        <CardTitle className="text-sm font-medium text-muted-foreground">{label}</CardTitle>
        <Icon className="h-4 w-4 text-muted-foreground" />
      </CardHeader>
      <CardContent>
        <div className="text-3xl font-semibold tabular-nums">{value.toLocaleString()}</div>
        {sub && (
          <Badge variant={subTone} className="mt-1 text-[10px]">{sub}</Badge>
        )}
      </CardContent>
    </Card>
  );
}

interface TrendCardProps {
  title: string;
  data: { date: string; count: number }[];
  color: string;
}

function TrendCard({ title, data, color }: TrendCardProps) {
  const max = Math.max(1, ...data.map((d) => d.count));
  const points = data.slice(-30); // show last 30 days
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-medium">{title}</CardTitle>
        <CardDescription>
          {data.reduce((s, d) => s + d.count, 0).toLocaleString()} total
        </CardDescription>
      </CardHeader>
      <CardContent>
        <div className="flex h-32 items-end gap-px">
          {points.map((d) => {
            const h = Math.max(2, Math.round((d.count / max) * 120));
            return (
              <div
                key={d.date}
                className={`flex-1 ${color} rounded-t`}
                style={{ height: `${h}px` }}
                title={`${d.date}: ${d.count}`}
              />
            );
          })}
        </div>
        <div className="mt-2 flex justify-between text-[10px] text-muted-foreground">
          <span>{points[0]?.date}</span>
          <span>{points[points.length - 1]?.date}</span>
        </div>
      </CardContent>
    </Card>
  );
}

interface BreakdownCardProps {
  title: string;
  entries: [string, number][];
  total: number;
}

function BreakdownCard({ title, entries, total }: BreakdownCardProps) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm font-medium">{title}</CardTitle>
      </CardHeader>
      <CardContent>
        {entries.length === 0 ? (
          <div className="text-sm text-muted-foreground">No data.</div>
        ) : (
          <div className="space-y-2">
            {entries.map(([key, value]) => {
              const pct = total > 0 ? (value / total) * 100 : 0;
              return (
                <div key={key}>
                  <div className="flex items-center justify-between text-xs">
                    <span className="font-medium">{key}</span>
                    <span className="text-muted-foreground tabular-nums">
                      {value} ({pct.toFixed(1)}%)
                    </span>
                  </div>
                  <div className="mt-1 h-1.5 w-full rounded bg-muted">
                    <div
                      className="h-1.5 rounded bg-primary"
                      style={{ width: `${pct}%` }}
                    />
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

// Suppress unused import warning.
void XCircle;
