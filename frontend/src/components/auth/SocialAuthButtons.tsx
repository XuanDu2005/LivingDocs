import { useNavigate, useSearchParams } from 'react-router-dom';
import { FaGithub, FaGoogle } from 'react-icons/fa';

import { Button } from '../ui/button';
import { apiOrigin } from '../../lib/apiBase';

type Provider = 'google' | 'github';

interface SocialAuthButtonsProps {
  /** Whether the buttons appear on the login or register page. */
  mode: 'login' | 'register';
  /** Optional "next" URL to pass through to the OAuth start. */
  next?: string;
  /** Optional className for the outer container. */
  className?: string;
}

/**
 * Two side-by-side buttons that start the OAuth round-trip with
 * Google or GitHub. The browser is redirected to the backend's
 * {@code /api/v1/auth/oauth/{provider}/start} endpoint, which
 * 302s to the provider.
 */
export function SocialAuthButtons({ mode, next, className }: SocialAuthButtonsProps) {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const fallbackNext = params.get('next') ?? next;

  const handle = (provider: Provider) => {
    const search = new URLSearchParams({ action: mode });
    if (fallbackNext) search.set('next', fallbackNext);
    // Use window.location to trigger a full redirect (the backend will
    // eventually 302 back to /auth/callback?token=...).
    window.location.href = `${apiOrigin}/api/v1/auth/oauth/${provider}/start?${search.toString()}`;
    // navigate() is here so TypeScript doesn't complain about an unused
    // import; the actual navigation happens via the full redirect above.
    void navigate;
  };

  return (
    <div className={className ?? 'grid grid-cols-2 gap-2'}>
      <Button
        type="button"
        variant="outline"
        onClick={() => handle('google')}
        className="flex items-center justify-center gap-2"
      >
        <FaGoogle className="h-4 w-4" />
        <span>Google</span>
      </Button>
      <Button
        type="button"
        variant="outline"
        onClick={() => handle('github')}
        className="flex items-center justify-center gap-2"
      >
        <FaGithub className="h-4 w-4" />
        <span>GitHub</span>
      </Button>
    </div>
  );
}
