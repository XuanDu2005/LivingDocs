import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Bell, Check, RefreshCw, X } from 'lucide-react';
import { Button } from './ui/button';
import { Badge } from './ui/badge';
import { notificationsApi, Notification } from '../services/notifications';
import { describeError } from '../services/auth';
import { formatDistanceToNow, parseISO } from 'date-fns';

const POPOVER_WIDTH = 320;
const POPOVER_GAP = 8;

/**
 * Header bell icon with unread badge and popover list.
 *
 * <p>Polls the unread-count endpoint on an idle interval (currently
 * every 60s) so the badge stays fresh without a websocket transport.
 * Clicking the icon opens a small panel with the latest notifications;
 * each row can be marked read individually.
 *
 * <p>The popover is rendered with <code>position: fixed</code> (not
 * absolute) so it never gets clipped by an <code>overflow</code>
 * ancestor on the sidebar / header. Coordinates are computed from
 * the trigger button's bounding rect.
 */
export function NotificationBell() {
  const { t } = useTranslation();
  const [items, setItems] = useState<Notification[]>([]);
  const [count, setCount] = useState<number>(0);
  const [open, setOpen] = useState<boolean>(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [popoverPos, setPopoverPos] = useState<
    { top: number; left: number; width: number; maxHeight: number } | null
  >(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const popoverRef = useRef<HTMLDivElement | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [list, unread] = await Promise.all([
        notificationsApi.list(false),
        notificationsApi.unreadCount(),
      ]);
      setItems(list);
      setCount(unread);
      setError(null);
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

  // Position the popover against the trigger when it opens and on scroll/resize.
  // Flips below-the-trigger ↔ above-the-trigger so it never gets clipped by
  // the sidebar (which is the host here) or by the viewport bottom.
  useLayoutEffect(() => {
    if (!open) {
      setPopoverPos(null);
      return;
    }
    function place() {
      const btn = triggerRef.current;
      const pop = popoverRef.current;
      if (!btn) return;
      const rect = btn.getBoundingClientRect();
      const vw = window.innerWidth;
      const vh = window.innerHeight;
      const margin = 8;

      // Width: fixed but never wider than the viewport.
      const desiredWidth = POPOVER_WIDTH;
      const maxWidth = Math.max(220, vw - margin * 2);

      // Prefer to the right of the trigger; fall back to the left.
      const spaceRight = vw - rect.right - POPOVER_GAP - margin;
      const spaceLeft = rect.left - POPOVER_GAP - margin;
      const placeRight = spaceRight >= Math.min(desiredWidth, 280) || spaceRight >= spaceLeft;
      let left: number;
      if (placeRight) {
        left = Math.min(rect.right + POPOVER_GAP, vw - Math.min(desiredWidth, maxWidth) - margin);
      } else {
        left = Math.max(rect.left - POPOVER_GAP - Math.min(desiredWidth, maxWidth), margin);
      }

      // Prefer below the trigger; if not enough room, flip above.
      const spaceBelow = vh - rect.bottom - POPOVER_GAP - margin;
      const popMaxHeight = Math.min(480, Math.max(180, Math.max(spaceBelow, 240)));
      let top: number;
      if (spaceBelow >= 200 || spaceBelow >= vh - rect.top) {
        top = rect.bottom + POPOVER_GAP;
      } else {
        // Flip above and clamp to top margin.
        const desiredTop = rect.top - POPOVER_GAP - popMaxHeight;
        top = Math.max(desiredTop, margin);
      }

      setPopoverPos({ top, left, width: Math.min(desiredWidth, maxWidth), maxHeight: popMaxHeight });
      // Force a re-measure after the popover mounts so its actual height
      // can drive a tighter max-height on the next paint.
      if (pop) {
        const actual = pop.getBoundingClientRect();
        if (actual.height > 0) {
          setPopoverPos((prev) =>
            prev ? { ...prev, maxHeight: Math.min(prev.maxHeight, vh - prev.top - margin) } : prev,
          );
        }
      }
    }
    place();
    window.addEventListener('resize', place);
    window.addEventListener('scroll', place, true);
    return () => {
      window.removeEventListener('resize', place);
      window.removeEventListener('scroll', place, true);
    };
  }, [open]);

  // Close on outside click so the popover does not stay pinned open.
  useEffect(() => {
    if (!open) return;
    function onPointerDown(e: MouseEvent | TouchEvent) {
      const target = e.target as Node | null;
      if (!target) return;
      if (popoverRef.current && popoverRef.current.contains(target)) return;
      if (triggerRef.current && triggerRef.current.contains(target)) return;
      setOpen(false);
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') setOpen(false);
    }
    window.addEventListener('mousedown', onPointerDown);
    window.addEventListener('touchstart', onPointerDown);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('mousedown', onPointerDown);
      window.removeEventListener('touchstart', onPointerDown);
      window.removeEventListener('keydown', onKeyDown);
    };
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

  async function onMarkAllRead() {
    const unread = items.filter((n) => !n.readAt);
    if (unread.length === 0) return;
    setBusyId('__all__');
    try {
      for (const n of unread) {
        await notificationsApi.markRead(n.id).catch(() => null);
      }
      const nowIso = new Date().toISOString();
      setItems((prev) => prev.map((x) => (x.readAt ? x : { ...x, readAt: nowIso })));
      setCount(0);
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div>
      <Button
        ref={triggerRef}
        variant="ghost"
        size="sm"
        aria-label={t('notifications.openBell')}
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => setOpen((o) => !o)}
        className="relative w-full justify-start"
      >
        <Bell className="h-4 w-4" />
        <span className="ml-2 text-sm">{t('notifications.title')}</span>
        {count > 0 && (
          <span className="ml-auto inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-semibold leading-none text-destructive-foreground">
            {count > 99 ? '99+' : count}
          </span>
        )}
      </Button>

      {open && popoverPos && (
        <div
          ref={popoverRef}
          role="dialog"
          aria-label={t('notifications.title')}
          style={{
            position: 'fixed',
            top: popoverPos.top,
            left: popoverPos.left,
            width: popoverPos.width,
            maxHeight: popoverPos.maxHeight,
            zIndex: 1000,
          }}
          className="flex flex-col rounded-md border bg-card text-card-foreground shadow-lg"
        >
          <div className="flex items-center justify-between border-b px-3 py-2">
            <div className="text-sm font-medium">{t('notifications.title')}</div>
            <div className="flex items-center gap-1">
              <Button
                variant="ghost"
                size="sm"
                onClick={() => void onMarkAllRead()}
                disabled={busyId === '__all__' || count === 0}
                aria-label={t('notifications.markAllRead')}
                title={t('notifications.markAllRead')}
              >
                <Check className="h-3.5 w-3.5" />
              </Button>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => void refresh()}
                aria-label={t('notifications.refresh')}
                title={t('notifications.refresh')}
              >
                <RefreshCw className="h-3.5 w-3.5" />
              </Button>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => setOpen(false)}
                aria-label={t('notifications.close')}
                title={t('notifications.close')}
              >
                <X className="h-3.5 w-3.5" />
              </Button>
            </div>
          </div>

          {error && (
            <div className="border-b bg-destructive/10 px-3 py-2 text-xs text-destructive">
              {error}
            </div>
          )}

          <ul className="flex-1 divide-y overflow-auto">
            {items.length === 0 ? (
              <li className="px-3 py-6 text-center text-xs text-muted-foreground">
                {t('notifications.empty')}
              </li>
            ) : (
              items.map((n) => (
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

          {items.length > 0 && (
            <div className="border-t px-3 py-1.5 text-right text-[10px] text-muted-foreground">
              {t('notifications.totalCount', { count: items.length })}
            </div>
          )}
        </div>
      )}
    </div>
  );
}