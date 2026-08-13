import {
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
} from 'react';
import { createPortal } from 'react-dom';
import { Check, Copy, MoreHorizontal } from 'lucide-react';

import { useI18n } from '../i18n';
import { useCopy } from '../hooks/useCopy';
import { useDismiss } from '../hooks/useDismiss';
import { ConfirmDialog } from './Overlay';

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

/* ── Anchored panels ───────────────────────────────────────────────────── */

/**
 * A panel pinned to the control that opened it, drawn in a portal.
 *
 * Row controls live inside a table that scrolls sideways and clips what leaves it, so anything that
 * has to escape the cell is measured against the viewport and replaced as the page moves under it.
 */
function useAnchoredPanel({
  open,
  maxWidth,
  onDismiss,
}: {
  open: boolean;
  maxWidth: number;
  onDismiss: () => void;
}) {
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState({ left: 16, top: 16, width: maxWidth });

  useDismiss(open, panelRef, triggerRef, onDismiss);

  useLayoutEffect(() => {
    if (!open) return;

    const place = () => {
      const anchor = triggerRef.current?.getBoundingClientRect();
      if (!anchor) return;
      const width = Math.min(maxWidth, window.innerWidth - 32);
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
  }, [open, maxWidth]);

  return { triggerRef, panelRef, position };
}

/**
 * A control that asks before it writes.
 *
 * The question itself is a dialog in the middle of the window rather than a note beside the button,
 * so the same surface answers it everywhere in the console: from a row, from a record, from inside
 * a menu. The trigger's only job is to name what is about to be asked.
 */
export function ConfirmAction({
  trigger,
  title,
  confirm,
  pending,
  description,
  destructive = false,
  prominent = false,
  onConfirm,
}: {
  trigger: string;
  /** Heading of the dialog. The trigger's own words, unless they are too thin to stand alone. */
  title?: string;
  confirm: string;
  pending: string;
  description: string;
  destructive?: boolean;
  /** Raises the resting state when the console is actively recommending this action. */
  prominent?: boolean;
  onConfirm: () => Promise<void>;
}) {
  const [asking, setAsking] = useState(false);
  const [busy, setBusy] = useState(false);

  return (
    <>
      <button
        className={`btn btn-sm ${prominent ? 'btn-secondary font-semibold' : 'btn-quiet'}`}
        aria-haspopup="dialog"
        // Rows print the same verb on every line; the record it acts on is what tells them apart.
        aria-label={title}
        onClick={() => setAsking(true)}
      >
        {trigger}
      </button>
      {asking && (
        <ConfirmDialog
          title={title ?? trigger}
          description={description}
          confirm={confirm}
          pending={pending}
          destructive={destructive}
          busy={busy}
          onCancel={() => setAsking(false)}
          onConfirm={async () => {
            setBusy(true);
            try {
              await onConfirm();
            } finally {
              setBusy(false);
              setAsking(false);
            }
          }}
        />
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
    <ConfirmAction
      trigger={t('common.delete')}
      title={`${t('common.delete')} ${label}`}
      confirm={t('common.confirmDelete')}
      pending={t('common.deleting')}
      description={consequence}
      destructive
      onConfirm={onDelete}
    />
  );
}

/* ── Row overflow ──────────────────────────────────────────────────────── */

export type RowAction = {
  key: string;
  label: string;
  /** Names the authority the entry belongs to. Consecutive entries sharing one print it once. */
  group?: string;
  destructive?: boolean;
} & (
  | { onSelect: () => void; consequence?: never; confirm?: never; pending?: never; onConfirm?: never }
  | {
      onSelect?: never;
      /** What the reader is about to cause. Its presence is what makes the entry ask first. */
      consequence: string;
      confirm?: string;
      pending?: string;
      onConfirm: () => Promise<void>;
    }
);

/**
 * The rest of what a row can do, behind one control.
 *
 * A register is read far more often than it is clicked, and a row that prints four verbs at all
 * times is four verbs of noise on every line that nobody is acting on. One action stays in the row,
 * the one the reader came to take; everything else is named here, under the authority it belongs to
 * — because "delete my credential" and "delete this API for the whole deployment" were two buttons
 * reading `Delete`, side by side, in the same row.
 *
 * Choosing a consequential entry closes the menu and asks in the dialog every other confirmation in
 * the console uses, so the question always arrives on the same surface, whatever raised it.
 */
export function RowMenu({ label, actions }: { label: string; actions: RowAction[] }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [asking, setAsking] = useState<RowAction | null>(null);
  const [busy, setBusy] = useState(false);
  const id = useId();

  const { triggerRef, panelRef, position } = useAnchoredPanel({
    open,
    maxWidth: 232,
    onDismiss: () => setOpen(false),
  });

  // Focus lands on the first entry, so the menu is operable from the keyboard the moment it opens.
  useEffect(() => {
    if (open) panelRef.current?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus();
  }, [open, panelRef]);

  if (actions.length === 0) return null;

  const groups: { name?: string; items: RowAction[] }[] = [];
  for (const action of actions) {
    const last = groups[groups.length - 1];
    if (last && last.name === action.group) last.items.push(action);
    else groups.push({ name: action.group, items: [action] });
  }

  function roam(event: ReactKeyboardEvent<HTMLDivElement>) {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    event.preventDefault();
    const items = [...(panelRef.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]') ?? [])];
    if (items.length === 0) return;
    const at = items.indexOf(document.activeElement as HTMLButtonElement);
    const step = event.key === 'ArrowDown' ? 1 : -1;
    items[(at + step + items.length) % items.length].focus();
  }

  return (
    <>
      <button
        ref={triggerRef}
        className="btn btn-sm btn-quiet aspect-square px-0"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        aria-label={`${t('common.more')} ${label}`}
        onClick={() => setOpen((value) => !value)}
      >
        <MoreHorizontal size={16} aria-hidden="true" />
      </button>
      {open &&
        createPortal(
          <div
            ref={panelRef}
            id={id}
            style={position}
            className="fixed z-50 rounded-panel border border-line bg-surface text-left shadow-overlay [animation:fade-in_140ms_var(--ease-out-quint)]"
          >
            <div role="menu" aria-label={label} className="p-1.5" onKeyDown={roam}>
              {groups.map((group, index) => (
                <div
                  key={group.name ?? index}
                  role="group"
                  aria-label={group.name}
                  className={index > 0 ? 'mt-1.5 border-t border-line pt-1.5' : undefined}
                >
                  {group.name && <p className="stamp px-2 pb-1.5 pt-1 text-text-3">{group.name}</p>}
                  {group.items.map((action) => (
                    <button
                      key={action.key}
                      role="menuitem"
                      aria-haspopup={action.onSelect ? undefined : 'dialog'}
                      className={`flex w-full items-center rounded-control px-2 py-2 text-left text-sm transition-colors hover:bg-sunk ${
                        action.destructive ? 'text-bad hover:bg-bad-wash' : 'text-text-2 hover:text-text'
                      }`}
                      onClick={() => {
                        setOpen(false);
                        if (action.onSelect) action.onSelect();
                        else setAsking(action);
                      }}
                    >
                      {action.label}
                    </button>
                  ))}
                </div>
              ))}
            </div>
          </div>,
          document.body,
        )}
      {asking && (
        <ConfirmDialog
          title={asking.label}
          description={asking.consequence!}
          confirm={asking.confirm ?? t('common.confirm')}
          pending={asking.pending ?? t('common.working')}
          destructive={!!asking.destructive}
          busy={busy}
          // The menu entry that raised this is already gone; the row's own control is what remains.
          returnFocus={() => triggerRef.current}
          onCancel={() => setAsking(null)}
          onConfirm={async () => {
            setBusy(true);
            try {
              await asking.onConfirm!();
            } finally {
              setBusy(false);
              setAsking(null);
            }
          }}
        />
      )}
    </>
  );
}
