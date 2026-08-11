import { useEffect, useRef } from 'react';
import {
  Activity as ActivityIcon,
  BookOpen,
  Bot,
  Boxes,
  KeyRound,
  LogOut,
  UsersRound,
  Workflow,
  X,
  type LucideIcon,
} from 'lucide-react';

import type { Identity } from '../api';
import { Wordmark } from '../components';
import { useFocusTrap } from '../hooks/useFocusTrap';
import { useI18n, type MessageKey } from '../i18n';

import type { Page } from './routes';

/**
 * One rail, every destination.
 *
 * What an operator does (connections, activity) above what Janus stores, and the guide the developer
 * of a calling service reads under both. Section tabs could only show the first tier and hid the
 * collections one click deep, behind a control that existed on one page only. Here the second tier
 * is a nav group, so every page starts at the same offset and the current position is readable
 * without opening anything.
 *
 * The registry lists two collections, not four: a destination and the rule admitting a caller to it
 * are only ever read together, so both are edited on the connection itself.
 */

type Item = { id: Page; label: MessageKey; icon: LucideIcon };

const GROUPS: { label: MessageKey; items: Item[] }[] = [
  {
    label: 'nav.console',
    items: [
      { id: 'connections', label: 'nav.connections', icon: Workflow },
      { id: 'activity', label: 'nav.activity', icon: ActivityIcon },
    ],
  },
  {
    label: 'nav.registry',
    items: [
      { id: 'credentials', label: 'credentials.title', icon: KeyRound },
      { id: 'applications', label: 'applications.title', icon: Boxes },
    ],
  },
  {
    // What a connection is for, rather than what it is made of: the guide is read by whoever writes
    // the calling service, and it is the one destination that holds no records at all.
    label: 'nav.reference',
    items: [
      { id: 'documentation', label: 'nav.documentation', icon: BookOpen },
      // The same integration, for the reader who will not read it: a file for the coding agent.
      { id: 'agents', label: 'nav.agents', icon: Bot },
    ],
  },
];

/**
 * Managing accounts is the one thing a role changes, so it is the one destination that appears for
 * some people and not others. It is a group of its own rather than an entry in the registry: an
 * account is not a record the gateway reads, and administering people is not administering APIs.
 */
const ADMINISTRATION: { label: MessageKey; items: Item[] } = {
  label: 'nav.administration',
  items: [{ id: 'accounts', label: 'accounts.title', icon: UsersRound }],
};

export type NavProps = {
  page: Page;
  /** How many records each destination currently holds. */
  counts: Partial<Record<Page, number>>;
  identity: Identity;
  onNavigate: (page: Page) => void;
  onSignOut: () => void;
};

function Nav({ page, counts, identity, onNavigate, onSignOut, onClose }: NavProps & { onClose?: () => void }) {
  const { t, formatNumber } = useI18n();
  const groups = identity.role === 'USER' ? GROUPS : [...GROUPS, ADMINISTRATION];
  /** A display name that repeats the login is a placeholder, not a person: the bootstrap account. */
  const named = identity.displayName !== identity.username;

  return (
    <div className="flex h-full flex-col">
      {/*
       * Sized to land the wordmark on the same baseline as the controls in the bar beside it, and
       * closed by the same 2px rule at the same height, so the line under the head of the console
       * crosses the rail and the bar as one line rather than two that nearly meet.
       */}
      <div className="shrink-0">
        <div className="flex items-center gap-2.5 px-4 py-3">
          <span className="flex min-h-[38px] flex-1 items-center gap-2.5 pointer-coarse:min-h-11">
            <Wordmark />
          </span>
          {onClose && (
            <button className="btn btn-sm btn-quiet -mr-2 aspect-square px-0" aria-label={t('nav.close')} onClick={onClose}>
              <X size={17} />
            </button>
          )}
        </div>
        <div className="h-0.5 bg-line" />
      </div>

      <nav aria-label={t('nav.label')} className="min-h-0 flex-1 overflow-y-auto px-3 py-4">
        {groups.map((group) => (
          <div key={group.label} className="mt-5 first:mt-0">
            <p className="stamp mb-1.5 px-2.5 text-text-3">{t(group.label)}</p>
            <ul className="space-y-0.5">
              {group.items.map(({ id, label, icon: Icon }) => {
                const count = counts[id];
                return (
                  <li key={id}>
                    {/*
                     * The weight stays put across states, like every other selected thing in this
                     * console: the fill and the accent icon mark the current page without resizing
                     * the row and shoving the count sideways.
                     */}
                    <button className="nav-item" aria-current={id === page ? 'page' : undefined} onClick={() => onNavigate(id)}>
                      <Icon size={16} strokeWidth={2} className="nav-icon shrink-0" />
                      <span className="truncate">{t(label)}</span>
                      {count !== undefined && <span className="num ml-auto pl-2 text-2xs text-text-3">{formatNumber(count)}</span>}
                    </button>
                  </li>
                );
              })}
            </ul>
          </div>
        ))}
      </nav>

      <div className="shrink-0 border-t border-line p-3">
        {/*
         * Who you are signed in as, beside the way out. The journal now names a person, so the
         * console has to say which person it is writing down before somebody acts.
         *
         * Two lines whoever is signed in, and no token twice. An account carrying a person's name
         * states it over the login; the bootstrap account, whose display name is its login, states
         * the login once and gives the second line to the role. The login is machine data and set
         * as such, the role is a word: the monospace used to run across both and made "Super
         * administrateur" look like a value the gateway matches literally.
         */}
        <div className="px-2.5 pb-2">
          <p className={`truncate text-sm font-medium ${named ? '' : 'data'}`}>
            {named ? identity.displayName : identity.username}
          </p>
          <p className="truncate text-2xs text-text-3">
            {named && (
              <>
                <span className="data">{identity.username}</span> ·{' '}
              </>
            )}
            {t(`roles.${identity.role}` as MessageKey)}
          </p>
        </div>
        <button className="nav-item" onClick={onSignOut}>
          <LogOut size={16} strokeWidth={2} className="nav-icon shrink-0" />
          {t('common.signOut')}
        </button>
      </div>
    </div>
  );
}

/** The rail itself, from the environment strip to the bottom of the window. */
export function Sidebar(props: NavProps) {
  return (
    <div className="hidden border-r border-line bg-surface lg:bottom-0 lg:left-0 lg:top-0 lg:z-40 lg:flex lg:w-64 lg:flex-col lg:fixed">
      <Nav {...props} />
    </div>
  );
}

/** Below 60rem the rail is a drawer: the width belongs to the data, not to a permanent menu. */
export function NavDrawer({ onClose, ...props }: NavProps & { onClose: () => void }) {
  const { t } = useI18n();
  const ref = useRef<HTMLDivElement>(null);
  useFocusTrap(ref);

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    document.body.style.overflow = 'hidden';
    ref.current?.querySelector<HTMLElement>('button')?.focus();
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = '';
      opener?.focus?.();
    };
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-50 bg-[var(--c-scrim)] [animation:fade-in_160ms_var(--ease-out-quint)] lg:hidden"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-label={t('nav.label')}
        className="h-full w-[17rem] max-w-[85vw] border-r border-line bg-surface shadow-overlay [animation:slide-in-rail_220ms_var(--ease-out-quint)]"
      >
        <Nav {...props} onClose={onClose} />
      </div>
    </div>
  );
}
