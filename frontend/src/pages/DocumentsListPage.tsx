import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, FileText, Plus, Search } from 'lucide-react';
import { PageTitle } from '../components/PageTitle';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Badge } from '../components/ui/badge';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../components/ui/table';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../components/ui/select';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { describeError } from '../services/auth';
import { documentsApi, Document, DocumentStatus } from '../services/documents';
import { format } from 'date-fns';

const STATUS_VARIANT: Record<DocumentStatus, 'muted' | 'warning' | 'success' | 'info' | 'destructive' | 'secondary'> = {
  DRAFT: 'muted',
  IN_REVIEW: 'warning',
  APPROVED: 'success',
  PUBLISHED: 'info',
  REJECTED: 'destructive',
  ARCHIVED: 'secondary',
};

const STATUS_LABEL: Record<DocumentStatus, string> = {
  DRAFT: 'Draft',
  IN_REVIEW: 'In review',
  APPROVED: 'Approved',
  PUBLISHED: 'Published',
  REJECTED: 'Rejected',
  ARCHIVED: 'Archived',
};

export default function DocumentsListPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const [docs, setDocs] = useState<Document[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState<string>('');
  const [docType, setDocType] = useState<string>('all');
  const [statusFilter, setStatusFilter] = useState<string>('all');

  useEffect(() => {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    documentsApi
      .list(workspaceId)
      .then(setDocs)
      .catch((err) => setError(describeError(err)))
      .finally(() => setLoading(false));
  }, [workspaceId]);

  const docTypes = useMemo(() => {
    const s = new Set(docs.map((d) => d.docType));
    return ['all', ...Array.from(s)];
  }, [docs]);

  const filtered = useMemo(() => {
    const q = search.toLowerCase();
    return docs.filter((d) => {
      if (docType !== 'all' && d.docType !== docType) return false;
      if (statusFilter !== 'all' && d.status !== statusFilter) return false;
      if (!q) return true;
      return `${d.title} ${d.slug} ${d.docType} ${d.summary ?? ''}`
        .toLowerCase()
        .includes(q);
    });
  }, [docs, search, docType, statusFilter]);

  if (!workspaceId) return <EmptyState title="Missing workspace id" />;

  return (
    <div className="space-y-4">
      <Button variant="ghost" size="sm" asChild>
        <Link to={`/workspaces/${workspaceId}`}>
          <ArrowLeft className="mr-1 h-4 w-4" /> Back to workspace
        </Link>
      </Button>

      <PageTitle
        title="Documentation"
        subtitle="All documents in this workspace."
        action={
          <Button asChild>
            <Link to={`/workspaces/${workspaceId}/documents/new`}>
              <Plus className="mr-1 h-4 w-4" /> New document
            </Link>
          </Button>
        }
      />

      <div className="flex flex-wrap items-center gap-2">
        <div className="relative flex-1 sm:max-w-sm">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            placeholder="Search by title, slug, type…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-9"
          />
        </div>
        <Select value={docType} onValueChange={setDocType}>
          <SelectTrigger className="w-40">
            <SelectValue placeholder="Type" />
          </SelectTrigger>
          <SelectContent>
            {docTypes.map((t) => (
              <SelectItem key={t} value={t}>
                {t === 'all' ? 'All types' : t}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select value={statusFilter} onValueChange={setStatusFilter}>
          <SelectTrigger className="w-40">
            <SelectValue placeholder="Status" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All statuses</SelectItem>
            {(Object.keys(STATUS_LABEL) as DocumentStatus[]).map((s) => (
              <SelectItem key={s} value={s}>
                {STATUS_LABEL[s]}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {error && <ErrorState message={error} />}
      {loading && <LoadingState message="Loading documents…" />}

      {!loading && !error && filtered.length === 0 && (
        <EmptyState
          icon={<FileText className="h-8 w-8" />}
          title="No documents match"
          description="Try clearing the filters or create a new document."
          action={
            <Button asChild>
              <Link to={`/workspaces/${workspaceId}/documents/new`}>
                <Plus className="mr-1 h-4 w-4" /> New document
              </Link>
            </Button>
          }
        />
      )}

      {!loading && filtered.length > 0 && (
        <div className="rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Title</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Auto-update</TableHead>
                <TableHead>Updated</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filtered.map((d) => (
                <TableRow key={d.id}>
                  <TableCell>
                    <div className="font-medium">
                      <Link
                        to={`/workspaces/${workspaceId}/documents/${d.id}`}
                        className="hover:text-primary"
                      >
                        {d.title}
                      </Link>
                    </div>
                    <div className="mt-0.5 text-xs text-muted-foreground">
                      <code>{d.slug}</code>
                      {d.summary ? ` · ${d.summary.slice(0, 80)}` : ''}
                    </div>
                  </TableCell>
                  <TableCell>
                    <Badge variant="muted">{d.docType}</Badge>
                  </TableCell>
                  <TableCell>
                    <Badge variant={STATUS_VARIANT[d.status]}>
                      {STATUS_LABEL[d.status]}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    {d.autoUpdateEnabled ? (
                      <Badge variant="info">auto</Badge>
                    ) : (
                      <span className="text-xs text-muted-foreground">manual</span>
                    )}
                  </TableCell>
                  <TableCell className="text-xs text-muted-foreground">
                    {format(new Date(d.updatedAt), 'MMM d, yyyy')}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  );
}
