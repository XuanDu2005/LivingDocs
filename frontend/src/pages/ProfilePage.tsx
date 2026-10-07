import { FormEvent, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Check, LogOut, User } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Badge } from '../components/ui/badge';
import {
  Card, CardContent, CardDescription, CardHeader, CardTitle,
} from '../components/ui/card';
import { LoadingState, ErrorState } from '../components/ui/states';
import { LinkedAccountsCard } from '../components/auth/LinkedAccountsCard';
import { useAuth } from '../contexts/AuthContext';
import { updateCurrentUser } from '../services/users';
import { describeError } from '../services/auth';

export default function ProfilePage() {
  const { user, refreshUser, logout } = useAuth();
  const { t } = useTranslation();
  const navigate = useNavigate();

  const [displayName, setDisplayName] = useState<string>('');
  const [email, setEmail] = useState<string>('');
  const [submitting, setSubmitting] = useState<boolean>(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (user) {
      setDisplayName(user.displayName);
      setEmail(user.email);
    }
  }, [user]);

  if (!user) return <LoadingState message={t('profile.loading')} />;

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setSubmitting(true);
    setMessage(null);
    setError(null);
    try {
      await updateCurrentUser({ displayName, email });
      await refreshUser();
      setMessage(t('profile.updated'));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSubmitting(false);
    }
  }

  function handleSignOut() {
    logout();
    navigate('/login', { replace: true });
  }

  return (
    <div className="mx-auto max-w-xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">{t('profile.title')}</h1>
        <p className="text-sm text-muted-foreground mt-1">
          {t('profile.subtitle')}
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <User className="h-4 w-4" /> {t('profile.info')}
          </CardTitle>
          <CardDescription>{t('profile.infoDesc')}</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={onSubmit} className="space-y-4">
            <div className="space-y-1.5">
              <Label htmlFor="profile-display">{t('profile.displayName')}</Label>
              <Input id="profile-display" type="text" value={displayName}
                onChange={(e) => setDisplayName(e.target.value)} required maxLength={120} />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="profile-email">{t('profile.email')}</Label>
              <Input id="profile-email" type="email" value={email}
                onChange={(e) => setEmail(e.target.value)}
                disabled className="opacity-60 cursor-not-allowed" />
              <p className="text-xs text-muted-foreground">{t('profile.emailCantChange')}</p>
            </div>
            <div className="rounded-md border bg-muted/50 p-3 space-y-1.5">
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">{t('profile.userId')}</span>
                <code className="text-xs font-mono">{user.id}</code>
              </div>
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">{t('profile.created')}</span>
                <span className="text-xs">{new Date(user.createdAt).toLocaleDateString()}</span>
              </div>
              {user.role && (
                <div className="flex items-center justify-between text-sm">
                  <span className="text-muted-foreground">{t('profile.role')}</span>
                  <Badge variant="info">{user.role}</Badge>
                </div>
              )}
            </div>
            {message && (
              <div className="flex items-center gap-2 text-sm text-green-600">
                <Check className="h-4 w-4" /> {message}
              </div>
            )}
            {error && <ErrorState message={error} />}
            <Button type="submit" disabled={submitting}>
              {submitting ? t('common.actions.saving') : t('profile.saveChanges')}
            </Button>
          </form>
        </CardContent>
      </Card>

      <LinkedAccountsCard />

      <Card>
        <CardContent className="pt-6">
          <div className="flex items-center justify-between">
            <div>
              <div className="text-sm font-medium">{t('profile.signOut')}</div>
              <div className="text-xs text-muted-foreground">{t('profile.signOutDesc')}</div>
            </div>
            <Button variant="destructive" onClick={handleSignOut}>
              <LogOut className="mr-1 h-4 w-4" /> {t('common.actions.logout')}
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}