import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { TFunction } from 'i18next';
import { Loader2, ShieldAlert } from 'lucide-react';

import { Button } from '../components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '../components/ui/card';
import { authStorage } from '../services/auth';
import { fetchCurrentUser } from '../services/users';

type Status = 'processing' | 'success' | 'error';

/**
 * Target of the OAuth 2.0 redirect. The backend 302s the browser here
 * with {@code ?token=...&provider=...&next=...} (or {@code ?error=...}).
 *
 * <p>We exchange the one-shot token for a {@code fetchCurrentUser}
 * round-trip so the local cache (displayName, role, etc.) is fully
 * populated, then navigate to the original destination.
 */
export default function OAuthCallbackPage() {
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [status, setStatus] = useState<Status>('processing');
  const [errorCode, setErrorCode] = useState<string | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const ran = useRef<boolean>(false);

  useEffect(() => {
    if (ran.current) return;
    ran.current = true;
    const token = params.get('token');
    const error = params.get('error');
    const linked = params.get('linked');
    const next = params.get('next') ?? '/dashboard';
    const provider = params.get('provider');

    if (error) {
      setStatus('error');
      setErrorCode(error);
      setErrorMessage(params.get('message'));
      return;
    }
    if (linked) {
      // The "link" flow does NOT return a JWT — it just signals success.
      // Forward the user back to the profile page so the LinkedAccountsCard
      // can pick up ?linked=... and show the success message.
      setStatus('success');
      const target = `/profile?linked=${encodeURIComponent(linked)}`;
      window.setTimeout(() => navigate(target, { replace: true }), 600);
      return;
    }
    if (!token) {
      setStatus('error');
      setErrorCode('missing_token');
      setErrorMessage(
        t('auth.oauth.missingToken'),
      );
      return;
    }
    authStorage.setToken(token);
    fetchCurrentUser()
      .then((user) => {
        authStorage.setUser(user);
        setStatus('success');
        // Brief delay so the user sees the success card before navigation.
        window.setTimeout(() => navigate(next, { replace: true }), 600);
      })
      .catch((e) => {
        authStorage.clearToken();
        authStorage.clearUser();
        setStatus('error');
        setErrorCode('user_load_failed');
        setErrorMessage(e instanceof Error ? e.message : t('auth.oauth.failedLoadProfile'));
      });
    void provider;
  }, [params, navigate]);

  if (status === 'processing') {
    return (
      <div className="flex min-h-[70vh] items-center justify-center">
        <Card className="w-full max-w-md">
          <CardHeader>
            <div className="flex h-10 w-10 items-center justify-center rounded-md bg-primary/10 text-primary">
              <Loader2 className="h-5 w-5 animate-spin" />
            </div>
            <CardTitle>{t('auth.oauth.processing')}</CardTitle>
            <CardDescription>
              {t('auth.oauth.processingDesc')}
            </CardDescription>
          </CardHeader>
        </Card>
      </div>
    );
  }
  if (status === 'error') {
    const friendly = friendlyErrorMessage(errorCode, errorMessage, t);
    return (
      <div className="flex min-h-[70vh] items-center justify-center">
        <Card className="w-full max-w-md">
          <CardHeader>
            <div className="flex h-10 w-10 items-center justify-center rounded-md bg-destructive/10 text-destructive">
              <ShieldAlert className="h-5 w-5" />
            </div>
            <CardTitle>{t('auth.oauth.failed')}</CardTitle>
            <CardDescription>{friendly}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-2">
            <Button className="w-full" onClick={() => navigate('/login', { replace: true })}>
              {t('auth.oauth.backToLogin')}
            </Button>
            <Button
              variant="outline"
              className="w-full"
              onClick={() => navigate('/register', { replace: true })}
            >
              {t('auth.oauth.createAccount')}
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }
  return (
    <div className="flex min-h-[70vh] items-center justify-center">
      <Card className="w-full max-w-md">
        <CardHeader>
        <CardTitle>{t('auth.oauth.success')}</CardTitle>
        <CardDescription>{t('auth.oauth.redirecting')}</CardDescription>
        </CardHeader>
      </Card>
    </div>
  );
}

function friendlyErrorMessage(
  code: string | null,
  fallback: string | null,
  t: TFunction,
): string {
  switch (code) {
    case 'invalid_state':
      return t('auth.oauth.errInvalidState');
    case 'merge_required':
      return t('auth.oauth.errMergeRequired');
    case 'link_failed':
      return t('auth.oauth.errLinkFailed');
    case 'link_callback_misrouted':
      return t('auth.oauth.errLinkCallbackMisrouted');
    case 'link_session_missing':
      return t('auth.oauth.errLinkSessionMissing');
    case 'exchange_failed':
      return t('auth.oauth.errExchangeFailed');
    case 'missing_token':
      return t('auth.oauth.errMissingToken');
    default:
      return fallback ?? t('auth.oauth.errGeneric');
  }
}
