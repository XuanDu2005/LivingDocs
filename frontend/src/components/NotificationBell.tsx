import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Bell, Check, RefreshCw } from 'lucide-react';
import { Button } from './ui/button';
import { Badge } from './ui/badge';
import { notificationsApi, Notification } from '../services/notifications';
import { describeError } from '../services/auth';
import { formatDistanceToNow, parseISO } from 'date-fns';

/**
 * Header bell icon with unread badge and popover list.
 *
 * <p>Polls the unread-count endpoint on an idle interval (currently
 * every 60s) so the badge stays fresh without a websocket transport.
 * Clicking the icon opens a small panel with the latest notifications;
 * each row can be marked read individually.
 */
export function NotificationBell() {
  const { t } = useTranslation();
  const [items, setItems] = useState<Notification[]>([]);
  const [count, setCount] = useState<number>(0);
  const [open, setOpen] = useState<boolean>(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [list, count] = await Promise.all([
        notificationsApi.list(false),
        notificationsApi.unreadCount(),
      ]);
      setItems(list);
      setCount(count);
    } catch (err) {
      setError(describeError(err));
    }
  }, []);

  useEffect(() => {
    void refresh();
    const interval = window.setInterval(() => {
      void refresh();
    }, 60_000);
    return () => window.clearInterval(interval);
  }, [refresh]);

  // Close on outside click so the popover does not stay pinned open.
  useEffect(() => {
    if (!open) return;
    function onClick(e: MouseEvent) {
      if (!containerRef.current) return;
      if (!containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    window.addEventListener('mousedown', onClick);
    return () => window.removeEventListener('mousedown', onClick);
  }, [open]);

  async function onMarkRead(n: Notification) {
    if (n.readAt) return;
    setBusyId(n.id);
    try {
      await notificationsApi.markRead(n.id);
      setItems((prev) =>
        prev.map((x) =>
          x.id === n.id ? { ...x, readAt: new Date().toISOString() } : x,
        ),
      );
      setCount((c) => Math.max(0, c - 1));
    } catch (err) {
      setError(describeError(err));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="relative" ref={containerRef}>
      <Button
        variant="ghost"
        size="sm"
        aria-label={t('notifications.openBell')}
        onClick={() => setOpen((o) => !o)}
        className="relative"
      >
        <Bell className="h-4 w-4" />
        {count > 0 && (
          <span className="absolute -right-0.5 -top-0.5 inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-semibold leading-none text-destructive-foreground">
            {count > 99 ? '99+' : count}
          </span>
        )}
      </Button>
      {open && (
        <div className="absolute right-0 z-50 mt-2 w-80 rounded-md border bg-card text-card-foreground shadow-lg">
          <div className="flex items-center justify-between border-b px-3 py-2">
            <div className="text-sm font-medium">{t('notifications.title')}</div>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => void refresh()}
              aria-label={t('notifications.refresh')}
            >
              <RefreshCw className="h-3.5 w-3.5" />
            </Button>
          </div>
          {error && (
            <div className="px-3 py-2 text-xs text-destructive">{error}</div>
          )}
          <ul className="max-h-80 divide-y overflow-auto">
            {items.length === 0 ? (
              <li className="px-3 py-6 text-center text-xs text-muted-foreground">
                {t('notifications.empty')}
              </li>
            ) : (
              items.slice(0, 15).map((n) => (
                <li
                  key={n.id}
                  className={`px-3 py-2 ${n.readAt ? 'bg-card' : 'bg-muted/30'}`}
                >
                  <div className="flex items-start justify-between gap-2">
                    <div className="flex-1">
                      <div className="text-sm font-medium">{n.title}</div>
                      {n.body && (
                        <div className="mt-0.5 line-clamp-2 text-xs text-muted-foreground">
                          {n.body}
                        </div>
                      )}
                      <div className="mt-1 flex items-center gap-2 text-[10px] text-muted-foreground">
                        <Badge variant="muted" className="text-[9px]">{n.kind}</Badge>
                        <span>{formatDistanceToNow(parseISO(n.createdAt), { addSuffix: true })}</span>
                      </div>
                    </div>
                    {!n.readAt && (
                      <Button
                        variant="ghost"
                        size="sm"
                        disabled={busyId === n.id}
                        onClick={() => void onMarkRead(n)}
                        aria-label={t('notifications.markRead')}
                      >
                        <Check className="h-3.5 w-3.5" />
                      </Button>
                    )}
                  </div>
                  {n.link && (
                    <Link
                      to={n.link}
                      className="mt-1 inline-block text-[11px] text-primary hover:underline"
                      onClick={() => setOpen(false)}
                    >
                      {t('notifications.openLink')}
                    </Link>
                  )}
                </li>
              ))
            )}
          </ul>
        </div>
      )}
    </div>
  );
}