import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, BarChart3, FileText, Users, AlertTriangle, Database, TrendingUp } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '../components/ui/select';
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  BarChart, Bar, Legend,
} from 'recharts';
import { LoadingState, ErrorState } from '../components/ui/states';
import { describeError } from '../services/auth';
import { analyticsApi, AnalyticsResponse } from '../services/analytics';
import { format, parseISO } from 'date-fns';

const SEVERITY_COLORS: Record<string, string> = {
  CRITICAL: '#ef4444', HIGH: '#f97316', MEDIUM: '#f59e0b', LOW: '#22c55e',
};

export default function AnalyticsDashboardPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { t } = useTranslation();
  const [data, setData] = useState<AnalyticsResponse | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [days, setDays] = useState<number>(30);

  async function load() {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    try {
      const d = await analyticsApi.get(workspaceId, days);
      setData(d);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, [workspaceId, days]);

  if (!workspaceId) return null;
  if (loading) return <LoadingState message="Loading analytics..." />;
  if (error) return <ErrorState message={error} />;
  if (!data) return null;

  const totalDocs = Object.values(data.documentStatusBreakdown).reduce((a, b) => a + b, 0);
  const totalDrift = Object.values(data.driftSeverityBreakdown).reduce((a, b) => a + b, 0);
  const totalIndexed = data.indexingActivity.reduce((a, b) => a + b.count, 0);
  const activeToday = data.activeUsers[data.activeUsers.length - 1]?.count ?? 0;

  return (
    <div className="space-y-6">
      <Button variant="ghost" size="sm" asChild>
        <Link to={`/workspaces/${workspaceId}`}>
          <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}
        </Link>
      </Button>

      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight flex items-center gap-2">
            <BarChart3 className="h-5 w-5" /> Analytics Dashboard
          </h1>
          <p className="text-sm text-muted-foreground">
            Workspace-level insights over the last {days} days.
          </p>
        </div>
        <Select value={days.toString()} onValueChange={(v) => setDays(Number(v))}>
          <SelectTrigger className="w-32">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="7">7 days</SelectItem>
            <SelectItem value="30">30 days</SelectItem>
            <SelectItem value="90">90 days</SelectItem>
            <SelectItem value="180">180 days</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Card>
          <CardContent className="pt-4">
            <div className="flex items-center gap-2">
              <FileText className="h-5 w-5 text-primary" />
              <div>
                <div className="text-2xl font-bold">{totalDocs}</div>
                <div className="text-xs text-muted-foreground">Total documents</div>
              </div>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-4">
            <div className="flex items-center gap-2">
              <Users className="h-5 w-5 text-info" />
              <div>
                <div className="text-2xl font-bold">{activeToday}</div>
                <div className="text-xs text-muted-foreground">Active users today</div>
              </div>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-4">
            <div className="flex items-center gap-2">
              <AlertTriangle className="h-5 w-5 text-orange-500" />
              <div>
                <div className="text-2xl font-bold">{totalDrift}</div>
                <div className="text-xs text-muted-foreground">Open drift alerts</div>
              </div>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-4">
            <div className="flex items-center gap-2">
              <Database className="h-5 w-5 text-green-500" />
              <div>
                <div className="text-2xl font-bold">{totalIndexed}</div>
                <div className="text-xs text-muted-foreground">Indexing jobs</div>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Charts */}
      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2">
              <TrendingUp className="h-4 w-4" /> Document Growth
            </CardTitle>
            <CardDescription>New documents created per day</CardDescription>
          </CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={200}>
              <LineChart data={data.documentGrowth.map((d) => ({
                ...d,
                name: format(parseISO(d.date), 'MMM d'),
              }))}>
                <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                <XAxis dataKey="name" tick={{ fontSize: 10 }} />
                <YAxis tick={{ fontSize: 10 }} />
                <Tooltip />
                <Line type="monotone" dataKey="count" stroke="var(--primary)" strokeWidth={2} dot={false} />
              </LineChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2">
              <Users className="h-4 w-4" /> Active Users
            </CardTitle>
            <CardDescription>Distinct users active per day</CardDescription>
          </CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={200}>
              <LineChart data={data.activeUsers.map((d) => ({
                ...d,
                name: format(parseISO(d.date), 'MMM d'),
              }))}>
                <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                <XAxis dataKey="name" tick={{ fontSize: 10 }} />
                <YAxis tick={{ fontSize: 10 }} />
                <Tooltip />
                <Line type="monotone" dataKey="count" stroke="var(--info)" strokeWidth={2} dot={false} />
              </LineChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">Drift Trends</CardTitle>
            <CardDescription>New drift alerts per day</CardDescription>
          </CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={200}>
              <BarChart data={data.driftTrends.map((d) => ({
                ...d,
                name: format(parseISO(d.date), 'MMM d'),
              }))}>
                <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                <XAxis dataKey="name" tick={{ fontSize: 10 }} />
                <YAxis tick={{ fontSize: 10 }} />
                <Tooltip />
                <Bar dataKey="count" fill="#f97316" />
              </BarChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">Indexing Activity</CardTitle>
            <CardDescription>Knowledge-base indexing jobs per day</CardDescription>
          </CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={200}>
              <BarChart data={data.indexingActivity.map((d) => ({
                ...d,
                name: format(parseISO(d.date), 'MMM d'),
              }))}>
                <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                <XAxis dataKey="name" tick={{ fontSize: 10 }} />
                <YAxis tick={{ fontSize: 10 }} />
                <Tooltip />
                <Bar dataKey="count" fill="var(--primary)" />
              </BarChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>
      </div>

      {/* Breakdowns */}
      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Document Status Breakdown</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-2">
              {Object.entries(data.documentStatusBreakdown).map(([status, count]) => (
                <div key={status} className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <Badge variant="muted">{status}</Badge>
                  </div>
                  <span className="text-sm font-medium">{count}</span>
                </div>
              ))}
              {Object.keys(data.documentStatusBreakdown).length === 0 && (
                <p className="text-sm text-muted-foreground">No documents yet.</p>
              )}
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">Open Drift by Severity</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="space-y-2">
              {Object.entries(data.driftSeverityBreakdown).map(([severity, count]) => (
                <div key={severity} className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span
                      className="h-3 w-3 rounded-full"
                      style={{ backgroundColor: SEVERITY_COLORS[severity] ?? '#94a3b8' }}
                    />
                    <span className="text-sm">{severity}</span>
                  </div>
                  <span className="text-sm font-medium">{count}</span>
                </div>
              ))}
              {Object.keys(data.driftSeverityBreakdown).length === 0 && (
                <p className="text-sm text-muted-foreground">No open drift alerts.</p>
              )}
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
