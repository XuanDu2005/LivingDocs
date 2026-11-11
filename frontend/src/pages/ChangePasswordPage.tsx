import { FormEvent, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { KeyRound } from 'lucide-react';
import { Button } from '../components/ui/button';
import {
  Card, CardContent, CardDescription, CardHeader, CardTitle,
} from '../components/ui/card';
import { PasswordInput } from '../components/ui/password-input';
import { ErrorState } from '../components/ui/states';
import { useAuth } from '../contexts/AuthContext';
import { changeOwnPassword } from '../services/users';
import { describeError } from '../services/auth';

/**
 * Replace your own password.
 *
 * <p>Reached two ways:
 *
 * <ul>
 *   <li>from the profile page, as a routine change;</li>
 *   <li>automatically, when the account was created or reset by an
 *       administrator and still holds the temporary password. The API
 *       refuses every other request in that state, so this screen is
 *       the only thing the account owner can do until the temporary
 *       credential is replaced.</li>
 * </ul>
 *
 * <p>The current password has to be supplied even in the forced case:
 * the temporary credential is known to the administrator as well, so
 * it cannot be the thing that authorises the change.
 */
export default function ChangePasswordPage() {
  const { t } = useTranslation();
  const { refreshUser } = useAuth();

  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (newPassword.length < 12) {
      setError(t('auth.changePassword.tooShort'));
      return;
    }
    if (newPassword !== confirm) {
      setError(t('auth.changePassword.mismatch'));
      return;
    }
    if (newPassword === currentPassword) {
      setError(t('auth.changePassword.sameAsCurrent'));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await changeOwnPassword({ currentPassword, newPassword });
      // The flag lives on the account, so the profile has to be
      // re-read — otherwise the UI would still think a password change
      // is pending.
      await refreshUser();
      setCurrentPassword('');
      setNewPassword('');
      setConfirm('');
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto max-w-xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">{t('auth.changePassword.title')}</h1>
        <p className="text-sm text-muted-foreground mt-1">
          {t('auth.changePassword.subtitle')}
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <KeyRound className="h-4 w-4" /> {t('auth.changePassword.formTitle')}
          </CardTitle>
          <CardDescription>{t('auth.changePassword.formDesc')}</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={onSubmit} className="space-y-4">
            <PasswordInput
              id="change-password-current"
              label={t('auth.changePassword.currentPassword')}
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
              required
              autoComplete="current-password"
            />
            <PasswordInput
              id="change-password-new"
              label={t('auth.changePassword.newPassword')}
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              required
              minLength={12}
              maxLength={72}
              autoComplete="new-password"
              showStrength
            />
            <PasswordInput
              id="change-password-confirm"
              label={t('auth.changePassword.confirmPassword')}
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
              required
              minLength={12}
              maxLength={72}
              autoComplete="new-password"
            />
            {error && <ErrorState message={error} />}
            <Button type="submit" disabled={busy}>
              {busy ? t('common.actions.saving') : t('auth.changePassword.submit')}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
