import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowLeft, CheckCircle2, Eye, Megaphone, Pencil, Send } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Input } from '../components/ui/input';
import { Textarea } from '../components/ui/textarea';
import { Label } from '../components/ui/label';
import {
  Dialog, DialogContent, DialogDescription, DialogFooter,
  DialogHeader, DialogTitle,
} from '../components/ui/dialog';
import {
  Card, CardContent, CardHeader, CardTitle, CardDescription,
} from '../components/ui/card';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '../components/ui/select';
import { LoadingState, ErrorState, EmptyState } from '../components/ui/states';
import { describeError } from '../services/auth';
import {
  BroadcastHistoryEntry,
  NotificationTarget,
  SendNotificationPayload,
  adminNotificationsApi,
} from '../services/notifications';
import { Role } from '../types/admin';
import { adminApi } from '../services/adminApi';
import { format, parseISO } from 'date-fns';

const TARGET_OPTIONS: NotificationTarget[] = ['all', 'role', 'user'];

function targetLabel(value: NotificationTarget, t: (k: string) => string): string {
  switch (value) {
    case 'all': return t('adminNotifications.targetAll');
    case 'role': return t('adminNotifications.targetRole');
    case 'user': return t('adminNotifications.targetUser');
  }
}

/**
 * Page where an administrator composes a platform-wide notification.
 *
 * <p>Targets: a single user (by id), a platform role (broadcast to all
 * active members), or every enabled user. The result panel shows how
 * many notifications were actually persisted (duplicates dropped when
 * a user matches multiple roles).
 */
export default function AdminNotificationsPage() {
  const { t } = useTranslation();

  const [target, setTarget] = useState<NotificationTarget>('all');
  const [userId, setUserId] = useState<string>('');
  const [roleCode, setRoleCode] = useState<string>('DEVELOPER');
  const [kind, setKind] = useState<string>('platform');
  const [title, setTitle] = useState<string>('');
  const [body, setBody] = useState<string>('');
  const [link, setLink] = useState<string>('');

  const [sending, setSending] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [lastResult, setLastResult] = useState<{
    recipients: number;
    duplicatesDropped: number;
    title: string;
  } | null>(null);

  const [history, setHistory] = useState<BroadcastHistoryEntry[]>([]);
  const [historyLoading, setHistoryLoading] = useState<boolean>(true);
  const [historyError, setHistoryError] = useState<string | null>(null);

  const [editingEntry, setEditingEntry] = useState<BroadcastHistoryEntry | null>(null);
  const [dialogMode, setDialogMode] = useState<'view' | 'edit'>('view');

  const [roles, setRoles] = useState<Role[]>([]);

  async function loadHistory() {
    setHistoryLoading(true);
    setHistoryError(null);
    try {
      const [items, roleRows] = await Promise.all([
        adminNotificationsApi.history(50),
        adminApi.listRoles().catch(() => [] as Role[]),
      ]);
      setHistory(items);
      setRoles(roleRows);
    } catch (err) {
      setHistoryError(describeError(err));
    } finally {
      setHistoryLoading(false);
    }
  }

  useEffect(() => {
    void loadHistory();
  }, []);

  function openEntry(entry: BroadcastHistoryEntry, mode: 'view' | 'edit') {
    setEditingEntry(entry);
    setDialogMode(mode);
    if (mode === 'edit') {
      // Pre-fill the compose form so admin can adjust and resend.
      const target: NotificationTarget =
        entry.target === 'role' ? 'role'
        : entry.target === 'user' ? 'user'
        : 'all';
      setTarget(target);
      if (target === 'role' && entry.roleCode) setRoleCode(entry.roleCode);
      if (target === 'user' && entry.actorUserId) setUserId(entry.actorUserId);
      setKind(entry.kind || 'general');
      setTitle(entry.title || '');
      setBody(entry.body || '');
      setLink(entry.link || '');
      // Clear stale state from a previous send.
      setError(null);
      setLastResult(null);
    }
  }

  function closeDialog() {
    setEditingEntry(null);
    setDialogMode('view');
  }

  function canSubmit(): boolean {
    if (!title.trim()) return false;
    if (target === 'user' && !userId.trim()) return false;
    if (target === 'role' && !roleCode.trim()) return false;
    return true;
  }

  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!canSubmit()) return;
    const payload: SendNotificationPayload = {
      target,
      kind: kind.trim() || 'general',
      title: title.trim(),
      body: body.trim() || undefined,
      link: link.trim() || undefined,
    };
    if (target === 'user') payload.userId = userId.trim();
    if (target === 'role') payload.roleCode = roleCode.trim().toUpperCase();

    setSending(true);
    setError(null);
    try {
      const result = await adminNotificationsApi.send(payload);
      setLastResult({
        recipients: result.recipients,
        duplicatesDropped: result.duplicatesDropped,
        title: result.title,
      });
      // Clear form so a second send can't accidentally re-send the
      // exact same text without the admin re-confirming it.
      setTitle('');
      setBody('');
      setLink('');
      await loadHistory();
    } catch (err) {
      setError(describeError(err));
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <Button variant="ghost" size="sm" asChild>
          <Link to="/admin">
            <ArrowLeft className="mr-1 h-4 w-4" />
            {t('adminNotifications.backToAdmin')}
          </Link>
        </Button>
        <h1 className="mt-2 text-2xl font-bold tracking-tight">
          {t('adminNotifications.title')}
        </h1>
        <p className="text-sm text-muted-foreground">
          {t('adminNotifications.subtitle')}
        </p>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Send className="h-4 w-4" />
              {t('adminNotifications.composeTitle')}
            </CardTitle>
            <CardDescription>{t('adminNotifications.composeDesc')}</CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={onSubmit} className="space-y-4">
              <div className="space-y-1.5">
                <Label htmlFor="notif-target">{t('adminNotifications.target')}</Label>
                <Select
                  value={target}
                  onValueChange={(v) => setTarget(v as NotificationTarget)}
                >
                  <SelectTrigger id="notif-target">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {TARGET_OPTIONS.map((value) => (
                      <SelectItem key={value} value={value}>
                        {targetLabel(value, t)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              {target === 'user' && (
                <div className="space-y-1.5">
                  <Label htmlFor="notif-user-id">
                    {t('adminNotifications.userIdLabel')}
                  </Label>
                  <Input
                    id="notif-user-id"
                    value={userId}
                    onChange={(e) => setUserId(e.target.value)}
                    placeholder="00000000-0000-0000-0000-000000000000"
                    required
                  />
                </div>
              )}

              {target === 'role' && (
                <div className="space-y-1.5">
                  <Label htmlFor="notif-role">
                    {t('adminNotifications.roleCodeLabel')}
                  </Label>
                  <Select
                    value={roleCode}
                    onValueChange={setRoleCode}
                  >
                    <SelectTrigger id="notif-role">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {roles.length === 0 && (
                        <SelectItem value={roleCode}>{roleCode}</SelectItem>
                      )}
                      {roles.map((r) => (
                        <SelectItem key={r.code} value={r.code}>
                          {r.code}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              )}

              <div className="space-y-1.5">
                <Label htmlFor="notif-kind">{t('adminNotifications.kindLabel')}</Label>
                <Input
                  id="notif-kind"
                  value={kind}
                  onChange={(e) => setKind(e.target.value)}
                  placeholder="platform"
                />
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="notif-title">{t('adminNotifications.titleLabel')}</Label>
                <Input
                  id="notif-title"
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                  placeholder={t('adminNotifications.titlePlaceholder')}
                  maxLength={255}
                  required
                />
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="notif-body">{t('adminNotifications.bodyLabel')}</Label>
                <Textarea
                  id="notif-body"
                  value={body}
                  onChange={(e) => setBody(e.target.value)}
                  rows={4}
                  placeholder={t('adminNotifications.bodyPlaceholder')}
                />
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="notif-link">{t('adminNotifications.linkLabel')}</Label>
                <Input
                  id="notif-link"
                  value={link}
                  onChange={(e) => setLink(e.target.value)}
                  placeholder="/workspaces/ai-password/docs"
                />
              </div>

              {error && (
                <div className="rounded border border-destructive/50 bg-destructive/10 px-3 py-2 text-sm text-destructive">
                  {error}
                </div>
              )}

              {lastResult && (
                <div className="flex items-start gap-2 rounded border border-success/50 bg-success/10 px-3 py-2 text-sm text-success">
                  <CheckCircle2 className="mt-0.5 h-4 w-4" />
                  <div>
                    {t('adminNotifications.sentOk', {
                      title: lastResult.title,
                      count: lastResult.recipients,
                    })}
                    {lastResult.duplicatesDropped > 0 && (
                      <div className="text-xs opacity-80">
                        {t('adminNotifications.duplicates', {
                          count: lastResult.duplicatesDropped,
                        })}
                      </div>
                    )}
                  </div>
                </div>
              )}

              <Button type="submit" disabled={sending || !canSubmit()}>
                <Send className="mr-1 h-4 w-4" />
                {sending ? t('adminNotifications.sending') : t('adminNotifications.send')}
              </Button>
            </form>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Megaphone className="h-4 w-4" />
              {t('adminNotifications.recentTitle')}
            </CardTitle>
            <CardDescription>{t('adminNotifications.recentDesc')}</CardDescription>
          </CardHeader>
          <CardContent>
            {historyLoading ? (
              <LoadingState message={t('adminNotifications.loading')} />
            ) : historyError ? (
              <ErrorState message={historyError} />
            ) : history.length === 0 ? (
              <EmptyState
                title={t('adminNotifications.emptyTitle')}
                description={t('adminNotifications.emptyDesc')}
              />
            ) : (
              <ul className="space-y-2">
                {history.slice(0, 20).map((h) => (
                  <li
                    key={h.id}
                    className="rounded-md border bg-muted/30 px-3 py-2"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <div className="text-sm font-medium">{h.title}</div>
                      <div className="flex items-center gap-1.5">
                        <Badge variant="muted" className="text-[10px]">{h.kind}</Badge>
                        <Badge variant="outline" className="text-[10px]">
                          {h.target === 'role' && h.roleCode
                            ? `${t('adminNotifications.targetRole')} · ${h.roleCode}`
                            : h.target === 'user'
                              ? t('adminNotifications.targetUser')
                              : t('adminNotifications.targetAll')}
                        </Badge>
                      </div>
                    </div>
                    {h.body && (
                      <div className="mt-1 line-clamp-2 text-xs text-muted-foreground">
                        {h.body}
                      </div>
                    )}
                    <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted-foreground">
                      <span>{format(parseISO(h.sentAt), 'MMM d, HH:mm')}</span>
                      <span>
                        {t('adminNotifications.recipients', { count: h.recipients })}
                      </span>
                      {h.link && (
                        <code className="break-all">{h.link}</code>
                      )}
                    </div>
                    <div className="mt-2 flex items-center gap-2">
                      <Button
                        type="button"
                        size="sm"
                        variant="outline"
                        onClick={() => openEntry(h, 'view')}
                      >
                        <Eye className="mr-1 h-3 w-3" />
                        {t('adminNotifications.view')}
                      </Button>
                      <Button
                        type="button"
                        size="sm"
                        variant="secondary"
                        onClick={() => openEntry(h, 'edit')}
                      >
                        <Pencil className="mr-1 h-3 w-3" />
                        {t('adminNotifications.editResend')}
                      </Button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </CardContent>
        </Card>
      </div>

      <Dialog open={editingEntry !== null} onOpenChange={(open) => { if (!open) closeDialog(); }}>
        <DialogContent className="max-w-xl">
          <DialogHeader>
            <DialogTitle>
              {editingEntry
                ? (dialogMode === 'edit'
                    ? t('adminNotifications.editResend')
                    : t('adminNotifications.viewTitle'))
                : t('adminNotifications.viewTitle')}
            </DialogTitle>
            <DialogDescription>
              {editingEntry && t('adminNotifications.originalSent', {
                when: format(parseISO(editingEntry.sentAt), 'PPpp'),
                recipients: editingEntry.recipients,
              })}
            </DialogDescription>
          </DialogHeader>

          {editingEntry && (
            <div className="space-y-3">
              <div className="flex flex-wrap items-center gap-1.5">
                <Badge variant="muted" className="text-[10px]">{editingEntry.kind}</Badge>
                <Badge variant="outline" className="text-[10px]">
                  {editingEntry.target === 'role' && editingEntry.roleCode
                    ? `${t('adminNotifications.targetRole')} · ${editingEntry.roleCode}`
                    : editingEntry.target === 'user'
                      ? t('adminNotifications.targetUser')
                      : t('adminNotifications.targetAll')}
                </Badge>
              </div>

              {dialogMode === 'view' ? (
                <>
                  <div>
                    <div className="text-xs font-medium uppercase text-muted-foreground">
                      {t('adminNotifications.titleLabel')}
                    </div>
                    <div className="mt-1 text-sm">{editingEntry.title}</div>
                  </div>
                  {editingEntry.body && (
                    <div>
                      <div className="text-xs font-medium uppercase text-muted-foreground">
                        {t('adminNotifications.bodyLabel')}
                      </div>
                      <div className="mt-1 whitespace-pre-wrap text-sm">{editingEntry.body}</div>
                    </div>
                  )}
                  {editingEntry.link && (
                    <div>
                      <div className="text-xs font-medium uppercase text-muted-foreground">
                        {t('adminNotifications.linkLabel')}
                      </div>
                      <code className="mt-1 block break-all text-xs">{editingEntry.link}</code>
                    </div>
                  )}
                </>
              ) : (
                <>
                  <div className="rounded border border-info/30 bg-info/5 px-3 py-2 text-xs text-info-foreground">
                    {t('adminNotifications.editHint')}
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="edit-title">{t('adminNotifications.titleLabel')}</Label>
                    <Input
                      id="edit-title"
                      value={title}
                      onChange={(e) => setTitle(e.target.value)}
                      maxLength={255}
                    />
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="edit-body">{t('adminNotifications.bodyLabel')}</Label>
                    <Textarea
                      id="edit-body"
                      value={body}
                      onChange={(e) => setBody(e.target.value)}
                      rows={4}
                    />
                  </div>
                  <div className="space-y-1.5">
                    <Label htmlFor="edit-link">{t('adminNotifications.linkLabel')}</Label>
                    <Input
                      id="edit-link"
                      value={link}
                      onChange={(e) => setLink(e.target.value)}
                    />
                  </div>
                </>
              )}
            </div>
          )}

          <DialogFooter>
            {dialogMode === 'view' ? (
              <>
                <Button variant="outline" onClick={closeDialog}>
                  {t('adminNotifications.close')}
                </Button>
                {editingEntry && (
                  <Button
                    type="button"
                    onClick={() => openEntry(editingEntry, 'edit')}
                  >
                    <Pencil className="mr-1 h-4 w-4" />
                    {t('adminNotifications.editResend')}
                  </Button>
                )}
              </>
            ) : (
              <>
                <Button variant="outline" onClick={closeDialog}>
                  {t('adminNotifications.cancel')}
                </Button>
                <Button
                  type="button"
                  disabled={sending || !canSubmit()}
                  onClick={async () => {
                    await onSubmit(new Event('submit') as unknown as React.FormEvent<HTMLFormElement>);
                    closeDialog();
                  }}
                >
                  <Send className="mr-1 h-4 w-4" />
                  {sending ? t('adminNotifications.sending') : t('adminNotifications.resend')}
                </Button>
              </>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}