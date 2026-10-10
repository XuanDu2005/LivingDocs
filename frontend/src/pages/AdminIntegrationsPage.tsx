import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { AxiosError } from 'axios';
import {
  ArrowLeft,
  CheckCircle2,
  Loader2,
  Plug,
  PlugZap,
  Trash2,
  XCircle,
  Github,
  GitBranch,
  MessageSquare,
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
  EventRow,
  IntegrationProvider,
  PROVIDER_DESCRIPTIONS,
  PROVIDER_LABELS,
  STATUS_LABELS,
  TestIntegrationResult,
} from '../types/integrations';

const PROVIDERS: IntegrationProvider[] = ['GITHUB', 'GITLAB', 'JIRA', 'SLACK'];

function providerIcon(p: IntegrationProvider) {
  switch (p) {
    case 'GITHUB': return Github;
    case 'GITLAB': return GitBranch;
    case 'JIRA':   return ShieldCheck;
    case 'SLACK':  return MessageSquare;
  }
}

/**
 * Admin console for every external integration on the platform.
 *
 * <p>One card per provider summarises the status of every connection
 * linked to that provider and exposes the connect / disconnect / test
 * actions. A second panel shows recent inbound webhook deliveries.
 */
export default function AdminIntegrationsPage() {
  const { t } = useTranslation();

  const [connections, setConnections] = useState<Connection[]>([]);
  const [events, setEvents] = useState<EventRow[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState<boolean>(false);

  const [editingProvider, setEditingProvider] = useState<IntegrationProvider | null>(null);
  const [editingExisting, setEditingExisting] = useState<Connection | null>(null);

  const [testingId, setTestingId] = useState<string | null>(null);
  const [testResult, setTestResult] = useState<Record<string, TestIntegrationResult>>({});

  async function load() {
    setLoading(true);
    setError(null);
    setForbidden(false);
    try {
      const [conns, evs] = await Promise.all([
        integrationsApi.listAdminConnections(),
        integrationsApi.listAdminEvents(30).catch(() => [] as EventRow[]),
      ]);
      setConnections(conns);
      setEvents(evs);
    } catch (err) {
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
  }, []);

  async function onTest(id: string) {
    setTestingId(id);
    try {
      const r = await integrationsApi.testAdminConnection(id);
      setTestResult((prev) => ({ ...prev, [id]: r }));
      await load();
    } catch (err) {
      setTestResult((prev) => ({
        ...prev,
        [id]: { ok: false, message: describeError(err) },
      }));
    } finally {
      setTestingId(null);
    }
  }

  async function onRevoke(id: string) {
    if (!confirm(t('integrations.confirmRevoke'))) return;
    try {
      await integrationsApi.revokeAdminConnection(id);
      await load();
    } catch (err) {
      setError(describeError(err));
    }
  }

  function connectionsOf(p: IntegrationProvider): Connection[] {
    return connections.filter((c) => c.provider === p);
  }

  return (
    <div className="space-y-6">
      <div>
        <Button variant="ghost" size="sm" asChild>
          <Link to="/admin">
            <ArrowLeft className="mr-1 h-4 w-4" />
            {t('integrations.backToAdmin')}
          </Link>
        </Button>
        <h1 className="mt-2 text-2xl font-bold tracking-tight flex items-center gap-2">
          <PlugZap className="h-5 w-5" /> {t('integrations.title')}
        </h1>
        <p className="text-sm text-muted-foreground">{t('integrations.subtitle')}</p>
      </div>

      {forbidden && (
        <div className="rounded-md border border-amber-500/40 bg-amber-500/10 p-4 text-sm text-amber-700 dark:text-amber-300 flex items-start gap-3">
          <ShieldAlert className="h-4 w-4 mt-0.5 shrink-0" />
          <div>
            <div className="font-medium">{t('integrations.forbiddenTitle')}</div>
            <p className="mt-1 text-amber-700/80 dark:text-amber-300/80">
              {t('integrations.forbiddenAdmin')}
            </p>
          </div>
        </div>
      )}
      {error && <ErrorState message={error} />}

      {loading ? (
        <LoadingState message={t('integrations.loading')} />
      ) : forbidden ? null : (
        <div className="grid gap-4 md:grid-cols-2">
          {PROVIDERS.map((p) => {
            const Icon = providerIcon(p);
            const rows = connectionsOf(p);
            return (
              <Card key={p}>
                <CardHeader>
                  <CardTitle className="flex items-center gap-2 text-base">
                    <Icon className="h-4 w-4" /> {PROVIDER_LABELS[p]}
                  </CardTitle>
                  <CardDescription>{PROVIDER_DESCRIPTIONS[p]}</CardDescription>
                </CardHeader>
                <CardContent className="space-y-3">
                  {rows.length === 0 ? (
                    <EmptyState
                      title={t('integrations.noConnectionsTitle')}
                      description={t('integrations.noConnectionsDesc', { provider: PROVIDER_LABELS[p] })}
                    />
                  ) : (
                    <ul className="space-y-2">
                      {rows.map((c) => {
                        const tr = testResult[c.id];
                        return (
                          <li
                            key={c.id}
                            className="rounded-md border bg-muted/30 px-3 py-2 space-y-2"
                          >
                            <div className="flex items-center justify-between gap-2">
                              <div className="min-w-0 flex-1">
                                <div className="text-sm font-medium truncate">
                                  {c.displayName}
                                </div>
                                <div className="text-xs text-muted-foreground truncate">
                                  {c.externalAccount}
                                  {c.baseUrl ? ` · ${c.baseUrl}` : ''}
                                </div>
                              </div>
                              <Badge
                                variant={c.status === 'ACTIVE' ? 'success' : c.status === 'ERROR' ? 'destructive' : 'muted'}
                                className="text-[10px]"
                              >
                                {STATUS_LABELS[c.status]}
                              </Badge>
                            </div>

                            <div className="flex flex-wrap items-center gap-2 text-[11px]">
                              <Badge variant="outline" className="text-[10px]">
                                {t('integrations.token')}: {c.hasAccessToken
                                  ? t('integrations.configured')
                                  : t('integrations.notConfigured')}
                              </Badge>
                              {p !== 'SLACK' && (
                                <Badge variant="outline" className="text-[10px]">
                                  {t('integrations.webhook')}: {c.hasWebhookSecret
                                    ? t('integrations.configured')
                                    : t('integrations.notConfigured')}
                                </Badge>
                              )}
                              {c.scopes && (
                                <code className="text-[10px] text-muted-foreground truncate max-w-[200px]">
                                  {c.scopes}
                                </code>
                              )}
                            </div>

                            {tr.ok ? (
                              <div className="flex items-center gap-1 text-xs text-green-700">
                                <CheckCircle2 className="h-3 w-3" /> {tr.message}
                              </div>
                            ) : tr ? (
                              <div className="flex items-center gap-1 text-xs text-destructive">
                                <XCircle className="h-3 w-3" /> {tr.message}
                              </div>
                            ) : null}

                            <div className="flex flex-wrap gap-2">
                              <Button
                                size="sm"
                                variant="outline"
                                onClick={() => onTest(c.id)}
                                disabled={testingId === c.id}
                              >
                                {testingId === c.id ? (
                                  <Loader2 className="mr-1 h-3 w-3 animate-spin" />
                                ) : (
                                  <CheckCircle2 className="mr-1 h-3 w-3" />
                                )}
                                {t('integrations.test')}
                              </Button>
                              <Button
                                size="sm"
                                variant="secondary"
                                onClick={() => {
                                  setEditingProvider(p);
                                  setEditingExisting(c);
                                }}
                              >
                                {t('common.actions.edit')}
                              </Button>
                              <Button
                                size="sm"
                                variant="ghost"
                                className="text-destructive"
                                onClick={() => onRevoke(c.id)}
                              >
                                <Trash2 className="mr-1 h-3 w-3" />
                                {t('integrations.revoke')}
                              </Button>
                            </div>
                          </li>
                        );
                      })}
                    </ul>
                  )}

                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => {
                      setEditingProvider(p);
                      setEditingExisting(null);
                    }}
                  >
                    <Plug className="mr-1 h-3 w-3" /> {t('integrations.addConnection')}
                  </Button>
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">{t('integrations.recentTitle')}</CardTitle>
          <CardDescription>{t('integrations.recentDesc')}</CardDescription>
        </CardHeader>
        <CardContent>
          {events.length === 0 ? (
            <EmptyState
              title={t('integrations.eventsEmptyTitle')}
              description={t('integrations.eventsEmptyDesc')}
            />
          ) : (
            <ul className="space-y-1.5">
              {events.map((ev) => (
                <li
                  key={ev.id}
                  className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md border bg-muted/20 px-3 py-2 text-xs"
                >
                  <Badge variant="outline" className="text-[10px]">{ev.provider}</Badge>
                  <code className="text-[11px]">{ev.eventType}</code>
                  <Badge
                    variant={ev.status === 'PROCESSED' ? 'success' : ev.status === 'FAILED' ? 'destructive' : 'muted'}
                    className="text-[10px]"
                  >
                    {ev.status}
                  </Badge>
                  {ev.errorMessage && (
                    <span className="text-destructive truncate">{ev.errorMessage}</span>
                  )}
                  <span className="text-muted-foreground">
                    {new Date(ev.receivedAt).toLocaleString()}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      <ConnectionDialog
        open={editingProvider !== null}
        provider={editingProvider}
        existing={editingExisting}
        scope="admin"
        onClose={() => {
          setEditingProvider(null);
          setEditingExisting(null);
        }}
        onSaved={async () => {
          setEditingProvider(null);
          setEditingExisting(null);
          await load();
        }}
      />
    </div>
  );
}
