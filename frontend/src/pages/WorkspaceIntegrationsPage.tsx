import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { AxiosError } from 'axios';
import {
  ArrowLeft,
  Github,
  GitBranch,
  MessageSquare,
  Plug,
  ShieldAlert,
  ShieldCheck,
} from 'lucide-react';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { ConnectionDialog } from '../components/ConnectionDialog';
import { describeError } from '../services/auth';
import { integrationsApi } from '../services/integrations';
import {
  Connection,
  IntegrationProvider,
  PROVIDER_DESCRIPTIONS,
  PROVIDER_LABELS,
  STATUS_LABELS,
} from '../types/integrations';

const SCOPED_PROVIDERS: IntegrationProvider[] = ['GITHUB', 'GITLAB', 'JIRA'];

function providerIcon(p: IntegrationProvider) {
  switch (p) {
    case 'GITHUB': return Github;
    case 'GITLAB': return GitBranch;
    case 'JIRA':   return ShieldCheck;
    case 'SLACK':  return MessageSquare;
  }
}

/**
 * Per-workspace view of the integrations linked to this workspace.
 *
 * <p>Slack is platform-wide so it does not appear here. Only SCM and
 * Jira are scoped — exactly what workspace members need to see and
 * configure.
 */
export default function WorkspaceIntegrationsPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { t } = useTranslation();

  const [rows2, setRows] = useState<Connection[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState<boolean>(false);
  const [editingProvider, setEditingProvider] = useState<IntegrationProvider | null>(null);

  async function load() {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    setForbidden(false);
    try {
      const conns = await integrationsApi.listWorkspaceConnections(workspaceId);
      setRows(conns);
    } catch (err) {
      // 403 means the user is not a member of this workspace — show a
      // dedicated message instead of the generic error string.
      if (err instanceof AxiosError && err.response?.status === 403) {
        setForbidden(true);
      } else {
        setError(describeError(err));
      }
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, [workspaceId]);

  if (!workspaceId) return null;

  return (
    <div className="space-y-6">
      <Button variant="ghost" size="sm" asChild>
        <Link to={`/workspaces/${workspaceId}`}>
          <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}
        </Link>
      </Button>

      <div>
        <h1 className="text-2xl font-bold tracking-tight flex items-center gap-2">
          <Plug className="h-5 w-5" /> {t('integrations.workspaceTitle')}
        </h1>
        <p className="text-sm text-muted-foreground">
          {t('integrations.workspaceSubtitle')}
        </p>
      </div>

      {forbidden && (
        <div className="rounded-md border border-amber-500/40 bg-amber-500/10 p-4 text-sm text-amber-700 dark:text-amber-300 flex items-start gap-3">
          <ShieldAlert className="h-4 w-4 mt-0.5 shrink-0" />
          <div>
            <div className="font-medium">{t('integrations.forbiddenTitle')}</div>
            <p className="mt-1 text-amber-700/80 dark:text-amber-300/80">
              {t('integrations.forbiddenWorkspace')}
            </p>
          </div>
        </div>
      )}
      {error && <ErrorState message={error} />}

      {loading ? (
        <LoadingState message={t('integrations.loading')} />
      ) : (
        <div className="grid gap-4 md:grid-cols-3">
          {SCOPED_PROVIDERS.map((p) => {
            const Icon = providerIcon(p);
            const matches = rows2.filter((c) => c.provider === p);
            return (
              <Card key={p}>
                <CardHeader>
                  <CardTitle className="flex items-center gap-2 text-base">
                    <Icon className="h-4 w-4" /> {PROVIDER_LABELS[p]}
                  </CardTitle>
                  <CardDescription>{PROVIDER_DESCRIPTIONS[p]}</CardDescription>
                </CardHeader>
                <CardContent className="space-y-3">
                  {matches.length === 0 ? (
                    <EmptyState
                      title={t('integrations.noConnectionsTitle')}
                      description={t('integrations.noConnectionsDesc', { provider: PROVIDER_LABELS[p] })}
                    />
                  ) : (
                    <ul className="space-y-2">
                      {matches.map((c) => (
                        <li
                          key={c.id}
                          className="rounded-md border bg-muted/30 px-3 py-2 space-y-1"
                        >
                          <div className="flex items-center justify-between gap-2">
                            <span className="text-sm font-medium truncate">
                              {c.displayName}
                            </span>
                            <Badge
                              variant={c.status === 'ACTIVE' ? 'success' : 'muted'}
                              className="text-[10px]"
                            >
                              {STATUS_LABELS[c.status]}
                            </Badge>
                          </div>
                          <div className="text-xs text-muted-foreground truncate">
                            {c.externalAccount}
                          </div>
                          {c.lastUsedAt && (
                            <div className="text-[11px] text-muted-foreground">
                              {t('integrations.lastUsed', {
                                when: new Date(c.lastUsedAt).toLocaleString(),
                              })}
                            </div>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={forbidden}
                    onClick={() => setEditingProvider(p)}
                  >
                    {matches.length === 0
                      ? t('integrations.linkPrompt')
                      : t('integrations.addAnother')}
                  </Button>
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}

      <ConnectionDialog
        open={editingProvider !== null}
        provider={editingProvider}
        workspaceId={workspaceId}
        scope="workspace"
        existing={null}
        onClose={() => setEditingProvider(null)}
        onSaved={async () => {
          setEditingProvider(null);
          await load();
        }}
      />
    </div>
  );
}