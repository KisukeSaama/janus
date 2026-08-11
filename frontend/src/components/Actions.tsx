import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { AlertTriangle, Check, Copy } from 'lucide-react';

import { useI18n } from '../i18n';
import { useCopy } from '../hooks/useCopy';

export function CopyButton({ value, label }: { value: string; label?: string }) {
  const { t } = useI18n();
  const [copied, copy] = useCopy();
  return (
    <button
      type="button"
      className="btn btn-sm btn-secondary shrink-0 self-start"
      aria-label={label ? `${t('common.copy')} ${label}` : t('common.copy')}
      onClick={() => void copy(value)}
    >
      {copied ? <Check size={14} className="text-ok" /> : <Copy size={14} />}
      <span className="sr-only" aria-live="polite">
        {copied ? t('common.copied') : ''}
      </span>
    </button>
  );
}

/**
 * A machine value with the one thing anybody wants to do with it. Multi-line values keep their line
 * breaks, because a curl command that has to be reassembled by hand is not a copyable command.
 */

/**
 * A machine value with the one thing anybody wants to do with it. Multi-line values keep their line
 * breaks, because a curl command that has to be reassembled by hand is not a copyable command.
 */
export function CopyField({ label, value, block = false }: { label: string; value: string; block?: boolean }) {
  const Value = block ? 'pre' : 'p';
  return (
    <div>
      <p className="stamp mb-1.5 text-text-2">{label}</p>
      <div className="flex items-stretch gap-2">
        <Value className="data min-w-0 flex-1 break-all rounded-control border border-line bg-sunk px-3 py-2 text-xs leading-5 whitespace-pre-wrap">
          {value}
        </Value>
        <CopyButton value={value} label={label} />
      </div>
    </div>
  );
}

/* ── Page furniture ────────────────────────────────────────────────────── */

/**
 * The same three lines at the top of every page: where you are, what this is, what it is for. The
 * fourth slot on the right holds whatever the page offers, which is a button on a collection and a
 * status on a record.
 *
 * Every row here has a floor height, and the section line is printed whether it names a section or
 * offers a way back. That is what keeps a title, a table, and a form landing on the same pixel from
 * one page to the next instead of stepping up and down as the copy changes length.
 *
 * Reading order on a phone is section, title, explanation, action. On a wide screen the action moves
 * up beside the title, where the eye already expects it.
 */

/**
 * Two steps beside the row. A blocking modal would hide the record being judged; this anchored
 * confirmation keeps the record visible and states the consequence before the final action.
 */
export function ArmedAction({
  trigger,
  confirm,
  pending,
  description,
  destructive = false,
  prominent = false,
  onConfirm,
}: {
  trigger: string;
  confirm: string;
  pending: string;
  description: string;
  destructive?: boolean;
  /** Raises the resting state when the console is actively recommending this action. */
  prominent?: boolean;
  onConfirm: () => Promise<void>;
}) {
  const { t } = useI18n();
  const [armed, setArmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [position, setPosition] = useState({ left: 16, top: 16, width: 320 });
  const id = useId();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (armed) confirmRef.current?.focus();
  }, [armed]);

  useLayoutEffect(() => {
    if (!armed) return;

    const place = () => {
      const anchor = triggerRef.current?.getBoundingClientRect();
      if (!anchor) return;
      const width = Math.min(360, window.innerWidth - 32);
      const left = Math.max(16, Math.min(anchor.right - width, window.innerWidth - width - 16));
      const height = panelRef.current?.offsetHeight ?? 0;
      const below = anchor.bottom + 8;
      const top = below + height <= window.innerHeight - 16 ? below : Math.max(16, anchor.top - height - 8);
      setPosition({ left, top, width });
    };

    place();
    window.addEventListener('resize', place);
    window.addEventListener('scroll', place, true);
    return () => {
      window.removeEventListener('resize', place);
      window.removeEventListener('scroll', place, true);
    };
  }, [armed]);

  useEffect(() => {
    if (!armed) return;
    const dismiss = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!panelRef.current?.contains(target) && !triggerRef.current?.contains(target)) setArmed(false);
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) {
        setArmed(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener('pointerdown', dismiss);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', dismiss);
      document.removeEventListener('keydown', escape);
    };
  }, [armed, busy]);

  return (
    <>
      <button
        ref={triggerRef}
        className={`btn btn-sm ${prominent ? 'btn-secondary font-semibold' : 'btn-quiet'}`}
        aria-expanded={armed}
        aria-controls={armed ? id : undefined}
        onClick={() => setArmed((value) => !value)}
      >
        {trigger}
        <span className="sr-only"> ({description})</span>
      </button>
      {armed &&
        createPortal(
          <div
            ref={panelRef}
            id={id}
            role="dialog"
            aria-modal="false"
            aria-label={trigger}
            className="fixed z-50 rounded-panel border border-line bg-surface p-3 text-left shadow-lg"
            style={position}
          >
            <div className="flex items-start gap-2.5">
              <AlertTriangle
                size={17}
                className={destructive ? 'mt-0.5 shrink-0 text-bad' : 'mt-0.5 shrink-0 text-warn'}
                aria-hidden="true"
              />
              <p className="text-xs leading-5 text-text-2">{description}</p>
            </div>
            <div className="mt-3 flex justify-end gap-2">
              <button className="btn btn-sm btn-quiet" disabled={busy} onClick={() => setArmed(false)}>
                {t('common.cancel')}
              </button>
          <button
            ref={confirmRef}
            className={`btn btn-sm font-semibold ${destructive ? 'btn-destructive' : 'btn-secondary'}`}
            disabled={busy}
            aria-label={description}
            onClick={async () => {
              setBusy(true);
              try {
                await onConfirm();
              } finally {
                setBusy(false);
                setArmed(false);
              }
            }}
          >
            {busy ? pending : confirm}
          </button>
            </div>
          </div>,
          document.body,
        )}
    </>
  );
}

export function DeleteAction({
  label,
  consequence,
  onDelete,
}: {
  label: string;
  consequence: string;
  onDelete: () => Promise<void>;
}) {
  const { t } = useI18n();
  return (
    <ArmedAction
      trigger={t('common.delete')}
      confirm={t('common.confirmDelete')}
      pending={t('common.deleting')}
      description={`${t('common.delete')} ${label}. ${consequence}`}
      destructive
      onConfirm={onDelete}
    />
  );
}
