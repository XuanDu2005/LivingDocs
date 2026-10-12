import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { GitCommit, ExternalLink, RefreshCw, Search } from 'lucide-react';
import { Button } from './ui/button';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';
import { Input } from './ui/input';
import { ErrorState, LoadingState } from './ui/states';
import { githubApi, Commit } from '../services/github';
import { describeError } from '../services/auth';
import { format } from 'date-fns';

interface Props {
  workspaceId: string;
  repositoryId: string;
}

/**
 * Per-repository commits panel. Lets a developer browse the recent commit
 * log ingested from GitHub, search by message / author, and trigger a
 * refresh from the GitHub API.
 */
export default function CommitsPanel({ workspaceId, repositoryId }: Props) {
  const { t } = useTranslation();
  const [commits, setCommits] = useState<Commit[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [ingesting, setIngesting] = useState<boolean>(false);
  const [search, setSearch] = useState<string>('');
  const [info, setInfo] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const list = await githubApi.listCommits(workspaceId, repositoryId, 100);
      setCommits(list);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  async function handleIngest() {
    setIngesting(true);
    setError(null);
    setInfo(null);
    try {
      const result = await githubApi.ingestCommits(workspaceId, repositoryId, 50);
      setInfo(t('commits.ingested', { count: result.ingested }));
      await load();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setIngesting(false);
    }
  }

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaceId, repositoryId]);

  const filtered = commits.filter((c) => {
    if (!search.trim()) return true;
    const q = search.toLowerCase();
    return (
      (c.message || '').toLowerCase().includes(q) ||
      (c.authorName || '').toLowerCase().includes(q) ||
      c.shortSha.toLowerCase().includes(q)
    );
  });

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between gap-2">
          <CardTitle className="text-base flex items-center gap-2">
            <GitCommit className="h-4 w-4" /> {t('commits.title', 'Recent commits')}
          </CardTitle>
          <div className="flex items-center gap-2">
            <div className="relative w-48">
              <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground" />
              <Input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder={t('commits.searchPlaceholder', 'Filter by message, author, SHA…')}
                className="h-8 pl-7 text-xs"
              />
            </div>
            <Button
              size="sm"
              variant="outline"
              onClick={() => void handleIngest()}
              disabled={ingesting}
            >
              <RefreshCw className={`mr-1 h-3 w-3 ${ingesting ? 'animate-spin' : ''}`} />
              {t('commits.ingest', 'Ingest from GitHub')}
            </Button>
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        {info && (
          <div className="rounded border border-green-500/30 bg-green-500/10 px-3 py-1.5 text-xs text-green-700 dark:text-green-300">
            {info}
          </div>
        )}
        {error && <ErrorState message={error} />}
        {loading ? (
          <LoadingState message={t('commits.loading', 'Loading commits…')} />
        ) : filtered.length === 0 ? (
          <p className="text-sm text-muted-foreground py-6 text-center">
            {search
              ? t('commits.noMatch', 'No commits match your filter.')
              : t('commits.empty', 'No commits ingested yet. Click "Ingest from GitHub" to fetch the latest 50.')}
          </p>
        ) : (
          <ul className="divide-y">
            {filtered.map((c) => (
              <li key={c.id} className="py-2.5 flex items-start gap-3">
                <code className="text-[11px] font-mono bg-muted px-1.5 py-0.5 rounded shrink-0">
                  {c.shortSha}
                </code>
                <div className="min-w-0 flex-1">
                  <div className="text-sm truncate">
                    {c.message || <span className="text-muted-foreground italic">(no message)</span>}
                  </div>
                  <div className="text-[11px] text-muted-foreground mt-0.5 flex items-center gap-2 flex-wrap">
                    <span>{c.authorName || '—'}</span>
                    {c.authoredAt && (
                      <span>{format(new Date(c.authoredAt), 'MMM d, yyyy HH:mm')}</span>
                    )}
                    {c.branch && <span>· {c.branch}</span>}
                  </div>
                </div>
                {c.url && (
                  <a
                    href={c.url}
                    target="_blank"
                    rel="noreferrer noopener"
                    className="shrink-0 text-blue-600 dark:text-blue-400 hover:underline flex items-center gap-1 text-xs"
                    title="View commit on GitHub"
                  >
                    <ExternalLink className="h-3 w-3" />
                  </a>
                )}
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}
