import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, Download, FileText, Search, RefreshCw } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import {
  Card, CardContent, CardHeader, CardTitle,
} from '../components/ui/card';
import { Input } from '../components/ui/input';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { describeError } from '../services/auth';
import apiClient from '../services/api';
import { format } from 'date-fns';

interface AuditLogEntry {
  id: string;
  actorUserId: string | null;
  actorRole: string | null;
  action: string;
  resourceType: string;
  resourceId: string | null;
  workspaceId: string | null;
  payload: string;
  createdAt: string;
}

interface PageResponse {
  items: AuditLogEntry[];
  page: number;
  size: number;
  total: number;
}

export default function AdminAuditLogPage() {
  const { t } = useTranslation();
  const [logs, setLogs] = useState<AuditLogEntry[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [actionFilter, setActionFilter] = useState<string>('');
  const [expanded, setExpanded] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const params: Record<string, string | number> = { page: 0, size: 100 };
      if (actionFilter) params.action = actionFilter;
      const { data } = await apiClient.get<PageResponse>('/admin/audit-logs', { params });
      setLogs(data.items);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  function exportCsv() {
    const params = new URLSearchParams();
    if (actionFilter) params.append('action', actionFilter);
    params.append('format', 'csv');
    const url = `/api/v1/admin/audit-logs/export?${params.toString()}`;
    window.open(url, '_blank');
  }

  function exportJson() {
    const params = new URLSearchParams();
    if (actionFilter) params.append('action', actionFilter);
    params.append('format', 'json');
    const url = `/api/v1/admin/audit-logs/export?${params.toString()}`;
    window.open(url, '_blank');
  }

  return (
    <div className="space-y-6">
      <Button variant="ghost" size="sm" asChild>
        <Link to="/admin">
          <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}
        </Link>
      </Button>

      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight flex items-center gap-2">
            <FileText className="h-5 w-5" /> Platform Audit Log
          </h1>
          <p className="text-sm text-muted-foreground">
            Cross-workspace audit log. All administrative and user actions are recorded here.
          </p>
        </div>
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={exportCsv}>
            <Download className="mr-1 h-3 w-3" /> CSV
          </Button>
          <Button size="sm" variant="outline" onClick={exportJson}>
            <Download className="mr-1 h-3 w-3" /> JSON
          </Button>
        </div>
      </div>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between space-y-0">
          <CardTitle className="text-base">Filter</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex gap-2">
            <div className="flex-1">
              <div className="relative">
                <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                <Input
                  value={actionFilter}
                  onChange={(e) => setActionFilter(e.target.value)}
                  placeholder="Filter by action (e.g. 'audit_retention')"
                  className="pl-8"
                />
              </div>
            </div>
            <Button onClick={() => void load()}>
              <RefreshCw className="mr-1 h-3 w-3" /> Apply
            </Button>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardContent className="p-0">
          {loading ? (
            <LoadingState message="Loading audit logs..." />
          ) : error ? (
            <ErrorState message={error} />
          ) : logs.length === 0 ? (
            <EmptyState title="No audit logs" description="No entries match the current filter." />
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>When</TableHead>
                  <TableHead>Actor</TableHead>
                  <TableHead>Role</TableHead>
                  <TableHead>Action</TableHead>
                  <TableHead>Resource</TableHead>
                  <TableHead>Workspace</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {logs.map((l) => (
                  <>
                    <TableRow key={l.id} className="cursor-pointer" onClick={() => setExpanded(expanded === l.id ? null : l.id)}>
                      <TableCell className="text-xs whitespace-nowrap">
                        {format(new Date(l.createdAt), 'MMM d, HH:mm:ss')}
                      </TableCell>
                      <TableCell className="font-mono text-xs">
                        {l.actorUserId ? l.actorUserId.substring(0, 8) + '...' : '—'}
                      </TableCell>
                      <TableCell>
                        {l.actorRole && <Badge variant="muted" className="text-[10px]">{l.actorRole}</Badge>}
                      </TableCell>
                      <TableCell>
                        <code className="text-xs">{l.action}</code>
                      </TableCell>
                      <TableCell>
                        <div className="text-xs">
                          <div>{l.resourceType}</div>
                          {l.resourceId && (
                            <code className="text-muted-foreground">{l.resourceId.substring(0, 16)}</code>
                          )}
                        </div>
                      </TableCell>
                      <TableCell className="font-mono text-xs text-muted-foreground">
                        {l.workspaceId ? l.workspaceId.substring(0, 8) + '...' : '—'}
                      </TableCell>
                    </TableRow>
                    {expanded === l.id && (
                      <TableRow>
                        <TableCell colSpan={6} className="bg-muted/30">
                          <pre className="text-xs overflow-auto p-2 max-h-48">
                            {JSON.stringify(JSON.parse(l.payload), null, 2)}
                          </pre>
                        </TableCell>
                      </TableRow>
                    )}
                  </>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
