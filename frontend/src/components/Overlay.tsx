import { useEffect, useEffectEvent, useId, useRef, type ReactNode, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { AlertTriangle, Check, Copy, X } from 'lucide-react';

import { useI18n } from '../i18n';
import { useCopy } from '../hooks/useCopy';
import { useFocusTrap } from '../hooks/useFocusTrap';
import { useMediaQuery, WIDE } from '../hooks/useMediaQuery';
import { HomeLink, Wordmark } from './Brand';

function useDismissable(onClose: () => void, ref: RefObject<HTMLElement | null>, autofocus: boolean) {
  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    document.body.style.overflow = 'hidden';
    if (autofocus) ref.current?.querySelector<HTMLElement>('input, select, textarea, button')?.focus();
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = '';
      opener?.focus?.();
    };
  }, [onClose, ref, autofocus]);
}

/**
 * A surface that takes the whole window for the length of one task.
 *
 * Setting up the first connection is not an edit beside the console, it is the reason the console
 * exists, and a 480px rail is the wrong shape for a decision taken in three steps. Everything else
 * still goes in the side panel: this is reserved for the flow that has a beginning and an end.
 */
export function Sheet({
  label,
  head,
  children,
  footer,
  onHome,
}: {
  label: string;
  head?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  /**
   * The corner goes home from here too. What the sheet receives is an intention rather than a
   * destination: a flow holding an entry nobody has written down yet answers the click with a
   * question first, and only the flow knows whether it has one.
   */
  onHome?: () => void;
}) {
  const ref = useRef<HTMLElement>(null);
  useFocusTrap(ref);

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = '';
      opener?.focus?.();
    };
  }, []);

  return (
    <section
      ref={ref}
      role="dialog"
      aria-modal="true"
      aria-label={label}
      className="fixed inset-0 z-50 flex flex-col bg-canvas [animation:fade-in_160ms_var(--ease-out-quint)]"
    >
      <header className="shrink-0 border-b border-line bg-surface">
        <div className="mx-auto flex max-w-[46rem] flex-wrap items-center justify-between gap-x-4 gap-y-2 px-4 py-3 md:px-6">
          {onHome ? <HomeLink onNavigate={onHome} /> : <Wordmark />}
          {head}
        </div>
      </header>

      <div className="min-h-0 flex-1 overflow-y-auto">
        <div className="mx-auto max-w-[46rem] px-4 py-8 md:px-6 md:py-10">{children}</div>
      </div>

      {footer && (
        <footer className="shrink-0 border-t border-line bg-surface">
          <div className="mx-auto flex max-w-[46rem] items-center gap-3 px-4 py-3.5 md:px-6">{footer}</div>
        </footer>
      )}
    </section>
  );
}

/** A right-hand panel on wide screens, a bottom sheet on a phone. */
export function SidePanel({
  title,
  intro,
  onClose,
  children,
}: {
  title: string;
  intro?: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const { t } = useI18n();
  const panelRef = useRef<HTMLElement>(null);
  const titleId = useId();
  const wide = useMediaQuery(WIDE);
  useFocusTrap(panelRef);
  useDismissable(onClose, panelRef, true);

  return (
    <div
      className="fixed inset-0 z-40 flex items-end justify-center bg-scrim [animation:fade-in_160ms_var(--ease-out-quint)] lg:items-stretch lg:justify-end"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <section
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="flex max-h-[92svh] w-full flex-col rounded-t-[10px] border border-line bg-surface shadow-overlay [animation:slide-in-y_240ms_var(--ease-out-quint)] lg:max-h-none lg:h-full lg:max-w-[30rem] lg:rounded-none lg:border-y-0 lg:border-r-0 lg:[animation:slide-in-x_220ms_var(--ease-out-quint)]"
      >
        <header className="flex items-start justify-between gap-4 border-b border-line px-5 py-4 md:px-6">
          <div>
            <h2 id={titleId} className="text-lg font-semibold tracking-title">
              {title}
            </h2>
            {intro && <p className="mt-1 max-w-[46ch] text-xs text-text-2">{intro}</p>}
          </div>
          <button
            className="btn btn-sm btn-quiet -mr-2 aspect-square px-0"
            onClick={onClose}
            aria-label={t('common.close')}
          >
            <X size={17} />
          </button>
        </header>
        <div className="flex-1 overflow-y-auto px-5 py-5 md:px-6 md:py-6">{children}</div>
      </section>
      {!wide && <div className="h-[env(safe-area-inset-bottom)]" />}
    </div>
  );
}

/**
 * The stop before a write nobody can take back.
 *
 * It takes the middle of the window and dims what is behind it, because the console would rather
 * interrupt a reader than let a key, a credential, or an entire catalogue entry go on a click that
 * landed where the eye had not arrived yet. What it asks for is the whole of it: what is about to
 * happen, to which record, and what will be true afterwards.
 *
 * Two details are the point rather than the frame. A destructive dialog opens with the focus on the
 * way out, so the key that dismisses it and the key that fires it are never the same one. And the
 * surface is locked while the request is in flight: a confirmation dismissed mid-write leaves the
 * reader with no idea whether the write happened.
 *
 * Portalled, because these are opened from inside table cells that clip and scroll their own
 * overflow, and a dialog is not part of the row that raised it.
 */
export function ConfirmDialog({
  title,
  description,
  confirm,
  pending,
  destructive = false,
  busy,
  returnFocus,
  onCancel,
  onConfirm,
}: {
  title: string;
  description: string;
  confirm: string;
  pending: string;
  destructive?: boolean;
  busy: boolean;
  /** Where focus belongs afterwards, read on the way out, for when the opener is already gone. */
  returnFocus?: () => HTMLElement | null | undefined;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const { t } = useI18n();
  const dialogRef = useRef<HTMLElement>(null);
  const cancelRef = useRef<HTMLButtonElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);
  const titleId = useId();
  const descriptionId = useId();
  useFocusTrap(dialogRef);

  // Effect Events: the surface is mounted once and torn down once, and nothing about a keystroke or
  // a request in flight should be able to re-run either. Both read the props of the last render
  // without appearing in the dependencies below.
  const escape = useEffectEvent(() => {
    if (!busy) onCancel();
  });
  const restoreFocus = useEffectEvent((opener: HTMLElement | null) => {
    (returnFocus?.() ?? opener)?.focus?.();
  });

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') escape();
    };
    document.addEventListener('keydown', onKey);
    // Restored rather than cleared: this dialog can be raised from inside a sheet that is holding
    // the page still itself, and clearing would hand the scrollbar back under an open surface.
    const scroll = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = scroll;
      restoreFocus(opener);
    };
  }, []);

  useEffect(() => {
    (destructive ? cancelRef : confirmRef).current?.focus();
  }, [destructive]);

  return createPortal(
    <div
      className="fixed inset-0 z-50 grid place-items-center bg-scrim p-4 [animation:fade-in_160ms_var(--ease-out-quint)]"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && !busy) onCancel();
      }}
    >
      <section
        ref={dialogRef}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
        className="w-full max-w-[27rem] rounded-panel border border-line bg-surface shadow-overlay [animation:slide-in-y_220ms_var(--ease-out-quint)]"
      >
        <div className="flex items-start gap-3.5 px-5 pb-5 pt-5">
          <span
            className={`grid h-9 w-9 shrink-0 place-items-center rounded-control ${
              destructive ? 'bg-bad-wash text-bad' : 'bg-warn-wash text-warn'
            }`}
          >
            <AlertTriangle size={18} aria-hidden="true" />
          </span>
          <div className="min-w-0">
            <h2 id={titleId} className="text-lg font-semibold tracking-title">
              {title}
            </h2>
            <p id={descriptionId} className="mt-1.5 max-w-[46ch] text-sm leading-6 text-text-2">
              {description}
            </p>
          </div>
        </div>
        <div className="flex flex-col-reverse gap-2 border-t border-line px-5 py-4 sm:flex-row sm:justify-end">
          <button ref={cancelRef} className="btn btn-secondary" disabled={busy} onClick={onCancel}>
            {t('common.cancel')}
          </button>
          <button
            ref={confirmRef}
            className={`btn ${destructive ? 'btn-destructive' : 'btn-primary'}`}
            disabled={busy}
            onClick={onConfirm}
          >
            {busy ? pending : confirm}
          </button>
        </div>
      </section>
    </div>,
    document.body,
  );
}

export function KeyIssued({ value, onDismiss }: { value: string; onDismiss: () => void }) {
  const { t } = useI18n();
  const [copied, copy] = useCopy();
  const copyRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLElement>(null);
  const titleId = useId();
  useFocusTrap(dialogRef);
  useEffect(() => copyRef.current?.focus(), []);

  // Held still like every other overlay, and restored rather than cleared: this is raised from a
  // panel that is already holding the page, and clearing would hand the scrollbar back under it.
  // No Escape here on purpose — the value is shown once, and the way out is the button that says so.
  useEffect(() => {
    const scroll = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = scroll;
    };
  }, []);

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-scrim p-4 [animation:fade-in_160ms_var(--ease-out-quint)]">
      <section
        ref={dialogRef}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="w-full max-w-lg rounded-panel border border-line bg-surface p-5 shadow-overlay md:p-6"
      >
        <p className="stamp text-accent-text">{t('key.shownOnce')}</p>
        <h2 id={titleId} className="mt-3 text-xl font-semibold tracking-title">
          {t('key.title')}
        </h2>
        <p className="mt-2 max-w-[54ch] text-sm text-text-2">{t('key.intro')}</p>
        <div className="mt-5 flex flex-col items-stretch gap-2 sm:flex-row">
          <p className="data flex-1 break-all rounded-control border border-line bg-sunk px-3 py-2.5 text-sm leading-6">
            {value}
          </p>
          <button ref={copyRef} className="btn btn-secondary sm:self-start" onClick={() => void copy(value)}>
            {copied ? <Check size={15} className="text-ok" /> : <Copy size={15} />}
            {copied ? t('common.copied') : t('common.copy')}
          </button>
        </div>
        <button className="btn btn-primary mt-6 w-full" onClick={onDismiss}>
          {t('key.stored')}
        </button>
      </section>
    </div>
  );
}
