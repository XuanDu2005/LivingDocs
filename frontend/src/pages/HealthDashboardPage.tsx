import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, FileText, ShieldAlert, TrendingUp } from 'lucide-react';
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  BarChart, Bar, PieChart, Pie, Cell,
} from 'recharts';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import {
  Card, CardContent, CardHeader, CardTitle,
} from '../components/ui/card';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { documentsApi, DocumentStatus } from '../services/documents';
import { driftApi, DriftAlert, DriftSeverity } from '../services/drift';
import { githubApi } from '../services/github';
import { describeError } from '../services/auth';
import { format } from 'date-fns';

const SEVERITY_COLORS: Record<DriftSeverity, string> = {
  CRITICAL: '#ef4444',
  HIGH: '#f97316',
  MEDIUM: '#f59e0b',
  LOW: '#22c55e',
};

const SEVERITY_LABEL: Record<DriftSeverity, string> = {
  CRITICAL: 'Critical',
  HIGH: 'High',
  MEDIUM: 'Medium',
  LOW: 'Low',
};

export default function HealthDashboardPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();

  const [docs, setDocs] = useState<{ id: string; title: string; slug: string; status: DocumentStatus; updatedAt: string }[]>([]);
  const [drifts, setDrifts] = useState<DriftAlert[]>([]);
  const [repos, setRepos] = useState<{ id: string; fullName: string }[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!workspaceId) return;
    (async () => {
      setLoading(true);
      setError(null);
      try {
        const [docList, driftList, repoList] = await Promise.all([
          documentsApi.list(workspaceId),
          driftApi.list(workspaceId, { status: 'OPEN' }),
          githubApi.listRepositories(workspaceId),
        ]);
        setDocs(
          docList.map((d) => ({
            id: d.id,
            title: d.title,
            slug: d.slug,
            status: d.status,
            updatedAt: d.updatedAt,
          })),
        );
        setDrifts(driftList);
        setRepos(repoList);
      } catch (err) {
        setError(describeError(err));
      } finally {
        setLoading(false);
      }
    })();
  }, [workspaceId]);

  if (!workspaceId) return <EmptyState title="Missing workspace id" />;
  if (loading) return <LoadingState message="Loading health data…" />;
  if (error) return <ErrorState message={error} />;

  // ── Stats ──────────────────────────────────────────────────────────────
  const publishedCount = docs.filter((d) => d.status === 'PUBLISHED').length;
  const reviewCount = docs.filter((d) => d.status === 'IN_REVIEW' || d.status === 'APPROVED').length;

  const bySeverity: Record<DriftSeverity, number> = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0 };
  for (const a of drifts) bySeverity[a.severity]++;

  // ── Chart 1: Document freshness (last 30 days) ────────────────────────
  const freshnessData = useMemo(() => {
    const days = 30;
    const counts: Record<string, number> = {};
    const now = Date.now();
    for (let i = days - 1; i >= 0; i--) {
      const d = new Date(now - i * 86400000);
      const key = format(d, 'MMM d');
      counts[key] = 0;
    }
    for (const doc of docs) {
      const docDate = new Date(doc.updatedAt);
      const diffDays = Math.floor((now - docDate.getTime()) / 86400000);
      if (diffDays < days) {
        const key = format(docDate, 'MMM d');
        if (key in counts) counts[key]++;
      }
    }
    return Object.entries(counts).map(([name, updated]) => ({ name, updated }));
  }, [docs]);

  // ── Chart 2: Documents by status ──────────────────────────────────────
  const statusCounts: Record<DocumentStatus, number> = {
    DRAFT: 0, IN_REVIEW: 0, APPROVED: 0, PUBLISHED: 0, REJECTED: 0, ARCHIVED: 0,
  };
  for (const d of docs) statusCounts[d.status]++;

  const statusData = (Object.keys(statusCounts) as DocumentStatus[])
    .filter((s) => statusCounts[s] > 0)
    .map((s) => ({ name: s, count: statusCounts[s] }));

  // ── Chart 3: Drift by severity (Pie) ─────────────────────────────────
  const severityData = (Object.keys(bySeverity) as DriftSeverity[])
    .filter((s) => bySeverity[s] > 0)
    .map((s) => ({ name: SEVERITY_LABEL[s], value: bySeverity[s], color: SEVERITY_COLORS[s] }));

  // ── Chart 4: Top repos by drift ───────────────────────────────────────
  const driftByRepo = useMemo(() => {
    const counts: Record<string, number> = {};
    const repoMap = new Map(repos.map((r) => [r.id, r.fullName]));
    for (const a of drifts) {
      const name = repoMap.get(a.repositoryId) ?? a.repositoryId;
      counts[name] = (counts[name] ?? 0) + 1;
    }
    return Object.entries(counts)
      .map(([name, count]) => ({ name, count }))
      .sort((a, b) => b.count - a.count)
      .slice(0, 5);
  }, [drifts, repos]);

  return (
    <div className="space-y-6">
      <Button variant="ghost" size="sm" asChild>
        <Link to={`/workspaces/${workspaceId}`}>
          <ArrowLeft className="mr-1 h-4 w-4" /> Back to workspace
        </Link>
      </Button>

      <div>
        <h1 className="text-2xl font-bold tracking-tight">Documentation health</h1>
        <p className="text-sm text-muted-foreground">
          Overview of document coverage, freshness, and drift across this workspace.
        </p>
      </div>

      {/* Stat cards */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Card>
          <CardContent className="pt-4">
            <div className="flex items-center gap-2">
              <FileText className="h-5 w-5 text-primary" />
              <div>
                <div className="text-2xl font-bold">{docs.length}</div>
                <div className="text-xs text-muted-foreground">Total documents</div>
              </div>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-4">
            <div className="flex items-center gap-2">
              <TrendingUp className="h-5 w-5 text-green-500" />
              <div>
                <div className="text-2xl font-bold">{publishedCount}</div>
                <div className="text-xs text-muted-foreground">Published</div>
              </div>
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-4">
            <div className="flex items-center gap-2">
              <ShieldAlert className="h-5 w-5 text-orange-500" />
              <div>
                <div className="text-2xl font-bold">{reviewCount}</div>
                <div className="text-xs text-muted-foreground">In review</div>
              </div>
            </div>
          </CardContent>
        </Card>
        <Card className={drifts.length > 0 ? 'border-orange-500/50' : ''}>
          <CardContent className="pt-4">
            <div className="flex items-center gap-2">
              <ShieldAlert className={`h-5 w-5 ${drifts.length > 0 ? 'text-destructive' : 'text-green-500'}`} />
              <div>
                <div className={`text-2xl font-bold ${drifts.length > 0 ? 'text-destructive' : ''}`}>
                  {drifts.length}
                </div>
                <div className="text-xs text-muted-foreground">Open drift</div>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        {/* Chart 1: Document freshness */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Document updates (last 30 days)</CardTitle>
          </CardHeader>
          <CardContent>
            <ResponsiveContainer width="100%" height={200}>
              <LineChart data={freshnessData}>
                <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                <XAxis dataKey="name" tick={{ fontSize: 10 }} className="text-muted-foreground" />
                <YAxis tick={{ fontSize: 10 }} className="text-muted-foreground" />
                <Tooltip contentStyle={{ fontSize: 12 }} />
                <Line
                  type="monotone"
                  dataKey="updated"
                  stroke="var(--primary)"
                  strokeWidth={2}
                  dot={false}
                  name="Updated docs"
                />
              </LineChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>

        {/* Chart 2: Documents by status */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Documents by status</CardTitle>
          </CardHeader>
          <CardContent>
            {statusData.length === 0 ? (
              <p className="text-sm text-muted-foreground">No documents yet.</p>
            ) : (
              <ResponsiveContainer width="100%" height={200}>
                <BarChart data={statusData}>
                  <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                  <XAxis dataKey="name" tick={{ fontSize: 10 }} className="text-muted-foreground" />
                  <YAxis tick={{ fontSize: 10 }} className="text-muted-foreground" />
                  <Tooltip contentStyle={{ fontSize: 12 }} />
                  <Bar dataKey="count" fill="var(--primary)" name="Documents" />
                </BarChart>
              </ResponsiveContainer>
            )}
          </CardContent>
        </Card>

        {/* Chart 3: Drift by severity (Pie) */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Open drift by severity</CardTitle>
          </CardHeader>
          <CardContent>
            {severityData.length === 0 ? (
              <div className="flex flex-col items-center justify-center h-48">
                <ShieldAlert className="h-8 w-8 text-green-500 mb-2" />
                <p className="text-sm text-muted-foreground">No open drift</p>
              </div>
            ) : (
              <ResponsiveContainer width="100%" height={200}>
                <PieChart>
                  <Pie
                    data={severityData}
                    dataKey="value"
                    nameKey="name"
                    cx="50%"
                    cy="50%"
                    outerRadius={70}
                    label={({ name, value }) => `${name} ${value}`}
                    labelLine={false}
                  >
                    {severityData.map((entry, index) => (
                      <Cell key={`cell-${index}`} fill={entry.color} />
                    ))}
                  </Pie>
                  <Tooltip />
                </PieChart>
              </ResponsiveContainer>
            )}
          </CardContent>
        </Card>

        {/* Chart 4: Top repos by drift */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Top repositories by drift</CardTitle>
          </CardHeader>
          <CardContent>
            {driftByRepo.length === 0 ? (
              <div className="flex flex-col items-center justify-center h-48">
                <ShieldAlert className="h-8 w-8 text-green-500 mb-2" />
                <p className="text-sm text-muted-foreground">No drift detected</p>
              </div>
            ) : (
              <ResponsiveContainer width="100%" height={200}>
                <BarChart data={driftByRepo} layout="vertical">
                  <CartesianGrid strokeDasharray="3 3" className="stroke-muted" />
                  <XAxis type="number" tick={{ fontSize: 10 }} className="text-muted-foreground" />
                  <YAxis dataKey="name" type="category" tick={{ fontSize: 10 }} className="text-muted-foreground" width={120} />
                  <Tooltip contentStyle={{ fontSize: 12 }} />
                  <Bar dataKey="count" fill="#f97316" name="Drift alerts" />
                </BarChart>
              </ResponsiveContainer>
            )}
          </CardContent>
        </Card>
      </div>

      {/* Recent documents table */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Recent documents</CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Title</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Last updated</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {[...docs]
                .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
                .slice(0, 10)
                .map((d) => (
                  <TableRow key={d.id}>
                    <TableCell>
                      <Link
                        to={`/workspaces/${workspaceId}/documents/${d.id}`}
                        className="font-medium hover:text-primary"
                      >
                        {d.title}
                      </Link>
                      <div className="text-xs text-muted-foreground font-mono">{d.slug}</div>
                    </TableCell>
                    <TableCell>
                      <Badge variant={d.status === 'PUBLISHED' ? 'success' : d.status === 'DRAFT' ? 'muted' : 'warning'}>
                        {d.status}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground whitespace-nowrap">
                      {format(new Date(d.updatedAt), 'MMM d, yyyy')}
                    </TableCell>
                  </TableRow>
                ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  );
}
