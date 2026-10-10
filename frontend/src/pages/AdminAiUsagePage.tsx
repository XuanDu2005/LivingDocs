import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  Legend, LineChart, Line,
} from 'recharts';
import {
  AlertTriangle, ChevronDown, ChevronUp, DollarSign, Loader2,
  RefreshCw, Search, TrendingUp,
} from 'lucide-react';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Input } from '../components/ui/input';
import {
  Card, CardContent, CardDescription, CardHeader, CardTitle,
} from '../components/ui/card';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import {
  Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter,
  DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import { Label } from '../components/ui/label';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { describeError } from '../services/auth';
import { adminApi } from '../services/adminApi';
import { AdminAiPlatformSummary, AdminAiUsageRow, DailyUsagePoint } from '../types/admin';
import { format, parseISO } from 'date-fns';

const USD = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

export default function AdminAiUsagePage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [summary, setSummary] = useState<AdminAiPlatformSummary | null>(null);
  const [chartData, setChartData] = useState<DailyUsagePoint[]>([]);
  const [breakdown, setBreakdown] = useState<AdminAiUsageRow[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState<string>('');
  const [selectedWs, setSelectedWs] = useState<AdminAiUsageRow | null>(null);
  const [wsChart, setWsChart] = useState<DailyUsagePoint[]>([]);
  const [wsChartLoading, setWsChartLoading] = useState<boolean>(false);
  const [editTarget, setEditTarget] = useState<AdminAiUsageRow | null>(null);
  const [editDaily, setEditDaily] = useState<string>('');
  const [editMonthly, setEditMonthly] = useState<string>('');
  const [editRate, setEditRate] = useState<string>('');
  const [saving, setSaving] = useState<boolean>(false);
  const [info, setInfo] = useState<string | null>(null);
  const [sortField, setSortField] = useState<'tokens30' | 'today' | 'name'>('tokens30');
  const [sortAsc, setSortAsc] = useState<boolean>(false);

  const loadAll = async () => {
    setLoading(true);
    setError(null);
    try {
      const [s, c, b] = await Promise.all([
        adminApi.getPlatformSummary(),
        adminApi.getPlatformChart(),
        adminApi.getWorkspaceBreakdown(),
      ]);
      setSummary(s);
      setChartData(c);
      setBreakdown(b);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void loadAll(); }, []);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    let rows = breakdown.filter((r) => {
      if (q.length === 0) return true;
      return `${r.workspaceName} ${r.workspaceSlug}`.toLowerCase().includes(q);
    });
    rows.sort((a, b) => {
      let cmp = 0;
      if (sortField === 'tokens30') cmp = a.last30DaysTokens - b.last30DaysTokens;
      else if (sortField === 'today') cmp = a.todayTokens - b.todayTokens;
      else cmp = a.workspaceName.localeCompare(b.workspaceName);
      return sortAsc ? cmp : -cmp;
    });
    return rows;
  }, [breakdown, search, sortField, sortAsc]);

  function toggleSort(field: typeof sortField) {
    if (sortField === field) setSortAsc((a) => !a);
    else { setSortField(field); setSortAsc(false); }
  }

  function SortIcon({ field }: { field: typeof sortField }) {
    if (sortField !== field) return null;
    return sortAsc ? <ChevronUp className="h-3 w-3 inline" /> : <ChevronDown className="h-3 w-3 inline" />;
  }

  async function openDetail(ws: AdminAiUsageRow) {
    setSelectedWs(ws);
    setWsChartLoading(true);
    try {
      const data = await adminApi.getWorkspaceAiChart(ws.workspaceId);
      setWsChart(data);
    } catch {
      setWsChart([]);
    } finally {
      setWsChartLoading(false);
    }
  }

  function openEdit(ws: AdminAiUsageRow) {
    setEditTarget(ws);
    setEditDaily(ws.dailyLimit?.toString() ?? '');
    setEditMonthly(ws.monthlyLimit?.toString() ?? '');
    setEditRate(ws.rateLimitPerMinute?.toString() ?? '');
  }

  async function saveEdit() {
    if (!editTarget) return;
    setSaving(true);
    setInfo(null);
    setError(null);
    try {
      const updated = await adminApi.updateWorkspaceLimits(editTarget.workspaceId, {
        dailyLimit: editDaily ? Number(editDaily) : null,
        monthlyLimit: editMonthly ? Number(editMonthly) : null,
        rateLimitPerMinute: editRate ? Number(editRate) : null,
      });
      setBreakdown((prev) => prev.map((r) => r.workspaceId === updated.workspaceId ? updated : r));
      setInfo(t('adminAiUsage.limitsSaved', 'Limits updated for {{workspace}}.', { workspace: updated.workspaceName }));
      setEditTarget(null);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSaving(false);
    }
  }

  const chartFormat = (v: number) => v >= 1_000_000 ? `${(v / 1_000_000).toFixed(1)}M`
    : v >= 1_000 ? `${(v / 1_000).toFixed(0)}K` : `${v}`;

  const CustomTooltip = ({ active, payload, label }: { active?: boolean; payload?: { value: number; name: string }[]; label?: string }) => {
    if (!active || !payload?.length) return null;
    return (
      <div className="rounded-md border bg-background px-3 py-2 shadow-sm text-xs">
        <div className="font-medium mb-1">{label}</div>
        {payload.map((p) => (
          <div key={p.name} className="text-muted-foreground">
            {p.name === 'tokens' ? `${p.value.toLocaleString()} tokens` : `${p.value.toLocaleString()} requests`}
          </div>
        ))}
      </div>
    );
  };

  if (loading) return <LoadingState message={t('adminAiUsage.loading', 'Loading AI usage…')} />;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">
          {t('adminAiUsage.title', 'Admin · AI Usage')}
        </h1>
        <p className="text-sm text-muted-foreground">
          {t('adminAiUsage.subtitle', 'Platform-wide AI token consumption, budget limits, and per-workspace rate control.')}
        </p>
      </div>

      {error && <ErrorState message={error} />}
      {info && (
        <div className="flex items-center gap-2 rounded-md border border-emerald-200 bg-emerald-50 dark:bg-emerald-950/20 px-3 py-2 text-sm text-emerald-700 dark:text-emerald-300">
          <TrendingUp className="h-4 w-4" /> {info}
        </div>
      )}

      {/* Stat cards */}
      {summary && (
        <div className="grid gap-3 md:grid-cols-4">
          <StatCard
            label={t('adminAiUsage.statTodayTokens', 'Tokens today')}
            value={summary.todayTokens.toLocaleString()}
            sub={t('adminAiUsage.statAcrossAll', 'across {{n}} workspaces', { n: summary.workspaceCount })}
          />
          <StatCard
            label={t('adminAiUsage.stat30dTokens', 'Tokens (30d)')}
            value={summary.last30DaysTokens.toLocaleString()}
            sub={t('adminAiUsage.stat30dRequests', '{{r}} requests', { r: summary.last30DaysRequests.toLocaleString() })}
          />
          <StatCard
            label={t('adminAiUsage.stat30dCost', 'Est. cost (30d)')}
            value={USD.format(Number(summary.last30DaysCost))}
            sub={t('adminAiUsage.statAverage', 'avg {{avg}}/workspace',
              { avg: USD.format(summary.workspaceCount > 0 ? Number(summary.last30DaysCost) / summary.workspaceCount : 0) })}
            icon={<DollarSign className="h-5 w-5 text-emerald-500" />}
          />
          <StatCard
            label={t('adminAiUsage.statWorkspaces', 'Active workspaces')}
            value={summary.workspaceCount.toString()}
            sub={t('adminAiUsage.statWithUsage', 'with AI usage data')}
          />
        </div>
      )}

      {/* Platform chart */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <TrendingUp className="h-4 w-4" />
            {t('adminAiUsage.chartTitle', 'Daily token consumption — last 30 days')}
          </CardTitle>
          <CardDescription>
            {t('adminAiUsage.chartDesc', 'Aggregated across all workspaces. Gray bars = requests × 10 (right axis).')}
          </CardDescription>
        </CardHeader>
        <CardContent>
          {chartData.length === 0 ? (
            <p className="text-sm text-muted-foreground text-center py-8">
              {t('adminAiUsage.noData', 'No usage data yet.')}
            </p>
          ) : (
            <ResponsiveContainer width="100%" height={240}>
              <BarChart data={chartData} margin={{ top: 5, right: 30, left: 10, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
                <XAxis
                  dataKey="date"
                  tickFormatter={(v: string) => format(parseISO(v), 'MMM d')}
                  tick={{ fontSize: 11 }}
                  stroke="hsl(var(--muted-foreground))"
                  interval={6}
                />
                <YAxis
                  yAxisId="left"
                  tickFormatter={chartFormat}
                  tick={{ fontSize: 11 }}
                  stroke="hsl(var(--muted-foreground))"
                />
                <YAxis yAxisId="right" orientation="right" tick={{ fontSize: 11 }} stroke="hsl(var(--muted-foreground))" />
                <Tooltip content={<CustomTooltip />} />
                <Legend />
                <Bar yAxisId="left" dataKey="tokens" name="tokens" fill="hsl(var(--primary))" radius={[3, 3, 0, 0]} maxBarSize={20} />
                <Bar yAxisId="right" dataKey="requests" name="requests" fill="hsl(var(--muted))" radius={[3, 3, 0, 0]} maxBarSize={20} />
              </BarChart>
            </ResponsiveContainer>
          )}
        </CardContent>
      </Card>

      {/* Per-workspace table */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <TrendingUp className="h-4 w-4" />
            {t('adminAiUsage.tableTitle', 'Per-workspace breakdown')}
            <Badge variant="muted">{filtered.length}</Badge>
          </CardTitle>
          <div className="flex gap-2 pt-2">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
              <Input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder={t('adminAiUsage.searchPlaceholder', 'Search workspaces…')}
                className="pl-8"
              />
            </div>
            <Button variant="outline" onClick={() => void loadAll()} disabled={loading}>
              <RefreshCw className={`h-4 w-4 mr-1 ${loading ? 'animate-spin' : ''}`} />
              {t('common.refresh', 'Refresh')}
            </Button>
          </div>
        </CardHeader>
        <CardContent className="p-0">
          {filtered.length === 0 ? (
            <EmptyState
              title={t('adminAiUsage.emptyTitle', 'No workspaces found.')}
              description={t('adminAiUsage.emptyDesc', 'Adjust the search filter.')}
            />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-64 cursor-pointer select-none" onClick={() => toggleSort('name')}>
                    {t('adminAiUsage.colWorkspace', 'Workspace')} <SortIcon field="name" />
                  </TableHead>
                  <TableHead className="text-right cursor-pointer select-none" onClick={() => toggleSort('today')}>
                    {t('adminAiUsage.colToday', 'Today')} <SortIcon field="today" />
                  </TableHead>
                  <TableHead className="text-right cursor-pointer select-none" onClick={() => toggleSort('tokens30')}>
                    {t('adminAiUsage.col30d', '30 days')} <SortIcon field="tokens30" />
                  </TableHead>
                  <TableHead className="text-right">{t('adminAiUsage.colRequests30', 'Requests (30d)')}</TableHead>
                  <TableHead className="text-right">{t('adminAiUsage.colDailyLimit', 'Daily limit')}</TableHead>
                  <TableHead className="text-right">{t('adminAiUsage.colMonthlyLimit', 'Monthly limit')}</TableHead>
                  <TableHead className="text-right">{t('adminAiUsage.colRateLimit', 'Rate / min')}</TableHead>
                  <TableHead className="text-right">{t('adminAiUsage.colActions', 'Actions')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((ws) => {
                  const dailyPct = ws.dailyLimit ? Math.min(100, (ws.todayTokens / ws.dailyLimit) * 100) : 0;
                  const monthlyPct = ws.monthlyLimit ? Math.min(100, (ws.monthTokens / ws.monthlyLimit) * 100) : 0;
                  return (
                    <TableRow key={ws.workspaceId}>
                      <TableCell>
                        <div
                          className="font-medium hover:underline cursor-pointer"
                          onClick={() => void openDetail(ws)}
                        >
                          {ws.workspaceName}
                        </div>
                        <div className="text-xs text-muted-foreground font-mono">{ws.workspaceSlug}</div>
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        <div>{ws.todayTokens.toLocaleString()}</div>
                        {ws.dailyLimit && (
                          <div className="mt-1 h-1 w-full rounded-full bg-muted">
                            <div
                              className={`h-full rounded-full ${dailyPct > 80 ? 'bg-destructive' : dailyPct > 50 ? 'bg-amber-500' : 'bg-primary'}`}
                              style={{ width: `${dailyPct}%` }}
                            />
                          </div>
                        )}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        <div>{ws.last30DaysTokens.toLocaleString()}</div>
                        {ws.monthlyLimit && (
                          <div className="mt-1 h-1 w-full rounded-full bg-muted">
                            <div
                              className={`h-full rounded-full ${monthlyPct > 80 ? 'bg-destructive' : monthlyPct > 50 ? 'bg-amber-500' : 'bg-primary'}`}
                              style={{ width: `${monthlyPct}%` }}
                            />
                          </div>
                        )}
                      </TableCell>
                      <TableCell className="text-right tabular-nums text-xs text-muted-foreground">
                        {ws.last30DaysRequests.toLocaleString()}
                      </TableCell>
                      <TableCell className="text-right tabular-nums text-xs">
                        {ws.dailyLimit ? ws.dailyLimit.toLocaleString() : <span className="text-muted-foreground">—</span>}
                      </TableCell>
                      <TableCell className="text-right tabular-nums text-xs">
                        {ws.monthlyLimit ? ws.monthlyLimit.toLocaleString() : <span className="text-muted-foreground">—</span>}
                      </TableCell>
                      <TableCell className="text-right tabular-nums text-xs">
                        {ws.rateLimitPerMinute ? ws.rateLimitPerMinute.toLocaleString() : <span className="text-muted-foreground">—</span>}
                      </TableCell>
                      <TableCell>
                        <div className="flex items-center justify-end gap-1">
                          <Button
                            size="icon"
                            variant="ghost"
                            title={t('adminAiUsage.viewChart', 'View usage chart')}
                            onClick={() => void openDetail(ws)}
                          >
                            <TrendingUp className="h-4 w-4" />
                          </Button>
                          <Button
                            size="icon"
                            variant="ghost"
                            title={t('adminAiUsage.setLimits', 'Set limits')}
                            onClick={() => openEdit(ws)}
                          >
                            <AlertTriangle className="h-4 w-4" />
                          </Button>
                          <Button
                            size="icon"
                            variant="ghost"
                            title={t('adminAiUsage.openWorkspace', 'Open workspace')}
                            onClick={() => navigate(`/workspaces/${ws.workspaceId}`)}
                          >
                            <ChevronUp className="h-4 w-4 rotate-45" />
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* Workspace detail dialog */}
      <Dialog open={!!selectedWs} onOpenChange={(o) => !o && setSelectedWs(null)}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>{selectedWs?.workspaceName}</DialogTitle>
            <DialogDescription>
              {t('adminAiUsage.chartDetailDesc', 'Daily usage for the last 30 days.')}
            </DialogDescription>
          </DialogHeader>
          {wsChartLoading ? (
            <div className="flex items-center justify-center py-8">
              <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
            </div>
          ) : (
            <ResponsiveContainer width="100%" height={200}>
              <LineChart data={wsChart} margin={{ top: 5, right: 20, left: 10, bottom: 5 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
                <XAxis
                  dataKey="date"
                  tickFormatter={(v: string) => format(parseISO(v), 'MMM d')}
                  tick={{ fontSize: 10 }}
                  stroke="hsl(var(--muted-foreground))"
                  interval={6}
                />
                <YAxis tickFormatter={chartFormat} tick={{ fontSize: 10 }} stroke="hsl(var(--muted-foreground))" />
                <Tooltip content={<CustomTooltip />} />
                <Legend />
                <Line type="monotone" dataKey="tokens" name="tokens" stroke="hsl(var(--primary))" strokeWidth={2} dot={false} />
              </LineChart>
            </ResponsiveContainer>
          )}
          <DialogFooter>
            <DialogClose asChild>
              <Button variant="outline">{t('common.close', 'Close')}</Button>
            </DialogClose>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Edit limits dialog */}
      <Dialog open={!!editTarget} onOpenChange={(o) => !o && setEditTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t('adminAiUsage.editLimitsTitle', 'Set AI limits for {{workspace}}', { workspace: editTarget?.workspaceName })}</DialogTitle>
            <DialogDescription>
              {t('adminAiUsage.editLimitsDesc', 'Leave empty to remove the limit (unlimited). Limits are enforced when the workspace makes AI requests.')}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-4 py-2">
            <div className="space-y-1.5">
              <Label htmlFor="edit-daily">{t('adminAiUsage.dailyLimit', 'Daily token limit')}</Label>
              <Input
                id="edit-daily"
                type="number"
                min={0}
                value={editDaily}
                onChange={(e) => setEditDaily(e.target.value)}
                placeholder={t('adminAiUsage.unlimited', 'Unlimited')}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="edit-monthly">{t('adminAiUsage.monthlyLimit', 'Monthly token limit')}</Label>
              <Input
                id="edit-monthly"
                type="number"
                min={0}
                value={editMonthly}
                onChange={(e) => setEditMonthly(e.target.value)}
                placeholder={t('adminAiUsage.unlimited', 'Unlimited')}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="edit-rate">{t('adminAiUsage.rateLimit', 'Requests per minute')}</Label>
              <Input
                id="edit-rate"
                type="number"
                min={0}
                value={editRate}
                onChange={(e) => setEditRate(e.target.value)}
                placeholder={t('adminAiUsage.unlimited', 'Unlimited')}
              />
            </div>
          </div>
          <DialogFooter>
            <DialogClose asChild>
              <Button variant="outline" disabled={saving}>{t('common.cancel', 'Cancel')}</Button>
            </DialogClose>
            <Button onClick={() => void saveEdit()} disabled={saving}>
              {saving
                ? <><Loader2 className="mr-1 h-4 w-4 animate-spin" /> {t('adminAiUsage.saving', 'Saving…')}</>
                : t('adminAiUsage.saveLimits', 'Save limits')}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function StatCard({ label, value, sub, icon }: { label: string; value: string; sub: string; icon?: React.ReactNode }) {
  return (
    <Card>
      <CardContent className="p-4 flex items-start justify-between">
        <div>
          <div className="text-xs uppercase tracking-wide text-muted-foreground">{label}</div>
          <div className="text-2xl font-semibold tabular-nums mt-1">{value}</div>
          <div className="text-xs text-muted-foreground mt-1">{sub}</div>
        </div>
        {icon && <div className="pt-1">{icon}</div>}
      </CardContent>
    </Card>
  );
}
