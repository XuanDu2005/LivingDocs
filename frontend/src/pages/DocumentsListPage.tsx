import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
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

const STATUS_LABEL_KEY: Record<DocumentStatus, string> = {
  DRAFT: 'documents.statusDraft',
  IN_REVIEW: 'documents.statusInReview',
  APPROVED: 'documents.statusApproved',
  PUBLISHED: 'documents.statusPublished',
  REJECTED: 'documents.statusRejected',
  ARCHIVED: 'documents.statusArchived',
};

export default function DocumentsListPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { t } = useTranslation();
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
    documentsApi.list(workspaceId).then(setDocs).catch((err) => setError(describeError(err))).finally(() => setLoading(false));
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
      return `${d.title} ${d.slug} ${d.docType} ${d.summary ?? ''}`.toLowerCase().includes(q);
    });
  }, [docs, search, docType, statusFilter]);

  if (!workspaceId) return <EmptyState title={t('documents.missingWorkspaceId')} />;

  return (
    <div className="space-y-4">
      <Button variant="ghost" size="sm" asChild>
        <Link to={`/workspaces/${workspaceId}`}>
          <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}
        </Link>
      </Button>

      <PageTitle
        title={t('documents.title')}
        subtitle={t('documents.subtitle')}
        action={
          <Button asChild>
            <Link to={`/workspaces/${workspaceId}/documents/new`}>
              <Plus className="mr-1 h-4 w-4" /> {t('documents.newCta')}
            </Link>
          </Button>
        }
      />

      <div className="flex flex-wrap items-center gap-2">
        <div className="relative flex-1 sm:max-w-sm">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            placeholder={t('documents.searchPlaceholder')}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="pl-9"
          />
        </div>
        <Select value={docType} onValueChange={setDocType}>
          <SelectTrigger className="w-40">
            <SelectValue placeholder={t('documents.type')} />
          </SelectTrigger>
          <SelectContent>
            {docTypes.map((dt) => (
              <SelectItem key={dt} value={dt}>
                {dt === 'all' ? t('documents.allTypes') : dt}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select value={statusFilter} onValueChange={setStatusFilter}>
          <SelectTrigger className="w-40">
            <SelectValue placeholder={t('documents.status')} />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">{t('documents.allStatuses')}</SelectItem>
            {(Object.keys(STATUS_LABEL_KEY) as DocumentStatus[]).map((s) => (
              <SelectItem key={s} value={s}>{t(STATUS_LABEL_KEY[s])}</SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {error && <ErrorState message={error} />}
      {loading && <LoadingState message={t('documents.loading')} />}

      {!loading && !error && filtered.length === 0 && (
        <EmptyState
          icon={<FileText className="h-8 w-8" />}
          title={t('documents.emptyTitle')}
          description={t('documents.emptyDesc')}
          action={
            <Button asChild>
              <Link to={`/workspaces/${workspaceId}/documents/new`}>
                <Plus className="mr-1 h-4 w-4" /> {t('documents.newCta')}
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
                <TableHead>{t('documents.colTitle')}</TableHead>
                <TableHead>{t('documents.colType')}</TableHead>
                <TableHead>{t('documents.colStatus')}</TableHead>
                <TableHead>{t('documents.colAutoUpdate')}</TableHead>
                <TableHead>{t('documents.colUpdated')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filtered.map((d) => (
                <TableRow key={d.id}>
                  <TableCell>
                    <div className="font-medium">
                      <Link to={`/workspaces/${workspaceId}/documents/${d.id}`} className="hover:text-primary">
                        {d.title}
                      </Link>
                    </div>
                    <div className="mt-0.5 text-xs text-muted-foreground">
                      <code>{d.slug}</code>
                      {d.summary ? ` · ${d.summary.slice(0, 80)}` : ''}
                    </div>
                  </TableCell>
                  <TableCell><Badge variant="muted">{d.docType}</Badge></TableCell>
                  <TableCell><Badge variant={STATUS_VARIANT[d.status]}>{t(STATUS_LABEL_KEY[d.status])}</Badge></TableCell>
                  <TableCell>
                    {d.autoUpdateEnabled
                      ? <Badge variant="info">{t('documents.auto')}</Badge>
                      : <span className="text-xs text-muted-foreground">{t('documents.manual')}</span>}
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