import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { AlertTriangle, ArrowLeft, Check, ChevronDown, ChevronRight } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../components/ui/select';
import { Card, CardContent } from '../components/ui/card';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '../components/ui/dialog';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { driftApi, DriftAlert, DriftKind, DriftResolution, DriftSeverity } from '../services/drift';
import { describeError } from '../services/auth';
import { format } from 'date-fns';

const SEVERITY_BADGE: Record<DriftSeverity, 'destructive' | 'warning' | 'info' | 'muted'> = {
  CRITICAL: 'destructive',
  HIGH: 'warning',
  MEDIUM: 'info',
  LOW: 'muted',
};

const KIND_BADGE: Record<DriftKind, 'default' | 'secondary' | 'muted'> = {
  REFERENTIAL: 'default',
  SIGNATURE: 'secondary',
  SEMANTIC: 'muted',
};

const RESOLUTION_BADGE: Record<DriftResolution, 'info' | 'success' | 'warning' | 'muted' | 'destructive'> = {
  OPEN: 'warning',
  ACCEPTED: 'success',
  DISMISSED: 'muted',
  FIXED: 'info',
};

interface AlertWithRepo extends DriftAlert {
  repositoryName?: string;
}

function EvidenceRow({ evidenceJson }: { evidenceJson: string }) {
  const [open, setOpen] = useState(false);
  let parsed: unknown;
  try {
    parsed = JSON.parse(evidenceJson);
  } catch {
    parsed = evidenceJson;
  }
  return (
    <div className="mt-2 rounded border bg-muted/50 p-2">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
      >
        {open ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
        {open ? 'Hide' : 'Show'} evidence
      </button>
      {open && (
        <pre className="mt-2 overflow-auto rounded bg-background p-2 text-xs font-mono max-h-48">
          {JSON.stringify(parsed, null, 2)}
        </pre>
      )}
    </div>
  );
}

export default function DriftAlertsPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();

  const [alerts, setAlerts] = useState<AlertWithRepo[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  const [severityFilter, setSeverityFilter] = useState<string>('ALL');
  const [kindFilter, setKindFilter] = useState<string>('ALL');
  const [statusFilter, setStatusFilter] = useState<string>('OPEN');

  const [resolveAlertId, setResolveAlertId] = useState<string | null>(null);
  const [resolveResolution, setResolveResolution] = useState<DriftResolution>('FIXED');

  const load = async () => {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    try {
      const params: { status?: DriftResolution; kind?: DriftKind; severity?: DriftSeverity } = {};
      if (statusFilter !== 'ALL') params.status = statusFilter as DriftResolution;
      if (kindFilter !== 'ALL') params.kind = kindFilter as DriftKind;
      if (severityFilter !== 'ALL') params.severity = severityFilter as DriftSeverity;

      const list = await driftApi.list(workspaceId, params);
      setAlerts(list as AlertWithRepo[]);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, [workspaceId, severityFilter, kindFilter, statusFilter]);

  async function handleResolve() {
    if (!workspaceId || !resolveAlertId) return;
    try {
      await driftApi.resolve(workspaceId, resolveAlertId, resolveResolution);
      setResolveAlertId(null);
      await load();
    } catch (err) {
      setError(describeError(err));
    }
  }

  if (!workspaceId) return <EmptyState title="Missing workspace id" />;

  const criticalCount = alerts.filter((a) => a.severity === 'CRITICAL').length;
  const highCount = alerts.filter((a) => a.severity === 'HIGH').length;
  const mediumCount = alerts.filter((a) => a.severity === 'MEDIUM').length;
  const lowCount = alerts.filter((a) => a.severity === 'LOW').length;

  return (
    <div className="space-y-4">
      <Button variant="ghost" size="sm" asChild>
        <Link to={`/workspaces/${workspaceId}`}>
          <ArrowLeft className="mr-1 h-4 w-4" /> Back to workspace
        </Link>
      </Button>

      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Drift alerts</h1>
          <p className="text-sm text-muted-foreground">Documentation drift detected from source code changes.</p>
        </div>
      </div>

      {/* Severity summary cards */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Card className="border-destructive/50">
          <CardContent className="pt-4">
            <div className="text-2xl font-bold text-destructive">{criticalCount}</div>
            <div className="text-xs text-muted-foreground">Critical</div>
          </CardContent>
        </Card>
        <Card className="border-orange-500/50">
          <CardContent className="pt-4">
            <div className="text-2xl font-bold text-orange-500">{highCount}</div>
            <div className="text-xs text-muted-foreground">High</div>
          </CardContent>
        </Card>
        <Card className="border-blue-500/50">
          <CardContent className="pt-4">
            <div className="text-2xl font-bold text-blue-500">{mediumCount}</div>
            <div className="text-xs text-muted-foreground">Medium</div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="pt-4">
            <div className="text-2xl font-bold text-muted-foreground">{lowCount}</div>
            <div className="text-xs text-muted-foreground">Low</div>
          </CardContent>
        </Card>
      </div>

      {/* Filters */}
      <div className="flex flex-wrap gap-2">
        <Select value={statusFilter} onValueChange={setStatusFilter}>
          <SelectTrigger className="w-36">
            <SelectValue placeholder="Status" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">All statuses</SelectItem>
            <SelectItem value="OPEN">Open</SelectItem>
            <SelectItem value="ACCEPTED">Accepted</SelectItem>
            <SelectItem value="DISMISSED">Dismissed</SelectItem>
            <SelectItem value="FIXED">Fixed</SelectItem>
          </SelectContent>
        </Select>
        <Select value={severityFilter} onValueChange={setSeverityFilter}>
          <SelectTrigger className="w-36">
            <SelectValue placeholder="Severity" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">All severities</SelectItem>
            <SelectItem value="CRITICAL">Critical</SelectItem>
            <SelectItem value="HIGH">High</SelectItem>
            <SelectItem value="MEDIUM">Medium</SelectItem>
            <SelectItem value="LOW">Low</SelectItem>
          </SelectContent>
        </Select>
        <Select value={kindFilter} onValueChange={setKindFilter}>
          <SelectTrigger className="w-36">
            <SelectValue placeholder="Kind" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">All kinds</SelectItem>
            <SelectItem value="REFERENTIAL">Referential</SelectItem>
            <SelectItem value="SIGNATURE">Signature</SelectItem>
            <SelectItem value="SEMANTIC">Semantic</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {error && <ErrorState message={error} />}
      {loading && <LoadingState message="Loading drift alerts…" />}

      {!loading && !error && alerts.length === 0 && (
        <EmptyState
          icon={<AlertTriangle className="h-8 w-8" />}
          title="No drift alerts"
          description="No drift alerts match the current filters."
        />
      )}

      {!loading && !error && alerts.length > 0 && (
        <Card>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Severity</TableHead>
                  <TableHead>Kind</TableHead>
                  <TableHead>Title</TableHead>
                  <TableHead>Detected</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {alerts.map((a) => (
                  <TableRow key={a.id}>
                    <TableCell>
                      <Badge variant={SEVERITY_BADGE[a.severity]}>{a.severity}</Badge>
                    </TableCell>
                    <TableCell>
                      <Badge variant={KIND_BADGE[a.driftKind]}>{a.driftKind}</Badge>
                    </TableCell>
                    <TableCell>
                      <div className="max-w-[200px]">
                        <div className="truncate font-medium text-sm">{a.title}</div>
                        {a.aiSuggestion && (
                          <div className="mt-1 text-xs text-muted-foreground truncate">
                            Hint: {a.aiSuggestion.slice(0, 80)}
                          </div>
                        )}
                        {a.evidenceJson && <EvidenceRow evidenceJson={a.evidenceJson} />}
                      </div>
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground whitespace-nowrap">
                      {format(new Date(a.detectedAt), 'MMM d, yyyy HH:mm')}
                    </TableCell>
                    <TableCell>
                      <Badge variant={RESOLUTION_BADGE[a.resolutionStatus]}>
                        {a.resolutionStatus}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <Dialog open={resolveAlertId === a.id} onOpenChange={(o) => { if (!o) setResolveAlertId(null); }}>
                        <DialogTrigger asChild>
                          <Button size="sm" variant="outline" onClick={() => { setResolveAlertId(a.id); setResolveResolution('FIXED'); }}>
                            <Check className="mr-1 h-3 w-3" /> Resolve
                          </Button>
                        </DialogTrigger>
                        <DialogContent>
                          <DialogHeader>
                            <DialogTitle>Resolve drift alert</DialogTitle>
                            <DialogDescription>{a.title}</DialogDescription>
                          </DialogHeader>
                          <div className="space-y-3 py-2">
                            <Select
                              value={resolveResolution}
                              onValueChange={(v) => setResolveResolution(v as DriftResolution)}
                            >
                              <SelectTrigger>
                                <SelectValue />
                              </SelectTrigger>
                              <SelectContent>
                                <SelectItem value="FIXED">Fixed</SelectItem>
                                <SelectItem value="ACCEPTED">Accepted (risk acknowledged)</SelectItem>
                                <SelectItem value="DISMISSED">Dismissed</SelectItem>
                                <SelectItem value="OPEN">Keep open</SelectItem>
                              </SelectContent>
                            </Select>
                          </div>
                          <DialogFooter>
                            <DialogClose asChild><Button variant="outline">Cancel</Button></DialogClose>
                            <Button onClick={() => void handleResolve()}>Confirm resolution</Button>
                          </DialogFooter>
                        </DialogContent>
                      </Dialog>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
