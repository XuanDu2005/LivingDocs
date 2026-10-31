import { FormEvent, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { KeyRound } from 'lucide-react';

import { Button } from '../components/ui/button';
import { Label } from '../components/ui/label';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '../components/ui/card';
import { PasswordInput } from '../components/ui/password-input';
import { OtpInput } from '../components/auth/OtpInput';
import { resetPassword } from '../services/authApi';
import { describeError } from '../services/auth';

export default function ResetPasswordPage() {
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const email = params.get('email') ?? '';
  const navigate = useNavigate();

  const [code, setCode] = useState<string>('');
  const [newPassword, setNewPassword] = useState<string>('');
  const [confirm, setConfirm] = useState<string>('');
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    if (code.length !== 6) {
      setError(t('auth.resetPassword.errCodeLength'));
      return;
    }
    if (newPassword.length < 8) {
      setError(t('auth.resetPassword.errPasswordLength'));
      return;
    }
    if (newPassword !== confirm) {
      setError(t('auth.passwordMismatch'));
      return;
    }
    setSubmitting(true);
    try {
      await resetPassword(email, code, newPassword);
      navigate('/login?reset=success', { replace: true });
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSubmitting(false);
    }
  }

  if (!email) {
    return (
      <div className="flex min-h-[70vh] items-center justify-center">
        <Card className="w-full max-w-md">
          <CardHeader>
            <CardTitle>{t('auth.resetPassword.missingEmail')}</CardTitle>
            <CardDescription>
              {t('auth.resetPassword.missingEmailDesc')}
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Button asChild className="w-full">
              <Link to="/forgot-password">{t('auth.resetPassword.backToForgot')}</Link>
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
          <div className="flex h-10 w-10 items-center justify-center rounded-md bg-primary/10 text-primary">
            <KeyRound className="h-5 w-5" />
          </div>
          <CardTitle>{t('auth.resetPassword.title')}</CardTitle>
          <CardDescription>
            {t('auth.resetPassword.subtitle', { email })}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={onSubmit} className="space-y-4">
            <div className="space-y-2">
              <Label>{t('auth.resetPassword.codeLabel')}</Label>
              <OtpInput value={code} onChange={setCode} length={6} />
            </div>
            <PasswordInput
              id="newPassword"
              label={t('auth.resetPassword.newPassword')}
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              required
              minLength={8}
              autoComplete="new-password"
              showStrength
            />
            <PasswordInput
              id="confirm"
              label={t('auth.resetPassword.confirmPassword')}
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
              required
              minLength={8}
              autoComplete="new-password"
            />
            {error && (
              <div className="rounded-md border border-destructive/50 bg-destructive/10 p-3 text-sm text-destructive">
                {error}
              </div>
            )}
            <Button type="submit" disabled={submitting} className="w-full">
              {submitting ? t('auth.resetPassword.submitting') : t('auth.resetPassword.submit')}
            </Button>
          </form>
          <p className="mt-4 text-center text-sm text-muted-foreground">
            <Link to="/forgot-password" className="hover:underline">
              {t('auth.resetPassword.requestNewCode')}
            </Link>
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
