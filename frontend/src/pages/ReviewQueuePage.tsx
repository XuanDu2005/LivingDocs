import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, CheckCircle, Clock, Shield, XCircle } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Card, CardContent } from '../components/ui/card';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '../components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../components/ui/tabs';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { reviewsApi, DocumentReview, ReviewDecision } from '../services/drift';
import { documentsApi } from '../services/documents';
import { describeError } from '../services/auth';
import { format } from 'date-fns';

const DECISION_ICON: Record<ReviewDecision, React.ReactNode> = {
  APPROVED: <CheckCircle className="h-4 w-4 text-green-600" />,
  REJECTED: <XCircle className="h-4 w-4 text-red-600" />,
  REQUEST_CHANGES: <Clock className="h-4 w-4 text-orange-500" />,
  COMMENT: <Shield className="h-4 w-4 text-blue-500" />,
};

const DECISION_VARIANT: Record<ReviewDecision, 'success' | 'destructive' | 'warning' | 'info'> = {
  APPROVED: 'success',
  REJECTED: 'destructive',
  REQUEST_CHANGES: 'warning',
  COMMENT: 'info',
};

interface ReviewWithDoc extends DocumentReview {
  documentId: string;
  documentTitle?: string;
  versionNumber?: number;
}

export default function ReviewQueuePage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();

  const [reviews, setReviews] = useState<ReviewWithDoc[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    try {
      const myReviews = await reviewsApi.myReviews();

      // Enrich with document titles
      const docs = await documentsApi.list(workspaceId);

      const enriched: ReviewWithDoc[] = [];
      for (const r of myReviews) {
        // We need documentId — try to find from timeline
        for (const doc of docs) {
          const tl = await documentsApi.timeline(doc.id).catch(() => []);
          if (tl.some((v) => v.id === r.documentVersionId)) {
            enriched.push({
              ...r,
              documentId: doc.id,
              documentTitle: doc.title,
              versionNumber: tl.find((v) => v.id === r.documentVersionId)?.versionNumber,
            });
            break;
          }
        }
      }
      setReviews(enriched);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, [workspaceId]);

  if (!workspaceId) return <EmptyState title="Missing workspace id" />;

  const pending = reviews.filter((r) => r.decision === 'APPROVED' || r.decision === 'REQUEST_CHANGES');
  const all = reviews;

  return (
    <div className="space-y-4">
      <Button variant="ghost" size="sm" asChild>
        <Link to={`/workspaces/${workspaceId}`}>
          <ArrowLeft className="mr-1 h-4 w-4" /> Back to workspace
        </Link>
      </Button>

      <div>
        <h1 className="text-2xl font-bold tracking-tight">Review queue</h1>
        <p className="text-sm text-muted-foreground">
          Document versions pending your review.
        </p>
      </div>

      {error && <ErrorState message={error} />}
      {loading && <LoadingState message="Loading reviews…" />}

      {!loading && !error && (
        <Tabs defaultValue="pending">
          <TabsList>
            <TabsTrigger value="pending">
              Pending on me
              {pending.length > 0 && (
                <Badge variant="warning" className="ml-2">{pending.length}</Badge>
              )}
            </TabsTrigger>
            <TabsTrigger value="all">
              All
              <Badge variant="muted" className="ml-2">{all.length}</Badge>
            </TabsTrigger>
          </TabsList>

          <TabsContent value="pending">
            {pending.length === 0 ? (
              <Card>
                <CardContent className="pt-6">
                  <EmptyState
                    title="Nothing pending"
                    description="No reviews assigned to you right now."
                  />
                </CardContent>
              </Card>
            ) : (
              <ReviewTable reviews={pending} workspaceId={workspaceId} />
            )}
          </TabsContent>

          <TabsContent value="all">
            {all.length === 0 ? (
              <Card>
                <CardContent className="pt-6">
                  <EmptyState
                    title="No reviews yet"
                    description="Reviews will appear here once documents are submitted for review."
                  />
                </CardContent>
              </Card>
            ) : (
              <ReviewTable reviews={all} workspaceId={workspaceId} />
            )}
          </TabsContent>
        </Tabs>
      )}
    </div>
  );
}

function ReviewTable({ reviews, workspaceId }: { reviews: ReviewWithDoc[]; workspaceId: string }) {
  return (
    <Card>
      <CardContent className="p-0">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Decision</TableHead>
              <TableHead>Document</TableHead>
              <TableHead>Version</TableHead>
              <TableHead>Reviewer role</TableHead>
              <TableHead>Comment</TableHead>
              <TableHead>Decided</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {reviews.map((r) => (
              <TableRow key={r.id}>
                <TableCell>
                  <div className="flex items-center gap-1.5">
                    {DECISION_ICON[r.decision]}
                    <Badge variant={DECISION_VARIANT[r.decision]}>{r.decision}</Badge>
                  </div>
                </TableCell>
                <TableCell>
                  {r.documentTitle ? (
                    <Link
                      to={`/workspaces/${workspaceId}/documents/${r.documentId}`}
                      className="font-medium hover:text-primary"
                    >
                      {r.documentTitle}
                    </Link>
                  ) : (
                    <code className="text-xs">{r.documentId}</code>
                  )}
                </TableCell>
                <TableCell>
                  {r.versionNumber !== undefined ? (
                    <Badge variant="muted">v{r.versionNumber}</Badge>
                  ) : (
                    '—'
                  )}
                </TableCell>
                <TableCell>
                  <Badge variant="info">{r.reviewerRole}</Badge>
                </TableCell>
                <TableCell className="max-w-[200px] text-sm">
                  {r.comment ? (
                    <span className="truncate block">{r.comment}</span>
                  ) : (
                    <span className="text-muted-foreground">—</span>
                  )}
                </TableCell>
                <TableCell className="text-xs text-muted-foreground whitespace-nowrap">
                  {format(new Date(r.decidedAt), 'MMM d, yyyy HH:mm')}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}
