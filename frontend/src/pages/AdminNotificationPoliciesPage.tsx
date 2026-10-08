import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, Check, Megaphone } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Switch2 } from '../components/ui/switch2';
import { LoadingState, ErrorState } from '../components/ui/states';
import { describeError } from '../services/auth';
import {
  NOTIFICATION_KIND_LABELS,
  SCOPE_LABELS,
  NotificationPolicy,
  notificationPoliciesApi,
} from '../services/notificationPolicies';

/**
 * Admin console for notification policy platform defaults.
 *
 * <p>Each row corresponds to a {@code NotificationKinds} entry. Toggling a
 * row updates the platform-wide default; the audit log gets a row. A
 * workspace can override the default via
 * {@code /workspaces/{id}/notification-policies}.
 */
export default function AdminNotificationPoliciesPage() {
  const { t } = useTranslation();
  const [rows, setRows] = useState<NotificationPolicy[] | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const r = await notificationPoliciesApi.listAdminPolicies();
      setRows(r);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function toggle(row: NotificationPolicy) {
    setBusy(row.eventKind);
    setInfo(null);
    setError(null);
    try {
      const updated = await notificationPoliciesApi.updateAdminPolicy(
        row.eventKind, !row.enabled,
      );
      setRows((prev) => prev == null
        ? prev
        : prev.map((r) => (r.eventKind === row.eventKind ? updated : r)));
      setInfo(t('notificationPolicies.saved'));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="space-y-6">
      <Button variant="ghost" size="sm" asChild>
        <Link to="/admin">
          <ArrowLeft className="mr-1 h-4 w-4" />
          {t('integrations.backToAdmin')}
        </Link>
      </Button>
      <div>
        <h1 className="text-2xl font-bold tracking-tight flex items-center gap-2">
          <Megaphone className="h-5 w-5" /> {t('notificationPolicies.title')}
        </h1>
        <p className="text-sm text-muted-foreground">
          {t('notificationPolicies.adminSubtitle')}
        </p>
      </div>

      {info && (
        <div className="rounded border border-green-500/30 bg-green-500/10 px-3 py-2 text-sm text-green-700 flex items-center gap-2">
          <Check className="h-4 w-4" /> {info}
        </div>
      )}
      {error && <ErrorState message={error} />}

      {loading || !rows ? (
        <LoadingState message={t('notificationPolicies.loading')} />
      ) : (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">{t('notificationPolicies.tableTitle')}</CardTitle>
            <CardDescription>{t('notificationPolicies.tableDesc')}</CardDescription>
          </CardHeader>
          <CardContent>
            <ul className="divide-y">
              {rows.map((row) => (
                <li key={row.eventKind} className="flex items-center gap-4 py-3">
                  <div className="min-w-0 flex-1">
                    <div className="text-sm font-medium">
                      {NOTIFICATION_KIND_LABELS[row.eventKind] ?? row.eventKind}
                    </div>
                    <code className="text-xs text-muted-foreground">{row.eventKind}</code>
                  </div>
                  <Badge variant={row.scope === 'PLATFORM' ? 'info' : 'muted'} className="text-[10px]">
                    {SCOPE_LABELS[row.scope]}
                  </Badge>
                  <div className="flex items-center gap-2">
                    {row.enabled ? (
                      <Badge variant="success" className="text-[10px]">
                        {t('notificationPolicies.enabled')}
                      </Badge>
                    ) : (
                      <Badge variant="muted" className="text-[10px]">
                        {t('notificationPolicies.disabled')}
                      </Badge>
                    )}
                    <Switch2
                      checked={row.enabled}
                      onCheckedChange={() => void toggle(row)}
                      disabled={busy === row.eventKind}
                      label={NOTIFICATION_KIND_LABELS[row.eventKind] ?? row.eventKind}
                    />
                  </div>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}
    </div>
  );
}