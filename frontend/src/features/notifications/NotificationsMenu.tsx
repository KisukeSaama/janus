import { useEffect, useRef, useState } from 'react';
import { AlertTriangle, Bell, Check, Info, X } from 'lucide-react';

import { useDismissNotification, useMarkNotificationsRead, useNotifications, type Notification } from '../../api';
import { ExpiryState } from '../../components';
import { useI18n } from '../../i18n';
import { NOTICE_DAYS, STAGE_TONE } from '../../lib/expiry';

/**
 * What Janus has to say, for the operator who did open the console.
 *
 * The mail reaches whoever did not. This is the same announcements, read where the records are, and
 * it is deliberately not cleared by being looked at: a badge that disappears on a glance is how a
 * key expires anyway. Marking read and dismissing are both things you choose to do.
 */

const TONE_TEXT: Record<'bad' | 'warn' | 'info', string> = {
  bad: 'text-bad',
  warn: 'text-warn',
  info: 'text-text-3',
};

function StageIcon({ notification }: { notification: Notification }) {
  const tone = STAGE_TONE[notification.stage];
  const Icon = tone === 'info' ? Info : AlertTriangle;
  return <Icon size={16} strokeWidth={2.25} className={`mt-0.5 shrink-0 ${TONE_TEXT[tone]}`} />;
}

export function NotificationsMenu({ onNavigate }: { onNavigate?: () => void }) {
  const { t, tc, formatAge } = useI18n();
  const feed = useNotifications();
  const markAllRead = useMarkNotificationsRead();
  const dismiss = useDismissNotification();

  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  // A feed that cannot be read is not worth an error banner over the whole console; the
  // announcements are still in the audit trail and in the mail that carried them.
  const items = feed.data?.items ?? [];
  const unread = feed.data?.unread ?? 0;
  const busy = markAllRead.isPending || dismiss.isPending;

  return (
    <div className="relative" ref={wrapRef}>
      <button
        ref={triggerRef}
        className="btn btn-secondary relative aspect-square px-0"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={
          unread > 0 ? `${t('notifications.label')}, ${tc('notifications.unread', unread)}` : t('notifications.label')
        }
        onClick={() => setOpen((o) => !o)}
      >
        <Bell size={16} strokeWidth={2} />
        {unread > 0 && (
          <span
            aria-hidden="true"
            className="stamp num absolute -right-1.5 -top-1.5 min-w-[1.15rem] rounded-[3px] bg-accent px-1 py-0.5 text-center text-on-accent"
          >
            {unread > 9 ? '9+' : unread}
          </span>
        )}
      </button>

      {open && (
        <div
          role="dialog"
          aria-label={t('notifications.title')}
          className="absolute right-0 top-[calc(100%+0.5rem)] z-40 w-[min(24rem,calc(100vw-2rem))] rounded-panel border border-line bg-surface shadow-overlay [animation:fade-in_140ms_var(--ease-out-quint)]"
        >
          <div className="flex items-start justify-between gap-3 border-b border-line px-4 py-3">
            <div>
              <p className="text-sm font-semibold">{t('notifications.title')}</p>
              <p className="mt-0.5 text-xs text-text-2">{t('notifications.lead')}</p>
            </div>
            {unread > 0 && (
              <button
                className="btn btn-sm btn-quiet shrink-0"
                disabled={busy}
                onClick={() => markAllRead.mutate()}
              >
                {t('notifications.markAllRead')}
              </button>
            )}
          </div>

          {items.length === 0 ? (
            <div className="flex items-start gap-3 px-4 py-6">
              <Check size={17} strokeWidth={2.25} className="mt-0.5 shrink-0 text-ok" />
              <div>
                <p className="text-sm font-medium">{t('notifications.empty')}</p>
                <p className="mt-1 text-xs text-text-2">{t('notifications.emptyHint', { days: NOTICE_DAYS })}</p>
              </div>
            </div>
          ) : (
            <ul className="max-h-[26rem] divide-y divide-line overflow-y-auto">
              {items.map((item) => (
                <li key={item.id} className="flex items-start gap-3 px-4 py-3">
                  <StageIcon notification={item} />
                  <div className="min-w-0 flex-1">
                    <p className={`truncate text-sm ${item.readAt ? 'text-text-2' : 'font-medium'}`}>
                      {item.credentialName}
                    </p>
                    <p className="mt-0.5 truncate text-xs text-text-2">{item.providerName}</p>
                    <div className="mt-1.5">
                      <ExpiryState expiresAt={item.expiresAt} />
                    </div>
                    <p className="mt-1 text-2xs text-text-3">
                      {t('notifications.raised', { age: formatAge(item.createdAt) })}
                    </p>
                  </div>
                  <button
                    className="btn btn-sm btn-quiet -mr-2 aspect-square shrink-0 px-0"
                    disabled={busy}
                    aria-label={t('notifications.dismissLabel', { name: item.credentialName })}
                    onClick={() => dismiss.mutate(item.id)}
                  >
                    <X size={15} />
                  </button>
                </li>
              ))}
            </ul>
          )}

          {items.length > 0 && onNavigate && (
            <div className="border-t border-line px-4 py-3">
              <button
                className="btn btn-sm btn-secondary w-full"
                onClick={() => {
                  setOpen(false);
                  onNavigate();
                }}
              >
                {t('notifications.go')}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
