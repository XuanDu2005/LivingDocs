import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { FaGithub, FaGoogle } from 'react-icons/fa';
import { Link2, Loader2, Unlink } from 'lucide-react';

import { Button } from '../ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '../ui/card';
import { useAuth } from '../../contexts/AuthContext';
import { describeError } from '../../services/auth';
import { startLinkProvider, unlinkProvider } from '../../services/authApi';

const ALL_PROVIDERS: Array<{
  id: 'google' | 'github';
  name: string;
  shortLabel: string;
  icon: React.ReactNode;
  hintKey: string;
}> = [
  {
    id: 'google',
    name: 'Google',
    shortLabel: 'Google',
    icon: <FaGoogle className="h-5 w-5" />,
    hintKey: 'linkedAccounts.hintGoogle',
  },
  {
    id: 'github',
    name: 'GitHub',
    shortLabel: 'GitHub',
    icon: <FaGithub className="h-5 w-5" />,
    hintKey: 'linkedAccounts.hintGithub',
  },
];

export interface LinkedAccountsCardProps {
  /**
   * Layout variants:
   *   - "card"  : full Card with header (default; used on Profile page)
   *   - "inline": compact list of chips/buttons for dashboards / sidebars
   *   - "menu"  : minimal button-only row for navbars
   */
  variant?: 'card' | 'inline' | 'menu';
}

/**
 * "Linked accounts" section. Renders the current OAuth providers
 * connected to the signed-in user, lets them connect new ones, and
 * lets them unlink an existing provider.
 *
 * <p>Source of truth: {@code user.linkedProviders} from
 * {@link useAuth}. Because the backend now includes this list in
 * every {@code /users/me} response, the component automatically
 * reflects any change made elsewhere in the app (or on another
 * device, the next time the user refreshes or focuses the tab).
 */
export function LinkedAccountsCard({ variant = 'card' }: LinkedAccountsCardProps = {}) {
  const { user, refreshUser } = useAuth();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const [busyProvider, setBusyProvider] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  const linked = user?.linkedProviders ?? [];
  const linkedBy = (p: string) => linked.find((l) => l.provider === p);

  // React to ?linked= / ?error= from the OAuth callback.
  useEffect(() => {
    const linkedProv = params.get('linked');
    const err = params.get('error');
    if (linkedProv) {
      setInfo(t('linkedAccounts.linkedOk', { provider: linkedProv }));
      const next = new URLSearchParams(params);
      next.delete('linked');
      next.delete('error');
      navigate({ search: next.toString() ? `?${next}` : '' }, { replace: true });
      // Re-fetch so user.linkedProviders includes the new identity.
      void refreshUser();
    } else if (err) {
      setError(
        err === 'invalid_state'
          ? t('linkedAccounts.sessionExpired')
          : err === 'link_failed'
            ? t('linkedAccounts.linkFailed')
            : describeError(new Error(err)),
      );
      const next = new URLSearchParams(params);
      next.delete('error');
      navigate({ search: next.toString() ? `?${next}` : '' }, { replace: true });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params]);

  const handleLink = async (provider: 'google' | 'github') => {
    setBusyProvider(provider);
    setError(null);
    try {
      const { url } = await startLinkProvider(provider);
      window.location.href = url;
    } catch (e) {
      setError(describeError(e));
      setBusyProvider(null);
    }
  };

  const handleUnlink = async (provider: 'google' | 'github') => {
    const confirmMessage =
      provider === 'github'
        ? t('linkedAccounts.unlinkConfirmGithub')
        : t('linkedAccounts.unlinkConfirm', { provider });
    if (!window.confirm(confirmMessage)) return;
    setBusyProvider(provider);
    setError(null);
    try {
      await unlinkProvider(provider);
      setInfo(t('linkedAccounts.unlinkedOk', { provider }));
      // Re-fetch so user.linkedProviders no longer includes the
      // freshly-unlinked identity.
      await refreshUser();
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusyProvider(null);
    }
  };

  // ----- render ---------------------------------------------------------

  const body = (
    <div className="space-y-3">
      {ALL_PROVIDERS.map((p) => {
        const connection = linkedBy(p.id);
        const isLinked = Boolean(connection);
        return (
          <div
            key={p.id}
            className="flex items-center justify-between rounded-md border border-border p-3"
          >
            <div className="flex items-center gap-3">
              <div className="text-foreground">{p.icon}</div>
              <div>
                <div className="font-medium">{p.shortLabel}</div>
                {variant !== 'menu' && (
                  <div className="text-xs text-muted-foreground">
                    {connection
                      ? connection.providerEmail ?? t('linkedAccounts.linked')
                      : t(p.hintKey)}
                  </div>
                )}
              </div>
            </div>
            {isLinked ? (
              <Button
                variant="outline"
                size={variant === 'menu' ? 'sm' : 'sm'}
                disabled={busyProvider === p.id}
                onClick={() => handleUnlink(p.id)}
              >
                {variant !== 'menu' && <Unlink className="mr-2 h-4 w-4" />}
                {t('linkedAccounts.unlink')}
              </Button>
            ) : (
              <Button
                variant="default"
                size="sm"
                disabled={busyProvider === p.id}
                onClick={() => handleLink(p.id)}
              >
                {busyProvider === p.id ? (
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                ) : null}
                {t('linkedAccounts.link')}
              </Button>
            )}
          </div>
        );
      })}

      {error && (
        <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
          {error}
        </div>
      )}
      {info && (
        <div className="rounded-md border border-emerald-500/40 bg-emerald-500/10 p-3 text-sm text-emerald-700 dark:text-emerald-300">
          {info}
        </div>
      )}
    </div>
  );

  if (variant === 'menu') {
    return body;
  }

  return (
    <Card>
      <CardHeader>
        <div className="flex h-10 w-10 items-center justify-center rounded-md bg-primary/10 text-primary">
          <Link2 className="h-5 w-5" />
        </div>
        <CardTitle>{t('linkedAccounts.title')}</CardTitle>
        {variant === 'card' && (
          <CardDescription>
            {t('linkedAccounts.description')}
          </CardDescription>
        )}
      </CardHeader>
      <CardContent>{body}</CardContent>
    </Card>
  );
}
