import { useState } from 'react';
import { FaGithub, FaGoogle } from 'react-icons/fa';
import { Link2, Loader2 } from 'lucide-react';

import { Button } from '../ui/button';
import { useAuth } from '../../contexts/AuthContext';
import { describeError } from '../../services/auth';
import { startLinkProvider } from '../../services/authApi';

/**
 * Compact "linked accounts" widget for navbars / dashboards.
 *
 * <p>Renders a single icon button per provider. The icon is filled
 * (colored) when the provider is already linked, outlined when not.
 * Clicking an unlinked icon starts the OAuth flow; clicking a linked
 * icon navigates the user to the profile page where they can manage
 * (or unlink) the provider.
 *
 * <p>Source of truth: {@code user.linkedProviders} from
 * {@link useAuth} — same as the profile page, so any change there is
 * automatically reflected here the next time the user state is
 * refreshed.
 */
export function LinkedAccountsQuickLinks() {
  const { user } = useAuth();
  const linked = user?.linkedProviders ?? [];
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const onClick = async (provider: 'google' | 'github') => {
    const isLinked = linked.some((p) => p.provider === provider);
    if (isLinked) {
      // Already linked — let the user manage it on the profile page.
      window.location.href = '/profile?tab=linked';
      return;
    }
    setBusy(provider);
    setError(null);
    try {
      const { url } = await startLinkProvider(provider);
      window.location.href = url;
    } catch (e) {
      setError(describeError(e));
      setBusy(null);
    }
  };

  const providers: Array<{
    id: 'google' | 'github';
    label: string;
    icon: React.ReactNode;
  }> = [
    {
      id: 'google',
      label: 'Google',
      icon: <FaGoogle className="h-4 w-4" />,
    },
    {
      id: 'github',
      label: 'GitHub',
      icon: <FaGithub className="h-4 w-4" />,
    },
  ];

  return (
    <div className="flex items-center gap-1" title={error ?? undefined}>
      {providers.map((p) => {
        const isLinked = linked.some((l) => l.provider === p.id);
        return (
          <Button
            key={p.id}
            variant="ghost"
            size="icon"
            disabled={busy === p.id}
            onClick={() => onClick(p.id)}
            title={
              isLinked
                ? `Đã liên kết ${p.label} — bấm để quản lý`
                : `Liên kết ${p.label}`
            }
            aria-label={
              isLinked ? `Đã liên kết ${p.label}` : `Liên kết ${p.label}`
            }
            className={
              isLinked
                ? 'text-emerald-600 hover:text-emerald-700 dark:text-emerald-400'
                : 'text-muted-foreground'
            }
          >
            {busy === p.id ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : isLinked ? (
              <Link2 className="-mr-1 h-3 w-3 text-emerald-500" />
            ) : null}
            {p.icon}
          </Button>
        );
      })}
    </div>
  );
}
