import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Github,
  KeyRound,
  Loader2,
  ShieldCheck,
  ShieldQuestion,
} from 'lucide-react';
import { Button } from './ui/button';
import { Badge } from './ui/badge';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from './ui/dialog';
import { githubScopesApi, ScopeOverview } from '../services/githubScopes';
import { describeError } from '../services/auth';

/**
 * Lets the user review — and narrow — the repository access scopes the
 * LivingDocs GitHub application was granted.
 *
 * <p>The OAuth handshake asks GitHub for a fixed scope set. GitHub does
 * not let a token shed a scope on its own, so "withdrawing" a scope here
 * means re-consenting with a narrower set: the dialog lists what is in
 * force, explains what each scope is used for, and — for the scopes the
 * platform can do without — offers a one-click re-authorization with
 * the reduced set.
 */
export function GithubScopesDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const { t } = useTranslation();
  const [overview, setOverview] = useState<ScopeOverview | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [busyScope, setBusyScope] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      setOverview(await githubScopesApi.overview());
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  /**
   * Re-consent with the currently-granted scopes minus the one the user
   * asked to drop.
   */
  async function handleNarrow(scope: string) {
    if (!overview) return;
    const remaining = overview.available
      .filter((s) => s.granted && s.scope !== scope)
      .map((s) => s.scope);
    if (remaining.length === 0) {
      setError(t('githubScopes.cannotDropAll'));
      return;
    }
    setBusyScope(scope);
    setError(null);
    try {
      const { url, state } = await githubScopesApi.reauthorize(remaining);
      sessionStorage.setItem('github-oauth-state', state);
      window.location.href = url;
    } catch (err) {
      setError(describeError(err));
      setBusyScope(null);
    }
  }

  const granted = overview?.available.filter((s) => s.granted) ?? [];
  const optional = overview?.available.filter((s) => s.granted && s.scope !== 'repo' && s.scope !== 'read:user') ?? [];
  const available = overview?.available.filter((s) => !s.granted) ?? [];

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl max-h-[80vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <KeyRound className="h-5 w-5" /> {t('githubScopes.title')}
          </DialogTitle>
          <DialogDescription>{t('githubScopes.description')}</DialogDescription>
        </DialogHeader>

        {loading ? (
          <div className="flex items-center gap-2 py-6 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" /> {t('common.loading')}
          </div>
        ) : error ? (
          <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
            {error}
          </div>
        ) : !overview ? (
          <p className="text-sm text-muted-foreground">{t('githubScopes.noConnection')}</p>
        ) : (
          <div className="space-y-4">
            <div className="flex items-center gap-2 text-sm">
              <Github className="h-4 w-4" />
              {overview.githubLogin ? (
                <span>
                  <span className="text-muted-foreground">{t('githubScopes.signedInAs')} </span>
                  <span className="font-semibold">@{overview.githubLogin}</span>
                </span>
              ) : (
                <span className="text-muted-foreground">{t('githubScopes.noConnection')}</span>
              )}
              {overview.connectionStatus && (
                <Badge variant={overview.connectionStatus === 'ACTIVE' ? 'success' : 'muted'} className="text-[10px]">
                  {overview.connectionStatus}
                </Badge>
              )}
            </div>

            <section className="space-y-2">
              <h3 className="text-sm font-semibold flex items-center gap-1.5">
                <ShieldCheck className="h-4 w-4 text-green-600" />
                {t('githubScopes.grantedTitle', 'Granted scopes')}
              </h3>
              {granted.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t('githubScopes.noneGranted')}</p>
              ) : (
                <div className="space-y-2">
                  {granted.map((s) => (
                    <div key={s.scope} className="rounded-md border px-3 py-2">
                      <div className="flex items-center justify-between gap-2">
                        <code className="text-xs font-mono font-semibold">{s.scope}</code>
                        {optional.some((o) => o.scope === s.scope) && (
                          <Button
                            size="sm"
                            variant="outline"
                            className="h-7 text-xs"
                            onClick={() => void handleNarrow(s.scope)}
                            disabled={busyScope !== null}
                          >
                            {busyScope === s.scope ? (
                              <Loader2 className="mr-1 h-3 w-3 animate-spin" />
                            ) : null}
                            {t('githubScopes.drop', 'Drop')}
                          </Button>
                        )}
                      </div>
                      <p className="text-xs text-muted-foreground mt-1">{s.description}</p>
                      <p className="text-xs text-muted-foreground/80 mt-0.5">{s.usage}</p>
                    </div>
                  ))}
                </div>
              )}
              {optional.length > 0 && (
                <p className="text-xs text-muted-foreground">
                  {t('githubScopes.dropHint', 'Dropping a scope re-opens the GitHub consent screen with the reduced set — GitHub does not let a token shed a scope on its own.')}
                </p>
              )}
            </section>

            {available.length > 0 && (
              <section className="space-y-2">
                <h3 className="text-sm font-semibold flex items-center gap-1.5">
                  <ShieldQuestion className="h-4 w-4 text-muted-foreground" />
                  {t('githubScopes.availableTitle', 'Not granted')}
                </h3>
                <div className="space-y-1">
                  {available.map((s) => (
                    <div key={s.scope} className="rounded-md border border-dashed px-3 py-2">
                      <code className="text-xs font-mono">{s.scope}</code>
                      <p className="text-xs text-muted-foreground mt-1">{s.description}</p>
                      <p className="text-xs text-muted-foreground/80 mt-0.5">{s.usage}</p>
                    </div>
                  ))}
                </div>
              </section>
            )}
          </div>
        )}

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {t('common.actions.cancel')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
