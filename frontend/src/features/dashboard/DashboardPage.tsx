import { useMemo, useRef, useState } from 'react';
import { AlertTriangle, ChevronRight, Info, Plus, ShieldCheck } from 'lucide-react';

import {
  useApplications,
  useAudit,
  useCredentials,
  useGrants,
  useProviders,
  type Audit,
  type Credential,
} from '../../api';
import { Blank, Empty, ExpiryState, LiveState, Outcome, PageHead, SectionHead, SkeletonRows } from '../../components';
import { useI18n } from '../../i18n';
import { assess, KEY_MAX_AGE_DAYS, type Attention, type AttentionTarget } from '../../lib/attention';
import { buildConnections, sortConnections, type Connection } from '../../lib/connections';
import { NOTICE_DAYS, upcoming } from '../../lib/expiry';
import { AUDIT_PAGE_SIZE } from '../activity/AuditLog';

/**
 * The console's home reads top to bottom as a day does: what is wrong, what is about to be, what
 * exists, what just happened.
 *
 * The work comes before the inventory. `Needs attention` is computed from data already on screen and
 * each finding can filter the list down to the rows it is about, so "one connection forwards
 * nothing" is one click away from that connection rather than from a full table.
 *
 * `Coming due` is the standing state of the recorded deadlines, which is not what the notification
 * menu holds: an announcement is raised once per stage and is answered by being read, a deadline
 * stays true until somebody stores a new date. Reading the feed cannot make a key expire later, so
 * the two are drawn in two places and neither clears the other.
 */

/** Past six, a section stops being a summary. The rest are one line, then the whole list is a click. */
const DUE_SHOWN = 6;

/** Enough of the log to see whether anything has happened, without becoming the log. */
const RECENT_SHOWN = 6;

export function DashboardPage({
  onOpen,
  onConnect,
  onNavigate,
}: {
  onOpen: (id: string) => void;
  onConnect: () => void;
  /** Findings and sections that are not about connections send the reader to the page that is. */
  onNavigate: (to: Exclude<AttentionTarget, 'connections'> | 'activity') => void;
}) {
  const { t, tc, formatNumber } = useI18n();
  const [focus, setFocus] = useState<string[] | null>(null);
  const listRef = useRef<HTMLElement>(null);

  const grants = useGrants();
  const applications = useApplications();
  const providers = useProviders();
  const credentials = useCredentials();
  // The same page of the log the activity view reads, so moving between them costs no request.
  const events = useAudit(0, AUDIT_PAGE_SIZE);

  const loading = grants.isPending || applications.isPending || providers.isPending || credentials.isPending;

  // Keyed on the query results themselves, which keep their identity between renders. Defaulting to
  // `[]` inside rather than above matters: a fresh literal in the dependency list would rebuild every
  // connection on every keystroke elsewhere on the page.
  const connections = useMemo(
    () =>
      sortConnections(
        buildConnections(
          grants.data ?? [],
          applications.data ?? [],
          providers.data ?? [],
          credentials.data ?? [],
        ),
      ),
    [grants.data, applications.data, providers.data, credentials.data],
  );

  const apps = applications.data ?? [];
  const secrets = credentials.data ?? [];

  const findings = loading ? [] : assess({ apps, credentials: secrets, connections });
  const due = loading ? [] : upcoming(secrets);
  const live = connections.filter((c) => c.live).length;
  const visible = focus ? connections.filter((c) => focus.includes(c.id)) : connections;
  const audits = events.data?.content ?? [];

  /**
   * A finding about connections narrows the list below rather than sending the reader anywhere, and
   * that list is usually under the fold: applied in silence, `Review` reads as a button that did
   * nothing. Everything else is a finding about another page, and goes there.
   */
  function review(item: Attention) {
    if (item.target !== 'connections') {
      onNavigate(item.target);
      return;
    }
    setFocus(item.ids);
    listRef.current?.scrollIntoView({
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
      block: 'start',
    });
  }

  // A connection starts from the service that needs one, so this opens the service flow and says so.
  // Registering an API on its own is a catalogue chore, and lives on the page that holds the
  // catalogue.
  const connectButton = (
    <button className="btn btn-primary w-full sm:w-auto" onClick={onConnect}>
      <Plus size={15} strokeWidth={2.25} />
      {t('applications.new')}
    </button>
  );

  // A first run with four empty tables teaches nothing. One paragraph and one button do.
  const firstRun = !loading && apps.length === 0 && (providers.data ?? []).length === 0 && connections.length === 0;
  if (firstRun) {
    return (
      <div className="mx-auto max-w-[44rem] py-10 text-center md:py-16">
        <h1 className="text-2xl font-semibold tracking-title">{t('connections.emptyTitle')}</h1>
        <p className="mx-auto mt-3.5 max-w-[58ch] text-text-2">{t('connections.emptyBody')}</p>
        <div className="mt-8 flex justify-center">{connectButton}</div>
      </div>
    );
  }

  return (
    <>
      <PageHead
        section={t('nav.console')}
        title={t('dashboard.title')}
        intro={t('dashboard.lead')}
        action={connectButton}
      />

      {/* Held open at its own height while the records load, so the sections below do not step down
          the page the moment the findings arrive. */}
      {loading ? (
        <div className="panel mb-10 flex items-start gap-3 px-4 py-3.5" aria-hidden="true">
          <div className="skeleton mt-1 h-4 w-4 shrink-0" />
          <div className="min-w-0 flex-1 space-y-1">
            <div className="skeleton h-[1.375rem] w-52" />
            <div className="skeleton h-5 w-full max-w-[36rem]" />
          </div>
        </div>
      ) : (
        <NeedsAttention items={findings} onReview={review} />
      )}

      <section className="mb-10">
        <SectionHead
          aside={
            <button
              className="text-xs text-text-2 underline underline-offset-2"
              onClick={() => onNavigate('credentials')}
            >
              {t('dashboard.dueAll')}
            </button>
          }
        >
          {t('dashboard.due')}
        </SectionHead>
        {loading ? (
          <SkeletonRows rows={2} cols={2} />
        ) : due.length === 0 ? (
          <Blank>{t('dashboard.dueNone', { days: NOTICE_DAYS })}</Blank>
        ) : (
          <ul className="panel divide-y divide-line">
            {due.slice(0, DUE_SHOWN).map((secret) => (
              <Deadline key={secret.id} credential={secret} />
            ))}
            {/* A section that quietly showed the first six would read as the whole list. */}
            {due.length > DUE_SHOWN && (
              <li className="px-4 py-2.5 text-xs text-text-3">{tc('dashboard.dueMore', due.length - DUE_SHOWN)}</li>
            )}
          </ul>
        )}
      </section>

      {/* `scroll-mt-24` clears the sticky bar: scrolled flush to the top, the head of the section
          would land underneath it and the reader would arrive at a list with no title. */}
      <section ref={listRef} className="mb-10 scroll-mt-24">
        <SectionHead
          aside={
            connections.length > 0 ? (
              <span className="num text-xs text-text-3">
                {t('dashboard.liveOf', { live: formatNumber(live), total: formatNumber(connections.length) })}
              </span>
            ) : undefined
          }
        >
          {t('connections.title')}
        </SectionHead>

        {focus && (
          <div className="mb-3 flex flex-wrap items-center justify-between gap-3 rounded-panel border border-accent/40 bg-accent-wash px-3.5 py-2.5 text-sm">
            <p>{t('connections.filtered')}</p>
            <button className="btn btn-sm btn-secondary" onClick={() => setFocus(null)}>
              {t('connections.showAll')}
            </button>
          </div>
        )}

        {loading ? (
          <SkeletonRows rows={4} cols={3} />
        ) : visible.length === 0 ? (
          <Empty headline={t('connections.noneTitle')} hint={t('connections.noneHint')} action={connectButton} />
        ) : (
          <ul className="panel divide-y divide-line">
            {visible.map((connection) => (
              <ConnectionRow key={connection.id} connection={connection} onOpen={() => onOpen(connection.id)} />
            ))}
          </ul>
        )}
      </section>

      <section>
        <SectionHead
          aside={
            <button className="text-xs text-text-2 underline underline-offset-2" onClick={() => onNavigate('activity')}>
              {t('dashboard.recentAll')}
            </button>
          }
        >
          {t('dashboard.recent')}
        </SectionHead>
        {events.isPending ? (
          /* The six it will draw, so the page does not step down when the log lands. */
          <SkeletonRows rows={RECENT_SHOWN} cols={2} />
        ) : audits.length === 0 ? (
          <Blank>{t('dashboard.noEvents')}</Blank>
        ) : (
          <ul className="panel divide-y divide-line">
            {audits.slice(0, RECENT_SHOWN).map((event) => (
              <RecentDecision key={event.id} event={event} />
            ))}
          </ul>
        )}
      </section>
    </>
  );
}

/* ── One statement per row ─────────────────────────────────────────────── */

function ConnectionRow({ connection, onOpen }: { connection: Connection; onOpen: () => void }) {
  const { t } = useI18n();
  const { grant, provider, credential } = connection;
  const orphan = !provider
    ? t('connections.orphanProvider')
    : !credential
      ? t('connections.orphanCredential')
      : null;

  return (
    <li>
      <button
        onClick={onOpen}
        className="grid w-full grid-cols-1 items-center gap-x-6 gap-y-3 px-4 py-3.5 text-left transition-colors hover:bg-sunk lg:grid-cols-[minmax(0,1.05fr)_minmax(0,1fr)_auto]"
      >
        <div className="min-w-0">
          {/* The whole row is the accessible name, so the arrow says what it means out loud. */}
          <p className="truncate text-sm">
            <span className="font-medium">{grant.applicationName}</span>
            <span className="sr-only"> {t('connections.reaches')} </span>
            <span aria-hidden="true" className="mx-2 text-text-3">
              &rarr;
            </span>
            <span className="font-medium">{grant.providerName}</span>
          </p>
          <p className="mt-1 truncate text-xs text-text-2">
            {orphan ? (
              <span className="text-warn">{orphan}</span>
            ) : (
              t('connections.presents', { name: grant.credentialName })
            )}
          </p>
        </div>

        {/* Where the caller sends its request. With no allowlist left, this is the whole address. */}
        <div className="min-w-0">
          <p className="data truncate text-xs text-text-2">{connection.gatewayPath || '—'}</p>
        </div>

        <div className="flex items-center justify-between gap-4 lg:justify-end">
          <LiveState live={connection.live} paused={connection.blockedBy === 'grant'} />
          <ChevronRight size={16} className="shrink-0 text-text-3" aria-hidden="true" />
        </div>
      </button>
    </li>
  );
}

/**
 * A secret and the day it stops working. The API it belongs to is on the line because two accounts
 * name their key the same thing far more often than they store two keys for the same API.
 */
function Deadline({ credential }: { credential: Credential }) {
  return (
    <li className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 px-4 py-3">
      <p className="min-w-0 truncate text-sm">
        {credential.name}
        <span className="ml-2 text-xs text-text-2">{credential.providerName}</span>
      </p>
      <ExpiryState expiresAt={credential.expiresAt} />
    </li>
  );
}

function RecentDecision({ event }: { event: Audit }) {
  const { tEnum, formatTime } = useI18n();
  return (
    <li className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 px-4 py-3">
      <p className="text-sm">{tEnum('action', event.action)}</p>
      <p className="flex items-baseline gap-3 text-xs text-text-2">
        <span className="data">{formatTime(event.occurredAt)}</span>
        <span>{tEnum('actor', event.actorType)}</span>
        {event.statusCode ? <span className="data">{event.statusCode}</span> : null}
        <Outcome value={event.outcome} />
      </p>
    </li>
  );
}

/* ── The work, before the inventory ────────────────────────────────────── */

function NeedsAttention({ items, onReview }: { items: Attention[]; onReview: (item: Attention) => void }) {
  const { t, tc } = useI18n();

  if (items.length === 0) {
    return (
      <div className="panel mb-10 flex items-start gap-3 px-4 py-3.5">
        <ShieldCheck size={17} strokeWidth={2.25} className="mt-0.5 shrink-0 text-ok" />
        <div>
          <p className="font-medium">{t('attention.clear')}</p>
          <p className="mt-1 max-w-[72ch] text-sm text-text-2">{t('attention.clearHint')}</p>
        </div>
      </div>
    );
  }

  return (
    <section className="mb-10">
      <SectionHead>{t('attention.title')}</SectionHead>
      <ul className="panel divide-y divide-line">
        {items.map((item) => {
          const Icon = item.severity === 'warn' ? AlertTriangle : Info;
          return (
            <li key={item.kind} className="flex flex-wrap items-start gap-x-4 gap-y-3 px-4 py-4">
              <Icon
                size={17}
                strokeWidth={2.25}
                className={`mt-0.5 shrink-0 ${item.severity === 'warn' ? 'text-warn' : 'text-text-3'}`}
              />
              <div className="min-w-0 flex-1">
                <p className="font-medium">
                  {tc(`attention.${item.kind}`, item.names.length, { days: KEY_MAX_AGE_DAYS })}
                </p>
                <p className="mt-1 max-w-[72ch] text-sm text-text-2">
                  {t(`attention.${item.kind}Hint` as 'attention.staleKeysHint')}
                </p>
                <p className="mt-1.5 truncate text-xs text-text-3">
                  {item.names.slice(0, 4).join(' · ')}
                  {item.names.length > 4 && ` · +${item.names.length - 4}`}
                </p>
              </div>
              <button className="btn btn-sm btn-secondary" onClick={() => onReview(item)}>
                {t('attention.go')}
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
