import { useEffect, useState } from 'react';
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
      setInfo('GitHub account unlinked. You can now connect a different account.');
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
              GitHub <span className="text-xs font-normal text-muted-foreground">(Repository)</span>
            </CardTitle>
          </div>
          {loading ? (
            <Badge variant="muted">Checking…</Badge>
          ) : isActive ? (
            <Badge variant="success">Connected</Badge>
          ) : connection ? (
            <Badge variant="warning">{connection.status}</Badge>
          ) : (
            <Badge variant="muted">Not connected</Badge>
          )}
        </div>
        <CardDescription>
          Liên kết GitHub cho <strong>repository</strong>: dùng để duyệt repo và
          đồng bộ pull request vào workspace. Nếu bạn đã liên kết GitHub ở
          phần Tài khoản thì chỉ cần bấm “Cấp quyền truy cập repo” một lần.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {loading ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            Checking connection status…
          </div>
        ) : isActive && connection ? (
          <div className="space-y-2">
            <div className="text-sm">
              <span className="text-muted-foreground">Signed in as </span>
              <span className="font-semibold">@{connection.githubLogin}</span>
            </div>
            {connection.scope && (
              <div className="text-xs text-muted-foreground">
                Scope: <span className="font-mono">{connection.scope}</span>
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
                    Redirecting…
                  </>
                ) : (
                  <>
                    <Github className="mr-1 h-4 w-4" />
                    Switch account
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
                  Unlink
                </Button>
                <DialogContent>
                  <DialogHeader>
                    <DialogTitle>Unlink GitHub account?</DialogTitle>
                    <DialogDescription>
                      This will remove the link between your LivingDocs
                      account (<span className="font-semibold">@{connection.githubLogin}</span>)
                      and your GitHub access token. Existing imported
                      repositories will remain, but you will need to
                      re-authorise to ingest new data.
                    </DialogDescription>
                  </DialogHeader>
                  <DialogFooter>
                    <Button
                      type="button"
                      variant="outline"
                      onClick={() => setConfirmOpen(false)}
                      disabled={disconnecting}
                    >
                      Cancel
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
                          Unlinking…
                        </>
                      ) : (
                        'Unlink GitHub'
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
              GitHub đã được liên kết ở phần Tài khoản.
            </div>
            <p className="text-xs text-muted-foreground">
              Cấp thêm quyền truy cập repository để có thể đồng bộ repo vào
              workspace.
            </p>
            <Button onClick={onConnect} disabled={connecting} size="sm">
              {connecting ? (
                <>
                  <Loader2 className="mr-1 h-4 w-4 animate-spin" />
                  Redirecting…
                </>
              ) : (
                <>
                  <Github className="mr-1 h-4 w-4" />
                  Cấp quyền truy cập repo
                </>
              )}
            </Button>
          </div>
        ) : (
          <Button onClick={onConnect} disabled={connecting} size="sm">
            {connecting ? (
              <>
                <Loader2 className="mr-1 h-4 w-4 animate-spin" />
                Redirecting…
              </>
            ) : (
              <>
                <Github className="mr-1 h-4 w-4" />
                Connect GitHub
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
