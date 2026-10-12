import { FormEvent, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, Bell, Mail, Save, Loader2 } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card';
import { Label } from '../components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../components/ui/select';
import { LoadingState, ErrorState } from '../components/ui/states';
import {
  fetchNotificationPreferences,
  updateNotificationPreferences,
  UserNotificationPreferences,
  DigestFrequency,
} from '../services/users';
import { describeError } from '../services/auth';

const DIGEST_OPTIONS: { value: DigestFrequency; label: string }[] = [
  { value: 'REAL_TIME', label: 'Real-time (as events happen)' },
  { value: 'DAILY', label: 'Daily digest' },
  { value: 'WEEKLY', label: 'Weekly digest' },
];

function ToggleRow({
  id,
  label,
  description,
  checked,
  onChange,
  disabled,
}: {
  id: string;
  label: string;
  description?: string;
  checked: boolean;
  onChange: (v: boolean) => void;
  disabled?: boolean;
}) {
  return (
    <div className="flex items-start justify-between gap-4 rounded-md border bg-muted/20 p-3">
      <div className="space-y-0.5 min-w-0">
        <Label htmlFor={id} className="text-sm font-medium cursor-pointer">{label}</Label>
        {description && <p className="text-xs text-muted-foreground">{description}</p>}
      </div>
      <button
        id={id}
        type="button"
        role="switch"
        aria-checked={checked}
        onClick={() => !disabled && onChange(!checked)}
        disabled={disabled}
        className={`relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-2 disabled:opacity-50 ${
          checked ? 'bg-primary' : 'bg-muted-foreground/30'
        }`}
      >
        <span
          className={`pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition-transform ${
            checked ? 'translate-x-5' : 'translate-x-0'
          }`}
        />
      </button>
    </div>
  );
}

export default function NotificationPreferencesPage() {
  const { t } = useTranslation();
  const [prefs, setPrefs] = useState<UserNotificationPreferences | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [saving, setSaving] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [savedAt, setSavedAt] = useState<number | null>(null);

  useEffect(() => {
    async function load() {
      setLoading(true);
      setError(null);
      try {
        const p = await fetchNotificationPreferences();
        setPrefs(p);
      } catch (err) {
        setError(describeError(err));
      } finally {
        setLoading(false);
      }
    }
    void load();
  }, []);

  function patch<K extends keyof UserNotificationPreferences>(key: K, value: UserNotificationPreferences[K]) {
    if (!prefs) return;
    setPrefs({ ...prefs, [key]: value });
  }

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!prefs) return;
    setSaving(true);
    setError(null);
    try {
      const updated = await updateNotificationPreferences(prefs);
      setPrefs(updated);
      setSavedAt(Date.now());
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSaving(false);
    }
  }

  if (loading) {
    return (
      <div className="mx-auto max-w-2xl space-y-4 p-6">
        <LoadingState message={t('notifications.loading', 'Loading notification preferences…')} />
      </div>
    );
  }
  if (!prefs) return <ErrorState message={error ?? 'Could not load preferences'} />;

  return (
    <div className="mx-auto max-w-2xl space-y-6 p-6">
      <Button variant="ghost" size="sm" asChild>
        <Link to="/profile">
          <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back', 'Back to profile')}
        </Link>
      </Button>

      <div>
        <h1 className="text-2xl font-bold tracking-tight flex items-center gap-2">
          <Bell className="h-5 w-5" /> {t('notifications.title', 'Notification preferences')}
        </h1>
        <p className="text-sm text-muted-foreground mt-1">
          {t('notifications.subtitle', 'Choose how and when LivingDocs notifies you. You can change these at any time.')}
        </p>
      </div>

      <form onSubmit={onSubmit} className="space-y-4">
        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2">
              <Mail className="h-4 w-4" /> {t('notifications.email', 'Email')}
            </CardTitle>
            <CardDescription>{t('notifications.emailDesc', 'Notifications sent to the address on your account.')}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <ToggleRow
              id="email-drift"
              label={t('notifications.driftDetected', 'Drift detected')}
              description={t('notifications.driftDetectedDesc', 'Get notified when the AI detects a documentation drift alert.')}
              checked={prefs.emailOnDriftDetected}
              onChange={(v) => patch('emailOnDriftDetected', v)}
            />
            <ToggleRow
              id="email-review"
              label={t('notifications.reviewRequested', 'Review requested')}
              description={t('notifications.reviewRequestedDesc', 'A document is waiting for your review.')}
              checked={prefs.emailOnReviewRequested}
              onChange={(v) => patch('emailOnReviewRequested', v)}
            />
            <ToggleRow
              id="email-autoupdate"
              label={t('notifications.autoUpdate', 'AI auto-update')}
              description={t('notifications.autoUpdateDesc', 'A document you own has been automatically updated by the AI.')}
              checked={prefs.emailOnAutoUpdate}
              onChange={(v) => patch('emailOnAutoUpdate', v)}
            />
            <ToggleRow
              id="email-token"
              label={t('notifications.tokenInvalid', 'GitHub token invalid')}
              description={t('notifications.tokenInvalidDesc', 'A connected GitHub account lost authorization and needs to be reconnected.')}
              checked={prefs.emailOnTokenInvalid}
              onChange={(v) => patch('emailOnTokenInvalid', v)}
            />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2">
              <Bell className="h-4 w-4" /> {t('notifications.inApp', 'In-app')}
            </CardTitle>
            <CardDescription>{t('notifications.inAppDesc', 'Notifications shown in the bell icon at the top of the page.')}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <ToggleRow
              id="inapp-drift"
              label={t('notifications.driftDetected', 'Drift detected')}
              checked={prefs.inAppOnDriftDetected}
              onChange={(v) => patch('inAppOnDriftDetected', v)}
            />
            <ToggleRow
              id="inapp-review"
              label={t('notifications.reviewRequested', 'Review requested')}
              checked={prefs.inAppOnReviewRequested}
              onChange={(v) => patch('inAppOnReviewRequested', v)}
            />
            <ToggleRow
              id="inapp-autoupdate"
              label={t('notifications.autoUpdate', 'AI auto-update')}
              checked={prefs.inAppOnAutoUpdate}
              onChange={(v) => patch('inAppOnAutoUpdate', v)}
            />
            <ToggleRow
              id="inapp-token"
              label={t('notifications.tokenInvalid', 'GitHub token invalid')}
              checked={prefs.inAppOnTokenInvalid}
              onChange={(v) => patch('inAppOnTokenInvalid', v)}
            />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">{t('notifications.digestTitle', 'Email digest frequency')}</CardTitle>
            <CardDescription>{t('notifications.digestDesc', 'How often non-urgent items are batched into a single email.')}</CardDescription>
          </CardHeader>
          <CardContent>
            <Select value={prefs.digestFrequency} onValueChange={(v) => patch('digestFrequency', v as DigestFrequency)}>
              <SelectTrigger className="max-w-sm">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {DIGEST_OPTIONS.map((o) => (
                  <SelectItem key={o.value} value={o.value}>{o.label}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </CardContent>
        </Card>

        {error && <ErrorState message={error} />}
        {savedAt && (
          <div className="rounded-md border border-primary/40 bg-primary/5 p-3 text-sm text-primary">
            {t('notifications.saved', 'Preferences saved.')}
          </div>
        )}

        <div className="flex justify-end">
          <Button type="submit" disabled={saving}>
            {saving ? (
              <>
                <Loader2 className="mr-1 h-4 w-4 animate-spin" />
                {t('common.actions.saving', 'Saving…')}
              </>
            ) : (
              <>
                <Save className="mr-1 h-4 w-4" />
                {t('common.actions.save', 'Save preferences')}
              </>
            )}
          </Button>
        </div>
      </form>
    </div>
  );
}
