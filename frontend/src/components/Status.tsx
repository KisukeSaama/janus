import type { ReactNode } from 'react';

import type { PingReason, ProviderPing } from '../api';
import { useI18n, type MessageKey } from '../i18n';
import { daysRemaining, stageOf, STAGE_TONE } from '../lib/expiry';

export function EnabledState({ enabled }: { enabled: boolean }) {
  const { t } = useI18n();
  return (
    <span className="inline-flex items-center gap-2 whitespace-nowrap text-xs">
      <span aria-hidden="true" className={`h-1.5 w-1.5 ${enabled ? 'bg-ok' : 'border border-text-3'}`} />
      {enabled ? t('state.active') : t('state.disabled')}
    </span>
  );
}

/**
 * Whether calls are actually being forwarded, which is not the same question as whether the grant
 * reads active. A connection switched off on purpose and one switched off by accident are different
 * states and are named differently: `Paused` is a decision, `Not forwarding` is a fault.
 */
export function LiveState({ live, paused }: { live: boolean; paused: boolean }) {
  const { t } = useI18n();
  const [mark, tone, label] = live
    ? ['bg-ok', 'text-text-2', t('state.live')]
    : paused
      ? ['border border-text-3', 'text-text-2', t('state.paused')]
      : ['bg-warn', 'text-warn', t('state.stalled')];
  return (
    <span className={`inline-flex items-center gap-2 whitespace-nowrap text-xs ${tone}`}>
      <span aria-hidden="true" className={`h-1.5 w-1.5 shrink-0 ${mark}`} />
      {label}
    </span>
  );
}

/** Each failure names a different thing to go and look at, so each keeps its own sentence. */
const PING_REASON: Record<PingReason, MessageKey> = {
  ANSWERED: 'providers.pingAnswered',
  TIMED_OUT: 'providers.pingTimedOut',
  UNRESOLVED: 'providers.pingUnresolved',
  TLS_FAILED: 'providers.pingTlsFailed',
  BLOCKED: 'providers.pingBlocked',
  UNREACHABLE: 'providers.pingUnreachable',
};

/**
 * What a destination answered when it was last asked, if it was.
 *
 * A refusal counts as reached: the probe presents no credential, so 401 and 404 both mean somebody
 * is listening, and that is the question. Only a server error is drawn as a fault while still
 * counting as alive — the address is fine and the API is not, which is somebody else's incident.
 */
export function PingState({
  result,
  pending,
  /** The probe itself did not get through — an ended session, a refused role, Janus unreachable. */
  failed,
}: {
  result?: ProviderPing;
  pending?: boolean;
  failed?: boolean;
}) {
  const { t } = useI18n();
  if (pending) return <span className="text-xs text-text-3">{t('providers.pinging')}</span>;
  if (failed) return <span className="text-xs text-bad">{t('providers.pingRefused')}</span>;
  if (!result) return <span className="text-xs text-text-3">{t('providers.pingNone')}</span>;

  const failing = result.reachable && result.status >= 500;
  const [mark, tone] = result.reachable
    ? failing
      ? ['bg-warn', 'text-warn']
      : ['bg-ok', 'text-text-2']
    : ['bg-bad', 'text-bad'];

  return (
    <span className={`inline-flex flex-wrap items-center gap-x-2 gap-y-1 text-xs ${tone}`}>
      <span aria-hidden="true" className={`h-1.5 w-1.5 shrink-0 ${mark}`} />
      <span>{t(PING_REASON[result.reason])}</span>
      {result.reachable && <span className="data">{result.status}</span>}
      <span className="data text-text-3">{t('providers.pingMillis', { millis: result.millis })}</span>
    </span>
  );
}

const EXPIRY_MARK: Record<'bad' | 'warn' | 'info', string> = {
  bad: 'bg-bad',
  warn: 'bg-warn',
  info: 'border border-text-3',
};

const EXPIRY_TEXT: Record<'bad' | 'warn' | 'info', string> = {
  bad: 'text-bad',
  warn: 'text-warn',
  info: 'text-text-2',
};

/**
 * A recorded deadline, and how far off it is.
 *
 * The date alone is a fact nobody converts in their head, and "in 5 days" alone is a claim you
 * cannot check. Both are shown, and the tone only appears once the date is near enough to be work:
 * a key due in nine months is drawn as quietly as one with no date at all.
 */
export function ExpiryState({ expiresAt }: { expiresAt?: string }) {
  const { t, tc, formatDate } = useI18n();
  if (!expiresAt) return <span className="text-xs text-text-3">{t('expiry.none')}</span>;

  const stage = stageOf(expiresAt);
  const tone = stage ? STAGE_TONE[stage] : null;
  const days = daysRemaining(expiresAt);
  const distance = days === 0 ? t('expiry.today') : days < 0 ? tc('expiry.agoDays', -days) : tc('expiry.inDays', days);

  return (
    <span className="inline-flex flex-wrap items-center gap-x-2 gap-y-1 text-xs">
      {tone && <span aria-hidden="true" className={`h-1.5 w-1.5 shrink-0 ${EXPIRY_MARK[tone]}`} />}
      {stage === 'EXPIRED' && <span className="stamp text-bad">{t('expiry.expired')}</span>}
      <span className="data whitespace-nowrap">{formatDate(expiresAt)}</span>
      <span className={`whitespace-nowrap ${tone ? EXPIRY_TEXT[tone] : 'text-text-3'}`}>{distance}</span>
    </span>
  );
}

// Throttled reads as a warning, not a failure: the deployment did exactly what it was told to.
const OUTCOME_TONE: Record<string, string> = { SUCCESS: 'text-ok', DENIED: 'text-warn', THROTTLED: 'text-warn' };

export function Outcome({ value }: { value: string }) {
  const { tEnum } = useI18n();
  return <span className={`stamp ${OUTCOME_TONE[value] ?? 'text-bad'}`}>{tEnum('outcome', value)}</span>;
}

/** Machine data, set in the monospace: tabular figures, slashed zero, no tracking of its own. */
export function Data({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <span className={`data ${className}`}>{children}</span>;
}
