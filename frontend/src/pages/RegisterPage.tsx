import { FormEvent, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { UserPlus } from 'lucide-react';

import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '../components/ui/card';
import { PasswordInput } from '../components/ui/password-input';
import { SocialAuthButtons } from '../components/auth/SocialAuthButtons';
import { useAuth } from '../contexts/AuthContext';
import { describeError, asApiError } from '../services/auth';

export default function RegisterPage() {
  const { register } = useAuth();
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [params] = useSearchParams();

  const [email, setEmail] = useState<string>('');
  const [displayName, setDisplayName] = useState<string>('');
  const [password, setPassword] = useState<string>('');
  const [confirm, setConfirm] = useState<string>('');
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [emailTaken, setEmailTaken] = useState<boolean>(false);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    setEmailTaken(false);
    if (password !== confirm) {
      setError(t('auth.passwordMismatch'));
      return;
    }
    setSubmitting(true);
    try {
      const result = await register({ email, password, displayName });
      const next = params.get('next');
      const verifyUrl =
        `/verify-email?email=${encodeURIComponent(result.email)}` +
        (next ? `&next=${encodeURIComponent(next)}` : '');
      navigate(verifyUrl, { replace: true });
    } catch (err) {
      const apiErr = asApiError(err);
      if (apiErr.status === 409) {
        setEmailTaken(true);
        setError(t('auth.emailAlreadyRegistered'));
      } else {
        setError(describeError(err));
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex min-h-[70vh] items-center justify-center">
      <Card className="w-full max-w-md">
        <CardHeader>
          <div className="flex h-10 w-10 items-center justify-center rounded-md bg-primary/10 text-primary">
            <UserPlus className="h-5 w-5" />
          </div>
          <CardTitle>{t('auth.register.title')}</CardTitle>
          <CardDescription>
            {t('auth.register.subtitle')}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <SocialAuthButtons mode="register" />
          <div className="my-4 flex items-center gap-3 text-xs uppercase text-muted-foreground">
            <span className="h-px flex-1 bg-border" />
            <span>{t('auth.divider')}</span>
            <span className="h-px flex-1 bg-border" />
          </div>
          <form onSubmit={onSubmit} className="space-y-4">
            <div className="space-y-2">
              <Label htmlFor="displayName">{t('auth.register.displayName')}</Label>
              <Input id="displayName" value={displayName} onChange={(e) => setDisplayName(e.target.value)} required maxLength={120} />
            </div>
            <div className="space-y-2">
              <Label htmlFor="email">{t('auth.register.email')}</Label>
              <Input id="email" type="email" value={email}
                onChange={(e) => {
                  setEmail(e.target.value);
                  if (emailTaken) {
                    setEmailTaken(false);
                    setError(null);
                  }
                }}
                required autoComplete="email" />
            </div>
            <PasswordInput id="password" label={t('auth.register.password')}
              value={password} onChange={(e) => setPassword(e.target.value)}
              required minLength={8} autoComplete="new-password" showStrength />
            <PasswordInput id="confirm" label={t('auth.confirmPassword')}
              value={confirm} onChange={(e) => setConfirm(e.target.value)}
              required minLength={8} autoComplete="new-password" />
            {error && (
              <div className="space-y-2 rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
                <p>{error}</p>
                {emailTaken && (
                  <div className="flex flex-wrap gap-3 text-xs">
                    <Link to={`/login?email=${encodeURIComponent(email)}`}
                      className="font-medium underline underline-offset-2 hover:no-underline">
                      {t('common.actions.login')} →
                    </Link>
                    <Link to="/forgot-password"
                      className="font-medium underline underline-offset-2 hover:no-underline">
                      {t('auth.forgot')}
                    </Link>
                  </div>
                )}
              </div>
            )}
            <Button type="submit" disabled={submitting} className="w-full">
              {submitting ? t('auth.register.submitting') : t('auth.register.submit')}
            </Button>
          </form>
          <p className="mt-4 text-center text-sm text-muted-foreground">
            {t('auth.register.haveAccount')}{' '}
            <Link to="/login" className="text-primary hover:underline">
              {t('auth.register.loginCta')}
            </Link>.
          </p>
        </CardContent>
      </Card>
    </div>
  );
}