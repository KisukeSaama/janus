import { useEffect, useRef, useState, type ReactNode } from 'react';
import { AlertTriangle, Laptop } from 'lucide-react';

import {
  ApiError,
  useDecideMcpAuthorization,
  useMcpAuthorization,
  useSignOut,
  type Identity,
  type McpAuthorization,
} from '../api';
import { Wordmark } from '../components';
import { useI18n, type MessageKey } from '../i18n';
import { useErrorMessage } from '../lib/errors';

import { leaveFor, useLocation } from './routes';
import { SettingsMenu } from './SettingsMenu';

/**
 * An AI assistant asking to act as the person signed in.
 *
 * An MCP client sends the browser to Janus, and Janus sends it here with the id of the request it is
 * holding. What is decided is not a setting but a delegation: whatever the assistant does afterwards,
 * the journal will write down under this person's name. So the screen says three things before it
 * offers a button — who is asking, where the answer goes, and what that answer lets them do — and it
 * says them in that order because it is the order in which they can be trusted least.
 *
 * The name is the client's own claim about itself; anybody can register as "Claude". The address the
 * code is returned to is the one fact Janus can vouch for, so it is the largest thing on the screen,
 * and when it is not this computer the screen says so in the warning tone rather than in a footnote.
 *
 * It stands alone, like the sign-in screen, rather than inside the console: a rail offering seven
 * other places to go has no business beside a decision somebody arrived at from another program.
 */

/** What an unusable request is called by the authorization endpoint, and the sentence each one earns. */
const REFUSAL: Record<string, MessageKey> = {
  invalid_client: 'consent.error.invalid_client',
  invalid_redirect_uri: 'consent.error.invalid_redirect_uri',
  invalid_request: 'consent.error.invalid_request',
};

/**
 * Schemes a browser would run or read rather than leave for. The server writes the address and has
 * already checked it against the client's registration; this is the last line, not the first, and it
 * leaves custom schemes alone because a native client may legitimately be reached through one.
 */
const UNSAFE_SCHEME = /^\s*(javascript|data|vbscript|blob|file):/i;

export function ConsentPage({ identity }: { identity: Identity }) {
  const params = new URLSearchParams(window.location.search);
  const request = params.get('request') ?? '';
  const refused = params.get('error') ?? (request ? null : 'invalid_request');

  return (
    <Frame identity={identity}>
      {refused ? <Unusable reason={refused} /> : <Request id={request} identity={identity} />}
    </Frame>
  );
}

/** The sign-in screen's frame, at the width a decision needs rather than a form. */
function Frame({ identity, children }: { identity: Identity; children: ReactNode }) {
  const { t } = useI18n();
  const signOut = useSignOut();

  return (
    <main className="grid min-h-svh place-items-center px-5 pb-[8vh] pt-8">
      <div className="w-full max-w-[34rem]">
        <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
          <Wordmark />
          <div className="flex items-center gap-2">
            <span className="stamp text-text-3">{t('consent.tagline')}</span>
            <SettingsMenu />
          </div>
        </div>

        <div className="panel px-5 py-5 md:px-6 md:py-6">{children}</div>

        {/*
         * Who is about to be delegated, and the way to become somebody else first. Signing out does
         * not move the address, so the same request is asked again of whoever signs in next.
         */}
        <p className="mt-5 flex flex-wrap items-center justify-center gap-x-2 gap-y-1 text-center text-xs text-text-3">
          <span>{t('consent.signedInAs', { name: identity.displayName })}</span>
          <span aria-hidden="true">·</span>
          <button
            type="button"
            className="rounded-[3px] underline underline-offset-2 transition-colors hover:text-text"
            disabled={signOut.isPending}
            onClick={() => void signOut.mutateAsync().catch(() => undefined)}
          >
            {t('consent.switch')}
          </button>
        </p>
      </div>
    </main>
  );
}

/** A heading the screen reader lands on, since nothing on this page is a field to focus instead. */
function Title({ children }: { children: ReactNode }) {
  const ref = useRef<HTMLHeadingElement>(null);
  useEffect(() => ref.current?.focus(), []);
  return (
    <h1 ref={ref} tabIndex={-1} className="text-lg font-semibold tracking-title outline-none">
      {children}
    </h1>
  );
}

/** A dead end, said plainly, with the one way out that leads somewhere. */
function DeadEnd({ title, body }: { title: string; body: string }) {
  const { t } = useI18n();
  const [, navigate] = useLocation();
  return (
    <>
      <Title>{title}</Title>
      <p className="mt-2 text-sm text-text-2">{body}</p>
      <p className="mt-2 text-sm text-text-2">{t('consent.startAgain')}</p>
      <button
        type="button"
        className="btn btn-secondary mt-6 w-full"
        onClick={() => navigate({ page: 'dashboard' }, { replace: true })}
      >
        {t('consent.openConsole')}
      </button>
    </>
  );
}

function Unusable({ reason }: { reason: string }) {
  const { t } = useI18n();
  return <DeadEnd title={t('consent.unusableTitle')} body={t(REFUSAL[reason] ?? REFUSAL.invalid_request)} />;
}

function Request({ id, identity }: { id: string; identity: Identity }) {
  const { t } = useI18n();
  const describe = useErrorMessage();
  const authorization = useMcpAuthorization(id);

  if (authorization.isPending) {
    return (
      <div aria-busy="true" aria-label={t('consent.loading')}>
        <div className="skeleton h-6 w-3/4" />
        <div className="skeleton mt-4 h-16" />
        <div className="skeleton mt-4 h-24" />
      </div>
    );
  }

  if (authorization.isError) {
    // Unknown, expired and already answered are one answer from the server, and one to the reader:
    // whichever it was, this request can no longer be decided and a new one has to be started.
    if (authorization.error instanceof ApiError && authorization.error.status === 404) {
      return <DeadEnd title={t('consent.expiredTitle')} body={t('consent.expiredBody')} />;
    }
    return (
      <>
        <Title>{t('consent.failedTitle')}</Title>
        <p role="alert" className="mt-4 rounded-panel border border-bad/40 bg-bad-wash px-3 py-2.5 text-sm">
          {describe(authorization.error)}
        </p>
        <button type="button" className="btn btn-secondary mt-6 w-full" onClick={() => void authorization.refetch()}>
          {t('consent.retry')}
        </button>
      </>
    );
  }

  return <Decision id={id} identity={identity} request={authorization.data} />;
}

function Decision({ id, identity, request }: { id: string; identity: Identity; request: McpAuthorization }) {
  const { t, formatTime } = useI18n();
  const describe = useErrorMessage();
  const decide = useDecideMcpAuthorization();
  const [error, setError] = useState('');
  /**
   * Which answer is on its way. Held after it is accepted as well as while it is sent: from then on
   * the page is leaving, and a second answer to a request already decided would only be a 404.
   */
  const [answering, setAnswering] = useState<'approve' | 'deny' | null>(null);
  const [leaving, setLeaving] = useState(false);

  const administrator = identity.role !== 'USER';
  const busy = answering !== null;

  async function answer(approve: boolean) {
    setError('');
    setAnswering(approve ? 'approve' : 'deny');
    try {
      const { redirectUrl } = await decide.mutateAsync({ request: id, approve });
      if (UNSAFE_SCHEME.test(redirectUrl)) {
        setError(t('consent.badRedirect'));
        return;
      }
      setLeaving(true);
      leaveFor(redirectUrl);
    } catch (x) {
      setError(describe(x));
      setAnswering(null);
    }
  }

  const label = (which: 'approve' | 'deny') =>
    answering !== which ? t(`consent.${which}`) : leaving ? t('consent.returning') : t('common.working');

  return (
    <>
      <p className="stamp mb-2 text-accent-text">{t('consent.stamp')}</p>
      <Title>{t('consent.title')}</Title>
      <p className="mt-1.5 text-sm text-text-2">{t('consent.intro')}</p>

      <dl className="mt-6 space-y-4">
        {/* The claim: set as a quotation, and labelled as one. */}
        <div>
          <dt className="stamp mb-1.5 text-text-2">{t('consent.clientLabel')}</dt>
          <dd>
            <p className="break-words text-base font-medium">“{request.clientName}”</p>
            <p className="mt-1 text-xs text-text-2">{t('consent.clientHint')}</p>
          </dd>
        </div>

        {/* The fact: where the code goes. The host is what a person can recognise; the rest is proof. */}
        <div>
          <dt className="stamp mb-1.5 text-text-2">{t('consent.redirectLabel')}</dt>
          <dd>
            <p className="data break-all text-lg font-semibold">{request.redirectHost}</p>
            <p className="data mt-1 break-all text-xs text-text-2">{request.redirectUri}</p>
          </dd>
        </div>
      </dl>

      {request.loopback ? (
        <p className="mt-4 flex items-start gap-2.5 rounded-panel border border-line bg-sunk px-3.5 py-3 text-sm">
          <Laptop size={16} strokeWidth={2} className="mt-0.5 shrink-0 text-text-2" aria-hidden="true" />
          <span>{t('consent.loopback')}</span>
        </p>
      ) : (
        <div
          role="note"
          className="mt-4 flex items-start gap-2.5 rounded-panel border border-warn/45 bg-warn-wash px-3.5 py-3 text-sm"
        >
          <AlertTriangle size={16} strokeWidth={2} className="mt-0.5 shrink-0 text-warn" aria-hidden="true" />
          <span>
            <strong className="font-semibold">{t('consent.remoteTitle')}</strong>{' '}
            {t('consent.remote', { host: request.redirectHost })}
          </span>
        </div>
      )}

      <section className="mt-6 border-t border-line pt-5" aria-labelledby="consent-can">
        <h2 id="consent-can" className="text-base font-semibold">
          {t('consent.canTitle')}
        </h2>
        <p className="mt-1 text-sm text-text-2">
          {t('consent.canLead', {
            name: identity.displayName,
            role: t(`roles.${identity.role}` as MessageKey),
          })}
        </p>
        <ul className="mt-3 space-y-2">
          <Item>{administrator ? t('consent.can.apisAdmin') : t('consent.can.apisUser')}</Item>
          <Item>{t('consent.can.services')}</Item>
          <Item>{t('consent.can.credentials')}</Item>
          <Item>{t('consent.can.file')}</Item>
        </ul>
      </section>

      <section className="mt-5" aria-labelledby="consent-never">
        <h2 id="consent-never" className="text-base font-semibold">
          {t('consent.neverTitle')}
        </h2>
        <ul className="mt-3 space-y-2">
          <Item>{t('consent.never.secrets')}</Item>
          <Item>{t('consent.never.keys')}</Item>
        </ul>
      </section>

      <p className="mt-5 text-xs text-text-2">
        {t('consent.revoke')} {t('consent.expires', { time: formatTime(request.expiresAt) })}
      </p>

      {error && (
        <p role="alert" className="mt-5 rounded-panel border border-bad/40 bg-bad-wash px-3 py-2.5 text-sm">
          {error}
        </p>
      )}

      {/*
       * Neither button takes the focus on arrival: a delegation should not be one stray Enter away.
       * Approve is last in reading order and on the right, where a primary action sits everywhere
       * else in the console; on a phone it is on top, nearest the thumb, with the refusal under it.
       */}
      <div className="mt-6 flex flex-col-reverse gap-2 border-t border-line pt-5 sm:flex-row sm:justify-end">
        <button type="button" className="btn btn-secondary" disabled={busy} onClick={() => void answer(false)}>
          {label('deny')}
        </button>
        <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void answer(true)}>
          {label('approve')}
        </button>
      </div>
      <p className="sr-only" aria-live="polite">
        {leaving ? t('consent.returning') : ''}
      </p>
    </>
  );
}

function Item({ children }: { children: string }) {
  return (
    <li className="flex gap-2.5 text-sm text-text-2">
      <span aria-hidden="true" className="mt-[0.4375rem] h-1.5 w-1.5 shrink-0 bg-line-strong" />
      <span>{children}</span>
    </li>
  );
}
