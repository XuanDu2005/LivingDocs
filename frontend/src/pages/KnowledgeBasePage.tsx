import { FormEvent, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, BookOpen, ChevronDown, ChevronRight, Database, Loader2, Search } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Badge } from '../components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger,
} from '../components/ui/dialog';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '../components/ui/select';
import { ErrorState, EmptyState } from '../components/ui/states';
import { knowledgeApi, KnowledgeHit } from '../services/knowledge';
import { codeApi, CodeEntity } from '../services/knowledge';
import { documentsApi, Document } from '../services/documents';
import { githubApi } from '../services/github';
import { describeError } from '../services/auth';

interface RepoEntities {
  repoId: string;
  repoName: string;
  entities: CodeEntity[];
  expanded: boolean;
}

export default function KnowledgeBasePage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();

  const [query, setQuery] = useState<string>('');
  const [topK, setTopK] = useState<number>(8);
  const [hits, setHits] = useState<KnowledgeHit[]>([]);
  const [docs, setDocs] = useState<Document[]>([]);
  const [repos, setRepos] = useState<{ id: string; fullName: string }[]>([]);
  const [repoEntities, setRepoEntities] = useState<RepoEntities[]>([]);
  const [searched, setSearched] = useState<boolean>(false);

  const [busy, setBusy] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  const [showIndexDialog, setShowIndexDialog] = useState<boolean>(false);
  const [indexDocId, setIndexDocId] = useState<string>('');
  const [indexing, setIndexing] = useState<boolean>(false);

  useEffect(() => {
    if (!workspaceId) return;
    documentsApi
      .list(workspaceId)
      .then(setDocs)
      .catch(() => {/* non-critical */});
    githubApi
      .listRepositories(workspaceId)
      .then(setRepos)
      .catch(() => {/* non-critical */});
  }, [workspaceId]);

  async function runSearch(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!workspaceId) return;
    setBusy(true);
    setError(null);
    setSearched(true);
    try {
      const r = await knowledgeApi.search(workspaceId, query, topK);
      setHits(r.hits);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  async function handleIndexOne() {
    if (!workspaceId || !indexDocId) return;
    setIndexing(true);
    setError(null);
    setInfo(null);
    try {
      const r = await knowledgeApi.index(workspaceId, indexDocId);
      setInfo(`Indexed ${r.chunks_indexed} chunks.`);
      setShowIndexDialog(false);
      setIndexDocId('');
    } catch (err) {
      setError(describeError(err));
    } finally {
      setIndexing(false);
    }
  }

  async function handleIndexAll() {
    if (!workspaceId || docs.length === 0) return;
    setBusy(true);
    setError(null);
    setInfo(null);
    try {
      let indexed = 0;
      for (const d of docs) {
        try {
          const r = await knowledgeApi.index(workspaceId, d.id);
          indexed += r.chunks_indexed;
        } catch {
          // skip failures
        }
      }
      setInfo(`Indexed ${indexed} chunks across ${docs.length} documents.`);
    } finally {
      setBusy(false);
    }
  }

  function toggleRepoEntities(repoId: string) {
    setRepoEntities((prev) =>
      prev.map((r) =>
        r.repoId === repoId ? { ...r, expanded: !r.expanded } : r,
      ),
    );
  }

  async function loadRepoEntities(repoId: string, repoName: string) {
    if (!workspaceId) return;
    setRepoEntities((prev) => {
      if (prev.some((r) => r.repoId === repoId)) return prev;
      return [...prev, { repoId, repoName, entities: [], expanded: true }];
    });
    const idx = repoEntities.findIndex((r) => r.repoId === repoId);
    const existing = repoEntities[idx];
    if (existing && existing.entities.length > 0) return;

    try {
      const entities = await codeApi.listEntities(workspaceId, repoId);
      setRepoEntities((prev) =>
        prev.map((r) =>
          r.repoId === repoId ? { ...r, entities } : r,
        ),
      );
    } catch {
      setRepoEntities((prev) =>
        prev.map((r) =>
          r.repoId === repoId ? { ...r, entities: [] } : r,
        ),
      );
    }
  }

  function handleToggleRepo(repoId: string, repoName: string) {
    const already = repoEntities.find((r) => r.repoId === repoId);
    if (already) {
      toggleRepoEntities(repoId);
    } else {
      void loadRepoEntities(repoId, repoName);
    }
  }

  if (!workspaceId) return <EmptyState title="Missing workspace id" />;

  return (
    <div className="space-y-6">
      <Button variant="ghost" size="sm" asChild>
        <Link to={`/workspaces/${workspaceId}`}>
          <ArrowLeft className="mr-1 h-4 w-4" /> Back to workspace
        </Link>
      </Button>

      <div>
        <h1 className="text-2xl font-bold tracking-tight">Knowledge base</h1>
        <p className="text-sm text-muted-foreground">
          Semantic search across indexed documents and code entities.
        </p>
      </div>

      {/* Search form */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <Search className="h-4 w-4" /> Semantic search
          </CardTitle>
        </CardHeader>
        <CardContent>
          <form onSubmit={runSearch} className="flex flex-col gap-3 sm:flex-row sm:items-end">
            <div className="flex-1 space-y-1.5">
              <Label htmlFor="kb-query" className="text-xs">Query</Label>
              <Input
                id="kb-query"
                type="search"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Ask the knowledge base…"
                required
              />
            </div>
            <div className="w-24 space-y-1.5">
              <Label htmlFor="kb-topk" className="text-xs">Top K</Label>
              <Input
                id="kb-topk"
                type="number"
                value={topK}
                onChange={(e) => setTopK(Math.max(1, parseInt(e.target.value, 10) || 8))}
                min={1}
                max={50}
              />
            </div>
            <Button type="submit" disabled={busy}>
              {busy ? <Loader2 className="mr-1 h-4 w-4 animate-spin" /> : <Search className="mr-1 h-4 w-4" />}
              {busy ? 'Searching…' : 'Search'}
            </Button>
          </form>
          {info && (
            <p className="mt-2 text-sm text-green-600 flex items-center gap-1">
              <BookOpen className="h-4 w-4" /> {info}
            </p>
          )}
          {error && <ErrorState message={error} />}
        </CardContent>
      </Card>

      {/* Search results */}
      {searched && hits.length === 0 && !busy && (
        <Card>
          <CardContent className="pt-6">
            <EmptyState
              title="No results"
              description="Try a different query or index more documents first."
            />
          </CardContent>
        </Card>
      )}

      {searched && hits.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">
              Top {hits.length} hits
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {hits.map((h, i) => (
              <div key={`${h.doc_id}-${i}`} className="rounded-md border bg-muted/30 p-3 space-y-1.5">
                <div className="flex items-center justify-between">
                  <span className="font-medium text-sm truncate">
                    {(h.metadata as { title?: string })?.title ?? h.doc_id}
                  </span>
                  <Badge variant="info" className="text-xs ml-2 flex-shrink-0">
                    {(h.score * 100).toFixed(1)}%
                  </Badge>
                </div>
                <p className="text-sm text-muted-foreground whitespace-pre-wrap">
                  {h.text.slice(0, 400)}
                  {h.text.length > 400 ? '…' : ''}
                </p>
                {h.metadata && (
                  <details className="mt-1">
                    <summary className="text-xs text-muted-foreground cursor-pointer">
                      Metadata
                    </summary>
                    <pre className="mt-1 rounded bg-background p-2 text-xs overflow-auto">
                      {JSON.stringify(h.metadata, null, 2)}
                    </pre>
                  </details>
                )}
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      {/* Index documents */}
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle className="text-base flex items-center gap-2">
              <Database className="h-4 w-4" /> Index documents
            </CardTitle>
            <div className="flex gap-2">
              <Button size="sm" variant="outline" onClick={() => void handleIndexAll()} disabled={busy || docs.length === 0}>
                <Database className="mr-1 h-4 w-4" /> Index all
              </Button>
              <Dialog open={showIndexDialog} onOpenChange={setShowIndexDialog}>
                <DialogTrigger asChild>
                  <Button size="sm" variant="outline" disabled={docs.length === 0}>
                    <BookOpen className="mr-1 h-4 w-4" /> Index one
                  </Button>
                </DialogTrigger>
                <DialogContent>
                  <DialogHeader>
                    <DialogTitle>Index a document</DialogTitle>
                    <DialogDescription>
                      Select a document to index into the knowledge base.
                    </DialogDescription>
                  </DialogHeader>
                  <div className="space-y-3 py-2">
                    <Select value={indexDocId} onValueChange={setIndexDocId}>
                      <SelectTrigger>
                        <SelectValue placeholder="Select document…" />
                      </SelectTrigger>
                      <SelectContent>
                        {docs.map((d) => (
                          <SelectItem key={d.id} value={d.id}>
                            {d.title}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                  <DialogFooter>
                    <Button variant="outline" onClick={() => setShowIndexDialog(false)}>Cancel</Button>
                    <Button onClick={() => void handleIndexOne()} disabled={!indexDocId || indexing}>
                      {indexing ? 'Indexing…' : 'Index'}
                    </Button>
                  </DialogFooter>
                </DialogContent>
              </Dialog>
            </div>
          </div>
        </CardHeader>
        <CardContent>
          {docs.length === 0 ? (
            <p className="text-sm text-muted-foreground">No documents found in this workspace.</p>
          ) : (
            <div className="space-y-1">
              {docs.map((d) => (
                <div key={d.id} className="flex items-center justify-between rounded border px-3 py-2">
                  <div>
                    <div className="text-sm font-medium">{d.title}</div>
                    <div className="text-xs text-muted-foreground">
                      <code>{d.slug}</code> · {d.docType}
                    </div>
                  </div>
                  <Button
                    size="sm"
                    variant="ghost"
                    className="h-7 text-xs"
                    onClick={() => void (async () => {
                      if (!workspaceId) return;
                      setBusy(true);
                      setError(null);
                      setInfo(null);
                      try {
                        const r = await knowledgeApi.index(workspaceId, d.id);
                        setInfo(`Indexed ${r.chunks_indexed} chunks from "${d.title}".`);
                      } catch (err) {
                        setError(describeError(err));
                      } finally {
                        setBusy(false);
                      }
                    })()}
                    disabled={busy}
                  >
                    Index
                  </Button>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Code entities */}
      {repos.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Code entities</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            {repos.map((repo) => {
              const state = repoEntities.find((r) => r.repoId === repo.id);
              return (
                <div key={repo.id} className="rounded-md border">
                  <button
                    type="button"
                    onClick={() => handleToggleRepo(repo.id, repo.fullName)}
                    className="flex w-full items-center gap-2 px-3 py-2 text-left hover:bg-muted/50 transition-colors"
                  >
                    {state?.expanded ? (
                      <ChevronDown className="h-4 w-4 text-muted-foreground flex-shrink-0" />
                    ) : (
                      <ChevronRight className="h-4 w-4 text-muted-foreground flex-shrink-0" />
                    )}
                    <span className="text-sm font-medium">{repo.fullName}</span>
                    {state && (
                      <Badge variant="muted" className="ml-auto text-xs">
                        {state.entities.length} entities
                      </Badge>
                    )}
                  </button>

                  {state?.expanded && (
                    <div className="border-t px-3 py-2 space-y-1 max-h-64 overflow-auto">
                      {state.entities.length === 0 ? (
                        <p className="text-xs text-muted-foreground py-2">
                          No code entities indexed for this repository yet.
                        </p>
                      ) : (
                        state.entities.map((e) => (
                          <div key={e.id} className="rounded bg-muted/30 px-2 py-1.5">
                            <div className="flex items-center gap-2">
                              <Badge variant="muted" className="text-xs">{e.entityType}</Badge>
                              <code className="text-xs font-mono truncate">{e.qualifiedName}</code>
                            </div>
                            <div className="text-xs text-muted-foreground mt-0.5">
                              {e.language} · {e.filePath}:{e.startLine}
                              {e.docstring && ` — ${e.docstring.slice(0, 60)}`}
                            </div>
                          </div>
                        ))
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </CardContent>
        </Card>
      )}
    </div>
  );
}
