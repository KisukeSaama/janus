import { useEffect, useRef, useState } from 'react';
import { Check, Copy } from 'lucide-react';

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
 * Two steps, in the row. A modal would hide the record being judged; arming the button keeps the
 * record and its consequence on screen together.
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
  const confirmRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (armed) confirmRef.current?.focus();
  }, [armed]);

  // The slot keeps its width in both states so arming never shifts the row.
  return (
    <span className="inline-flex items-center justify-end gap-2 lg:min-w-[9.5rem]">
      {armed ? (
        <>
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
          <button className="btn btn-sm btn-quiet" disabled={busy} onClick={() => setArmed(false)}>
            {t('common.cancel')}
          </button>
        </>
      ) : (
        <button
          className={`btn btn-sm ${prominent ? 'btn-secondary font-semibold' : 'btn-quiet'}`}
          onClick={() => setArmed(true)}
        >
          {trigger}
          <span className="sr-only"> ({description})</span>
        </button>
      )}
    </span>
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
