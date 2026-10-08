import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, Check, Loader2, Megaphone, Mail, Inbox, Save } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Switch2 } from '../components/ui/switch2';
import { Input } from '../components/ui/input';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { describeError } from '../services/auth';
import {
  NOTIFICATION_KIND_LABELS,
  SCOPE_LABELS,
  NotificationPolicy,
  notificationPoliciesApi,
} from '../services/notificationPolicies';
import {
  NotificationChannelConfig,
  notificationChannelsApi,
} from '../services/notificationChannels';

/**
 * Per-workspace view of the resolved notification policies.
 *
 * <p>Rows are flagged with their scope: {@code WORKSPACE} = explicit
 * override, {@code INHERITED} = coming from the platform default,
 * {@code DEFAULT} = no policy row exists at all (treated as enabled).
 * Toggling a row writes a {@code WORKSPACE} override.
 */
export default function WorkspaceNotificationPoliciesPage() {
  const { workspaceId } = useParams<{ workspaceId: string }>();
  const { t } = useTranslation();
  const [rows, setRows] = useState<NotificationPolicy[] | null>(null);
  const [channels, setChannels] = useState<NotificationChannelConfig[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [editingChannel, setEditingChannel] = useState<string | null>(null);
  const [editEmailRecipients, setEditEmailRecipients] = useState<string>('');
  const [editInApp, setEditInApp] = useState<boolean>(true);
  const [editEmail, setEditEmail] = useState<boolean>(false);
  const [savingChannel, setSavingChannel] = useState<boolean>(false);

  async function load() {
    if (!workspaceId) return;
    setLoading(true);
    setError(null);
    try {
      const [policies, channelList] = await Promise.all([
        notificationPoliciesApi.listWorkspacePolicies(workspaceId),
        notificationChannelsApi.list(workspaceId).catch(() => []),
      ]);
      setRows(policies);
      setChannels(channelList);
    } catch (err) {
      setError(describeError(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, [workspaceId]);

  function openChannelEditor(eventKind: string) {
    const existing = channels.find((c) => c.eventKind === eventKind);
    setEditingChannel(eventKind);
    setEditInApp(existing?.inAppEnabled ?? true);
    setEditEmail(existing?.emailEnabled ?? false);
    setEditEmailRecipients(existing?.emailRecipients ?? '');
  }

  async function saveChannel() {
    if (!workspaceId || !editingChannel) return;
    setSavingChannel(true);
    setError(null);
    try {
      const updated = await notificationChannelsApi.update(workspaceId, {
        eventKind: editingChannel,
        inAppEnabled: editInApp,
        emailEnabled: editEmail,
        emailRecipients: editEmailRecipients.trim() || null,
      });
      setChannels((prev) => {
        const filtered = prev.filter((c) => c.eventKind !== updated.eventKind);
        return [...filtered, updated].sort((a, b) => a.eventKind.localeCompare(b.eventKind));
      });
      setEditingChannel(null);
      setInfo('Channel configuration saved.');
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSavingChannel(false);
    }
  }

  async function toggle(row: NotificationPolicy) {
    if (!workspaceId) return;
    setBusy(row.eventKind);
    setInfo(null);
    setError(null);
    try {
      const updated = await notificationPoliciesApi.updateWorkspacePolicy(
        workspaceId, row.eventKind, !row.enabled,
      );
      setRows((prev) => prev == null
        ? prev
        : prev.map((r) => (r.eventKind === row.eventKind ? { ...updated, scope: 'WORKSPACE' } : r)));
      setInfo(t('notificationPolicies.saved'));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusy(null);
    }
  }

  if (!workspaceId) return null;

  return (
    <div className="space-y-6">
      <Button variant="ghost" size="sm" asChild>
        <Link to={`/workspaces/${workspaceId}`}>
          <ArrowLeft className="mr-1 h-4 w-4" /> {t('common.back')}
        </Link>
      </Button>
      <div>
        <h1 className="text-2xl font-bold tracking-tight flex items-center gap-2">
          <Megaphone className="h-5 w-5" /> {t('notificationPolicies.title')}
        </h1>
        <p className="text-sm text-muted-foreground">
          {t('notificationPolicies.workspaceSubtitle')}
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
      ) : rows.length === 0 ? (
        <EmptyState
          title={t('notificationPolicies.emptyTitle')}
          description={t('notificationPolicies.emptyDesc')}
        />
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
                  <Badge
                    variant={
                      row.scope === 'WORKSPACE' ? 'info'
                      : row.scope === 'INHERITED' ? 'muted'
                      : 'muted'}
                    className="text-[10px]"
                  >
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
                    {busy === row.eventKind ? (
                      <Loader2 className="h-4 w-4 animate-spin" />
                    ) : null}
                  </div>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      )}

      {/* Delivery Channels Section */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base flex items-center gap-2">
            <Mail className="h-4 w-4" /> Delivery Channels
          </CardTitle>
          <CardDescription>
            Configure which channels (in-app, email) are used to deliver each
            notification type. Leave email recipients blank to notify all workspace members.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {editingChannel ? (
            <div className="space-y-4 rounded-md border p-4">
              <div className="text-sm font-medium">
                Configure: {NOTIFICATION_KIND_LABELS[editingChannel] ?? editingChannel}
              </div>
              <div className="flex items-center justify-between">
                <div>
                  <div className="text-sm font-medium">In-app notification</div>
                  <div className="text-xs text-muted-foreground">Show in the notification bell.</div>
                </div>
                <Switch2
                  checked={editInApp}
                  onCheckedChange={() => setEditInApp(!editInApp)}
                  label="In-app"
                />
              </div>
              <div className="flex items-center justify-between">
                <div>
                  <div className="text-sm font-medium">Email notification</div>
                  <div className="text-xs text-muted-foreground">Send via email.</div>
                </div>
                <Switch2
                  checked={editEmail}
                  onCheckedChange={() => setEditEmail(!editEmail)}
                  label="Email"
                />
              </div>
              {editEmail && (
                <div className="space-y-1.5">
                  <label className="text-xs font-medium">Email recipients (optional)</label>
                  <Input
                    type="text"
                    value={editEmailRecipients}
                    onChange={(e) => setEditEmailRecipients(e.target.value)}
                    placeholder="user1@example.com, user2@example.com"
                  />
                  <p className="text-xs text-muted-foreground">
                    Comma-separated. Leave blank to send to all workspace members.
                  </p>
                </div>
              )}
              <div className="flex gap-2">
                <Button size="sm" onClick={() => void saveChannel()} disabled={savingChannel}>
                  <Save className="mr-1 h-3 w-3" /> Save
                </Button>
                <Button size="sm" variant="outline" onClick={() => setEditingChannel(null)}>
                  Cancel
                </Button>
              </div>
            </div>
          ) : (
            <ul className="divide-y">
              {rows && rows.map((row) => {
                const channel = channels.find((c) => c.eventKind === row.eventKind);
                return (
                  <li key={row.eventKind} className="flex items-center gap-4 py-3">
                    <div className="min-w-0 flex-1">
                      <div className="text-sm font-medium">
                        {NOTIFICATION_KIND_LABELS[row.eventKind] ?? row.eventKind}
                      </div>
                    </div>
                    <div className="flex items-center gap-1">
                      {channel?.inAppEnabled !== false ? (
                        <Badge variant="info" className="text-[10px]">
                          <Inbox className="mr-1 h-3 w-3" /> In-app
                        </Badge>
                      ) : (
                        <Badge variant="muted" className="text-[10px]">No in-app</Badge>
                      )}
                      {channel?.emailEnabled ? (
                        <Badge variant="info" className="text-[10px]">
                          <Mail className="mr-1 h-3 w-3" /> Email
                        </Badge>
                      ) : (
                        <Badge variant="muted" className="text-[10px]">No email</Badge>
                      )}
                    </div>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => openChannelEditor(row.eventKind)}
                    >
                      Configure
                    </Button>
                  </li>
                );
              })}
            </ul>
          )}
        </CardContent>
      </Card>
    </div>
  );
}