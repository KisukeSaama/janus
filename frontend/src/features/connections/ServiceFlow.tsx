import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, ArrowRight, Plus } from 'lucide-react';

import {
  del,
  keys,
  post,
  useCredentials,
  useProviders,
  useSession,
  type Credential,
  type Grant,
  type IssuedApplication,
  type Provider,
} from '../../api';
import { ConfirmDialog, CopyField, Field, SelectField, Sheet, TextAreaField } from '../../components';
import { useI18n } from '../../i18n';
import { curlFor, probeGateway, type ProbeResult } from '../../lib/connections';
import { useErrorMessage } from '../../lib/errors';
import { useApiActivation } from './activation';
import { ApiRow } from './ApiRow';
import { ConnectFlow } from './ConnectFlow';

/**
 * Registering a service, and saying what it may reach. This is where connections are made.
 *
 * The service comes first because it is what the reader has: they are writing something, and it
 * needs data. Which APIs it may call is the second question, answered against the catalogue this
 * deployment already holds — and when the one they want is not in it, the API flow opens over this
 * one, registers it, and hands the reader back to a list with the new entry in it. Nowhere in here
 * does anybody type an API's contract twice.
 *
 * It ends on the key. That value exists in this page and nowhere else — Janus keeps a hash — so the
 * flow stops on it, beside the address and a request actually sent through the gateway, rather than
 * dropping the reader on a table where nothing says whether any of it works.
 */

type Step = 1 | 2;

/** What the caller has to be told, once there is something to tell it. */
type Ready = {
  applicationId: string;
  apiKey: string;
  /** Everything this key opens. Empty is allowed: a service may be registered before it is used. */
  apis: { slug: string; name: string }[];
};

/** One row of the second step: a registered API, and the credential that makes it grantable. */
type ApiOption = { provider: Provider; credential?: Credential };

export function ServiceFlow({
  onClose,
  onDone,
  onHome,
}: {
  onClose: () => void;
  onDone: () => void;
  /** The wordmark's destination, which is a different exit from this flow than the button beside it. */
  onHome: () => void;
}) {
  const { t } = useI18n();
  const describe = useErrorMessage();
  const client = useQueryClient();

  const providers = useProviders();
  const credentials = useCredentials();
  // Read here rather than passed down: this flow is opened from two places, one of which holds no
  // identity of its own, and the session is a query the console has already answered.
  const session = useSession();
  // Anybody may register a service and subscribe it to the catalogue. Writing the catalogue is an
  // administrator's, so a reader who would be refused is not offered the button.
  const mayRegisterApi = session.data?.role !== 'USER';

  const [step, setStep] = useState<Step>(1);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [origins, setOrigins] = useState('');
  const [picked, setPicked] = useState<string[]>([]);
  /** The API flow, opened over this one when the catalogue is missing what the reader needs. */
  const [registering, setRegistering] = useState(false);
  /** Supplying what a row is missing, so an API can be activated without leaving this step. */
  const activation = useApiActivation();
  const [ready, setReady] = useState<Ready | null>(null);
  const [busy, setBusy] = useState(false);
  const [leaving, setLeaving] = useState<(() => void) | null>(null);
  const [error, setError] = useState('');

  // A grant needs a credential to present, so an API nobody holds one for cannot be ticked. It is
  // still listed: leaving it out is what makes an API registered a minute ago look like it vanished.
  const apis = useMemo<ApiOption[]>(() => {
    const credentialByProvider = new Map<string, Credential>();
    for (const credential of [...activation.minted, ...(credentials.data ?? [])]) {
      if (!credentialByProvider.has(credential.providerId)) {
        credentialByProvider.set(credential.providerId, credential);
      }
    }
    return (providers.data ?? [])
      .map((provider) => ({ provider, credential: credentialByProvider.get(provider.id) }))
      .sort((a, b) => a.provider.name.localeCompare(b.provider.name));
  }, [activation.minted, credentials.data, providers.data]);

  // Two sheets, one body. The API flow gives the page its scroll back when it closes, and this one
  // is still open over it: the lock is taken again rather than counted, which would be a mechanism
  // built for the only place two of these ever stack.
  useEffect(() => {
    if (!registering) document.body.style.overflow = 'hidden';
  }, [registering]);

  const started = name !== '' || description !== '' || origins !== '' || picked.length > 0;

  /** Nothing typed, nothing to lose: an empty form leaves on the click rather than on a question. */
  const leave = (exit: () => void) => (started ? setLeaving(() => exit) : exit());

  const complete: Record<Step, boolean> = {
    1: name.trim() !== '',
    // Reaching nothing is a legitimate answer: a service registered today may be subscribed next
    // week, and refusing to issue its key until then helps nobody.
    2: true,
  };

  function toggle(providerId: string) {
    setPicked((current) =>
      current.includes(providerId) ? current.filter((id) => id !== providerId) : [...current, providerId],
    );
  }

  /** Every list this flow can have written to, told at once that it is out of date. */
  const refresh = () =>
    Promise.all(
      [keys.applications, keys.grants, ['audit']].map((key) => client.invalidateQueries({ queryKey: key })),
    );

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    // Enter in a field submits the form, so the step gate is enforced here too and not only on the
    // button that shows it.
    if (!complete[step]) return;
    if (step < 2) {
      setStep(2);
      return;
    }

    setBusy(true);
    setError('');
    let stage = t('service.stepService');
    let issued: IssuedApplication | undefined;

    try {
      issued = await post<IssuedApplication>('/applications', {
        name: name.trim(),
        description: description.trim() || null,
        enabled: true,
        // One per line is how a short list is edited without inventing a widget. Empty means any
        // origin, which is what most services want and what the field's hint says.
        allowedOrigins: origins
          .split('\n')
          .map((origin) => origin.trim())
          .filter(Boolean),
      });

      stage = t('connect.stepGrant');
      const granted = apis.filter((api) => api.credential && picked.includes(api.provider.id));
      for (const { provider, credential } of granted) {
        await post<Grant>('/grants', {
          applicationId: issued.application.id,
          providerId: provider.id,
          credentialId: credential!.id,
          enabled: true,
          // No ceiling of its own: the destination's own limit already applies, and a second one
          // invented here would be a number nobody chose.
          rateLimitPerMinute: 0,
          rateLimitBurst: 0,
        });
      }

      await refresh();
      setReady({
        applicationId: issued.application.id,
        apiKey: issued.apiKey,
        apis: granted.map(({ provider }) => ({ slug: provider.slug, name: provider.name })),
      });
      setBusy(false);
    } catch (x) {
      // A key issued for a service that reaches nothing it was promised is worse than no key at all,
      // so a refused grant takes the service with it. Deleting the application removes whichever
      // grants were already written for it.
      if (issued) await del(`/applications/${issued.application.id}`).catch(() => undefined);
      setError(t('connect.partial', { step: stage, reason: describe(x) }));
      setBusy(false);
    }
  }

  return (
    <Sheet
      label={t('applications.panelTitle')}
      // Once the records exist there is nothing left to lose, so the corner stops asking.
      onHome={() => (ready ? onHome() : leave(onHome))}
      head={
        ready ? undefined : (
          <div className="flex items-center gap-3">
            <p className="stamp text-text-2" aria-live="polite">
              {t('connect.stepOf', { step, total: 2 })}
            </p>
            <span aria-hidden="true" className="flex gap-1">
              {[1, 2].map((n) => (
                <span key={n} className={`h-1 w-6 rounded-[1px] ${n <= step ? 'bg-accent' : 'bg-line'}`} />
              ))}
            </span>
          </div>
        )
      }
      /* Leaving sits at the far end of the bar from the button that carries on. Once the records
         exist there is nothing left to leave, so only the closing button remains. */
      footer={
        ready ? (
          <button className="btn btn-primary ml-auto min-w-[12rem]" onClick={onDone}>
            {t('ready.done')}
          </button>
        ) : (
          <>
            <button
              type="button"
              className="btn btn-quiet"
              aria-haspopup={started ? 'dialog' : undefined}
              onClick={() => leave(onClose)}
            >
              {t('connect.abandon')}
            </button>
            <div className="ml-auto flex items-center gap-3">
              {step > 1 && (
                <button className="btn btn-secondary" type="button" onClick={() => setStep(1)}>
                  <ArrowLeft size={15} strokeWidth={2.25} />
                  {t('common.back')}
                </button>
              )}
              <button
                type="submit"
                form="service"
                className="btn btn-primary min-w-[12rem]"
                disabled={!complete[step] || busy}
              >
                {step < 2 ? (
                  <>
                    {t('common.next')}
                    <ArrowRight size={15} strokeWidth={2.25} />
                  </>
                ) : busy ? (
                  t('connect.creating')
                ) : (
                  t('applications.submit')
                )}
              </button>
            </div>
          </>
        )
      }
    >
      {leaving && (
        <ConfirmDialog
          title={t('connect.abandonTitle')}
          description={t('connect.abandonDescription')}
          confirm={t('connect.abandonConfirm')}
          pending={t('common.working')}
          destructive
          busy={false}
          onCancel={() => setLeaving(null)}
          onConfirm={leaving}
        />
      )}

      {ready && <ReadyScreen name={name.trim()} ready={ready} />}

      {/* Hidden rather than unmounted once everything exists: `display: none` is out of the tab order
          and out of the accessibility tree, and the questions it holds have all been answered. */}
      <form id="service" onSubmit={submit} className={`space-y-6 ${ready ? 'hidden' : ''}`}>
        {step === 1 && (
          <>
            <StepHead title={t('service.s1Title')} lead={t('service.s1Lead')} />
            <Field
              label={t('applications.fieldName')}
              required
              autoFocus
              autoComplete="off"
              placeholder="orders-api"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
            <Field
              label={t('applications.fieldDescription')}
              autoComplete="off"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
            <TextAreaField
              label={t('applications.fieldOrigins')}
              data
              autoComplete="off"
              spellCheck={false}
              placeholder="https://example.com"
              value={origins}
              onChange={(e) => setOrigins(e.target.value)}
              hint={t('applications.originsHint')}
            />
          </>
        )}

        {step === 2 && (
          <>
            <StepHead title={t('service.s2Title')} lead={t('service.s2Lead')} />

            {apis.length === 0 ? (
              <p className="rounded-panel border border-line bg-sunk px-3.5 py-3 text-sm text-text-2">
                {t('service.apisEmpty')}
              </p>
            ) : (
              <div className="divide-y divide-line rounded-panel border border-line bg-sunk px-4">
                {apis.map(({ provider, credential }) => (
                  <ApiRow
                    key={provider.id}
                    provider={provider}
                    credential={credential}
                    path={`/gateway/${provider.slug}/**`}
                    activation={activation}
                    // Activating an API in this list is only ever done to grant it, so the tick is
                    // not a second question. It stays a tick: the reader can still take it back.
                    onActivated={({ id }) => setPicked((current) => (current.includes(id) ? current : [...current, id]))}
                  >
                    <input
                      type="checkbox"
                      checked={picked.includes(provider.id)}
                      disabled={!credential}
                      onChange={() => toggle(provider.id)}
                      className="mt-0.5 h-4 w-4 shrink-0 accent-[var(--c-accent)]"
                    />
                  </ApiRow>
                ))}
              </div>
            )}

            {/* An open API is activated on the click, with no field to report into, so its refusal
                is reported under the list it came from. */}
            {activation.error && activation.activating === null && (
              <p role="alert" className="text-sm text-bad">
                {activation.error}
              </p>
            )}

            {/* The catalogue is not the limit of what can be reached: an API nobody registered yet is
                one click away, and the reader comes back to this list with it in place. */}
            {mayRegisterApi && (
              <button type="button" className="btn btn-secondary" onClick={() => setRegistering(true)}>
                <Plus size={15} strokeWidth={2.25} />
                {t('service.registerApi')}
              </button>
            )}

            {picked.length === 0 && apis.length > 0 && (
              <p className="text-sm text-text-2">{t('service.apisNone')}</p>
            )}
          </>
        )}

        {error && (
          <p role="alert" className="rounded-panel border border-bad/40 bg-bad-wash px-3.5 py-3 text-sm">
            {error}
          </p>
        )}
      </form>

      {registering && (
        <ConnectFlow
          onClose={() => setRegistering(false)}
          onHome={() => {
            setRegistering(false);
            leave(onHome);
          }}
          // Registered from here, so the reader lands back on the list they left, with the new entry
          // in it. Ticking it for them would be answering a question they came here to be asked.
          onDone={() => setRegistering(false)}
        />
      )}
    </Sheet>
  );
}

/* ── What the caller needs, and the proof that it works ────────────────── */

/**
 * The end of the flow: the values a service sends, the command that sends them, and one real
 * request through the gateway.
 *
 * The key is the reason this is a screen rather than a toast. It exists in this page and nowhere
 * else, so the flow stops here and says so, instead of returning to a table that would have to
 * explain what was lost.
 */
function ReadyScreen({ name, ready }: { name: string; ready: Ready }) {
  const { t } = useI18n();
  const title = useRef<HTMLHeadingElement>(null);
  const [tried, setTried] = useState(ready.apis[0]?.slug ?? '');

  // The button that submitted the form is gone with the form, and a focus left on nothing is a
  // keyboard reader back at the top of the document. It lands on the sentence that says what
  // happened instead.
  useEffect(() => title.current?.focus(), []);

  // Any path reaches the destination, so the example states the one nobody can get wrong.
  const curl = curlFor(tried, '/', ready.applicationId, ready.apiKey);

  return (
    <div className="space-y-6">
      <div>
        <p className="stamp text-accent-text">{t('ready.stamp')}</p>
        <h1
          ref={title}
          tabIndex={-1}
          className="mt-3 text-xl font-semibold tracking-title focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-accent-edge"
        >
          {t('ready.title', { name })}
        </h1>
        <p className="mt-2 max-w-[64ch] text-sm text-text-2">{t('ready.lead')}</p>
      </div>

      <CopyField label={t('ready.key')} value={ready.apiKey} />
      <CopyField label={t('ready.id')} value={ready.applicationId} />

      {ready.apis.length === 0 ? (
        <p className="rounded-panel border border-line bg-sunk px-3.5 py-3 text-sm text-text-2">
          {t('service.reachesNothing')}
        </p>
      ) : (
        <>
          {/* One connection needs no chooser; several do, and the same choice drives the example and
              the request below it. */}
          {ready.apis.length > 1 && (
            <SelectField
              label={t('probe.which')}
              value={tried}
              onChange={(e) => setTried(e.target.value)}
              options={ready.apis.map((api) => ({ value: api.slug, label: api.name }))}
            />
          )}
          <div>
            <CopyField label={t('ready.call')} value={curl} block />
            <p className="mt-1.5 text-xs text-text-2">{t('ready.callHint')}</p>
          </div>
          <Probe slug={tried} applicationId={ready.applicationId} apiKey={ready.apiKey} />
        </>
      )}
    </div>
  );
}

/**
 * One real request, sent the way the caller will send it.
 *
 * Every screen so far has only claimed that a service, a destination, a secret and a grant agree.
 * This is the one that finds out. What it reports is deliberately not "success" or "failure" but who
 * answered: Janus refusing, the API refusing, and the API answering are three different problems,
 * and telling them apart here saves the round of guessing a status code alone would start.
 */
function Probe({ slug, applicationId, apiKey }: { slug: string; applicationId: string; apiKey: string }) {
  const { t } = useI18n();
  const [path, setPath] = useState('/');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ProbeResult | null>(null);

  // Trying one API says nothing about the next, and a verdict left over from the previous choice
  // would be read as being about this one. Adjusted during the render that brings the new slug in,
  // rather than in an effect afterwards: an effect would paint the previous verdict once first.
  const [probed, setProbed] = useState(slug);
  if (probed !== slug) {
    setProbed(slug);
    setResult(null);
  }

  async function send(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setResult(await probeGateway(slug, path, applicationId, apiKey));
    setBusy(false);
  }

  return (
    <form onSubmit={send} className="space-y-4 rounded-panel border border-line bg-sunk p-4">
      <div>
        <h2 className="font-medium">{t('probe.title')}</h2>
        <p className="mt-1 text-sm text-text-2">{t('probe.lead')}</p>
      </div>
      <Field
        label={t('probe.path')}
        data
        autoComplete="off"
        spellCheck={false}
        value={path}
        onChange={(e) => setPath(e.target.value)}
      />
      <button type="submit" className="btn btn-secondary" disabled={busy}>
        {busy ? t('probe.sending') : result ? t('probe.again') : t('probe.send')}
      </button>
      {result && <Verdict result={result} />}
    </form>
  );
}

function Verdict({ result }: { result: ProbeResult }) {
  const { t } = useI18n();
  // A forwarded call the API itself refused is not a fault of this connection: everything Janus
  // decides went right, and saying "it works" over a 401 from the API would be a lie the reader
  // would spend an afternoon on.
  const upstreamRefused = result.verdict === 'forwarded' && result.status >= 400;
  const tone =
    result.verdict === 'forwarded' && !upstreamRefused
      ? 'border-ok/40 bg-ok-wash'
      : upstreamRefused
        ? 'border-warn/40 bg-warn-wash'
        : 'border-bad/40 bg-bad-wash';

  const headline =
    result.verdict === 'unreachable'
      ? t('probe.unreachable')
      : result.verdict === 'refused'
        ? t('probe.refused')
        : upstreamRefused
          ? t('probe.upstream')
          : t('probe.forwarded');

  const detail =
    result.verdict === 'unreachable'
      ? t('probe.unreachableHint')
      : result.verdict === 'refused'
        ? result.detail
        : upstreamRefused
          ? t('probe.upstreamHint', { status: result.status })
          : t('probe.forwardedHint', { status: result.status });

  return (
    <div role="status" className={`rounded-panel border px-3.5 py-3 ${tone}`}>
      <p className="text-sm font-medium">{headline}</p>
      {detail && <p className="mt-1 text-sm">{detail}</p>}
      <p className="mt-1.5 flex flex-wrap gap-x-3 gap-y-1 text-xs text-text-2">
        <span className="data">{t('probe.took', { millis: result.millis })}</span>
        {result.cache && <span className="data">{t('probe.cache', { value: result.cache })}</span>}
        {result.correlationId && <span className="data">{t('probe.correlation', { id: result.correlationId })}</span>}
      </p>
    </div>
  );
}

function StepHead({ title, lead }: { title: string; lead: string }) {
  return (
    <div>
      <h1 className="text-xl font-semibold tracking-title">{title}</h1>
      <p className="mt-2 max-w-[64ch] text-sm text-text-2">{lead}</p>
    </div>
  );
}
