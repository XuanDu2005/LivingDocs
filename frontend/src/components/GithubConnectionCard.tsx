import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Github, Loader2, Unlink, ShieldCheck } from 'lucide-react';
import { Button } from './ui/button';
import { Badge } from './ui/badge';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from './ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from './ui/dialog';
import { githubApi, GithubConnection } from '../services/github';
import { describeError } from '../services/auth';
import { listLinkedProviders } from '../services/authApi';

/**
 * Shows the current GitHub connection status for the logged-in user and lets
 * them start the OAuth flow or revoke the existing connection so they can
 * link a different GitHub account.
 *
 * <p>After V_N+1, the "login" and "repository" flows share a single
 * user-level link: if the user already linked GitHub from the profile page
 * (which writes a row in {@code user_oauth_identities}) but has not yet
 * upgraded the scope to access repositories (which writes a row in
 * {@code github_connections}), we surface a "Cấp quyền truy cập repo"
 * button instead of a full re-link flow.
 */
export function GithubConnectionCard() {
  const { t } = useTranslation();
  const [connection, setConnection] = useState<GithubConnection | null>(null);
  const [loginLinked, setLoginLinked] = useState<boolean>(false);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [connecting, setConnecting] = useState<boolean>(false);
  const [confirmOpen, setConfirmOpen] = useState<boolean>(false);
  const [disconnecting, setDisconnecting] = useState<boolean>(false);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const [conn, providers] = await Promise.all([
        githubApi.getConnection().catch(() => null),
        listLinkedProviders().catch(() => [] as Array<{ provider: string }>),
      ]);
      setConnection(conn);
      setLoginLinked(providers.some((p: { provider: string }) => p.provider === 'github'));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function onConnect() {
    setConnecting(true);
    setError(null);
    setInfo(null);
    try {
      const { url, state } = await githubApi.getAuthorizeUrl();
      sessionStorage.setItem('github-oauth-state', state);
      window.location.href = url;
    } catch (err) {
      setError(describeError(err));
      setConnecting(false);
    }
  }

  async function onDisconnect() {
    setDisconnecting(true);
    setError(null);
    setInfo(null);
    try {
      await githubApi.disconnect();
      setConnection(null);
      setInfo(t('githubCard.unlinkedInfo'));
      setConfirmOpen(false);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setDisconnecting(false);
    }
  }

  const isActive = connection?.status === 'ACTIVE';

  return (
    <Card>
      <CardHeader>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Github className="h-5 w-5" />
            <CardTitle className="text-base">
              GitHub <span className="text-xs font-normal text-muted-foreground">{t('githubCard.repositorySuffix')}</span>
            </CardTitle>
          </div>
          {loading ? (
            <Badge variant="muted">{t('githubCard.checking')}</Badge>
          ) : isActive ? (
            <Badge variant="success">{t('githubCard.connected')}</Badge>
          ) : connection ? (
            <Badge variant="warning">{connection.status}</Badge>
          ) : (
            <Badge variant="muted">{t('githubCard.notConnected')}</Badge>
          )}
        </div>
        <CardDescription>
          {t('githubCard.description')}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {loading ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            {t('githubCard.checkingStatus')}
          </div>
        ) : isActive && connection ? (
          <div className="space-y-2">
            <div className="text-sm">
              <span className="text-muted-foreground">{t('githubCard.signedInAs')} </span>
              <span className="font-semibold">@{connection.githubLogin}</span>
            </div>
            {connection.scope && (
              <div className="text-xs text-muted-foreground">
                {t('githubCard.scope')}: <span className="font-mono">{connection.scope}</span>
              </div>
            )}
            <div className="flex flex-wrap gap-2 pt-1">
              <Button
                variant="outline"
                size="sm"
                onClick={onConnect}
                disabled={connecting}
              >
                {connecting ? (
                  <>
                    <Loader2 className="mr-1 h-4 w-4 animate-spin" />
                    {t('githubCard.redirecting')}
                  </>
                ) : (
                  <>
                    <Github className="mr-1 h-4 w-4" />
                    {t('githubCard.switchAccount')}
                  </>
                )}
              </Button>
              <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
                <Button
                  variant="ghost"
                  size="sm"
                  className="text-destructive hover:text-destructive"
                  onClick={() => setConfirmOpen(true)}
                >
                  <Unlink className="mr-1 h-4 w-4" />
                  {t('githubCard.unlink')}
                </Button>
                <DialogContent>
                  <DialogHeader>
                    <DialogTitle>{t('githubCard.unlinkTitle')}</DialogTitle>
                    <DialogDescription>
                      {t('githubCard.unlinkDesc', { login: `@${connection.githubLogin}` })}
                    </DialogDescription>
                  </DialogHeader>
                  <DialogFooter>
                    <Button
                      type="button"
                      variant="outline"
                      onClick={() => setConfirmOpen(false)}
                      disabled={disconnecting}
                    >
                      {t('common.actions.cancel')}
                    </Button>
                    <Button
                      type="button"
                      variant="destructive"
                      onClick={onDisconnect}
                      disabled={disconnecting}
                    >
                      {disconnecting ? (
                        <>
                          <Loader2 className="mr-1 h-4 w-4 animate-spin" />
                          {t('githubCard.unlinking')}
                        </>
                      ) : (
                        t('githubCard.unlinkGithub')
                      )}
                    </Button>
                  </DialogFooter>
                </DialogContent>
              </Dialog>
            </div>
          </div>
        ) : loginLinked ? (
          // User already linked GitHub from the profile page but the
          // repository-connection row hasn't been created yet (different
          // scope). Offer a one-click upgrade.
          <div className="space-y-2">
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <ShieldCheck className="h-4 w-4 text-primary" />
              {t('githubCard.linkedInAccount')}
            </div>
            <p className="text-xs text-muted-foreground">
              {t('githubCard.grantRepoDesc')}
            </p>
            <Button onClick={onConnect} disabled={connecting} size="sm">
              {connecting ? (
                <>
                  <Loader2 className="mr-1 h-4 w-4 animate-spin" />
                  {t('githubCard.redirecting')}
                </>
              ) : (
                <>
                  <Github className="mr-1 h-4 w-4" />
                  {t('githubCard.grantRepoAccess')}
                </>
              )}
            </Button>
          </div>
        ) : (
          <Button onClick={onConnect} disabled={connecting} size="sm">
            {connecting ? (
              <>
                <Loader2 className="mr-1 h-4 w-4 animate-spin" />
                {t('githubCard.redirecting')}
              </>
            ) : (
              <>
                <Github className="mr-1 h-4 w-4" />
                {t('githubCard.connectGithub')}
              </>
            )}
          </Button>
        )}

        {error && (
          <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
            {error}
          </div>
        )}
        {info && (
          <div className="rounded-md border border-primary/40 bg-primary/5 p-3 text-sm text-primary">
            {info}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
