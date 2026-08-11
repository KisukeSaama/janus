import { useState, type FormEvent, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, ArrowRight } from 'lucide-react';

import {
  del,
  keys,
  post,
  useApplications,
  type AuthType,
  type Credential,
  type Grant,
  type IssuedApplication,
  type Provider,
} from '../../api';
import { ChoiceField, Field, SelectField, Sheet } from '../../components';
import { useI18n } from '../../i18n';
import { toSlug } from '../../lib/connections';
import { useErrorMessage } from '../../lib/errors';

/**
 * Janus needs four records to authorize one call: an application, a provider, a credential, and a
 * grant. That is the security model, not a task. A developer's task is "let my service call this API
 * without holding its key", and this is the only screen that asks for it in those terms.
 *
 * Two steps: where it goes, and what it presents on arrival. Then a key and a request that works as
 * pasted. Nothing here asks which paths the caller may reach — registering an API admits it to all
 * of them, and the API's own authorisation decides the rest — which is what let the third step go.
 */

const NEW = '__new__';

type Step = 1 | 2;

export type NewConnection = {
  apiName: string;
  slug: string;
  applicationId: string;
  /** Empty when an existing service was reused: its key was shown once, long ago. */
  key: string;
};

export function ConnectFlow({ onClose, onDone }: { onClose: () => void; onDone: (c: NewConnection) => void }) {
  const { t } = useI18n();
  const describe = useErrorMessage();
  const client = useQueryClient();
  const callers = useApplications().data ?? [];

  const [step, setStep] = useState<Step>(1);
  const [apiName, setApiName] = useState('');
  const [slug, setSlug] = useState('');
  const [slugEdited, setSlugEdited] = useState(false);
  const [baseUrl, setBaseUrl] = useState('');
  const [authType, setAuthType] = useState<AuthType>('BEARER');
  const [headerName, setHeaderName] = useState('X-Api-Key');
  const [queryParameter, setQueryParameter] = useState('api_key');
  const [tokenUrl, setTokenUrl] = useState('');
  const [tokenScopes, setTokenScopes] = useState('');
  const [secret, setSecret] = useState('');
  const [callerId, setCallerId] = useState(NEW);
  const [callerName, setCallerName] = useState('');
  const [busy, setBusy] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [error, setError] = useState('');

  const effectiveSlug = slugEdited ? slug : toSlug(apiName);
  const started = apiName !== '' || baseUrl !== '' || secret !== '' || callerName !== '';

  const complete: Record<Step, boolean> = {
    1: apiName.trim() !== '' && effectiveSlug.length >= 3 && baseUrl.trim() !== '',
    2:
      (secret !== '' || authType === 'NONE') &&
      (authType !== 'API_KEY_HEADER' || headerName.trim() !== '') &&
      (authType !== 'API_KEY_QUERY' || queryParameter.trim() !== '') &&
      (authType !== 'OAUTH2_CLIENT_CREDENTIALS' || tokenUrl.trim() !== '') &&
      (callerId !== NEW || callerName.trim() !== ''),
  };

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    // Enter in a field submits the form, so the step gate is enforced here too and not only on the
    // button that shows it.
    if (!complete[step]) return;
    if (step < 2) {
      setStep((step + 1) as Step);
      return;
    }

    setBusy(true);
    setError('');
    // Created in dependency order and unwound in reverse if a later step is refused, so a failed
    // attempt never leaves half a connection behind.
    const undo: (() => Promise<unknown>)[] = [];
    let stage = t('connect.stepApi');

    try {
      const provider = await post<Provider>('/providers', {
        name: apiName.trim(),
        slug: effectiveSlug,
        baseUrl: baseUrl.trim(),
        enabled: true,
      });
      undo.push(() => del(`/providers/${provider.id}`));

      stage = t('connect.stepSecret');
      const credential = await post<Credential>('/credentials', {
        // An open API still gets a record: it is what the grant, the cache and the journal hang off.
        // Named for what it is, since "-secret" would describe a value that does not exist.
        name: authType === 'NONE' ? `${effectiveSlug}-open` : `${effectiveSlug}-secret`,
        providerId: provider.id,
        authType,
        headerName: authType === 'API_KEY_HEADER' ? headerName.trim() : null,
        queryParameter: authType === 'API_KEY_QUERY' ? queryParameter.trim() : null,
        tokenUrl: authType === 'OAUTH2_CLIENT_CREDENTIALS' ? tokenUrl.trim() : null,
        tokenScopes: authType === 'OAUTH2_CLIENT_CREDENTIALS' ? tokenScopes.trim() || null : null,
        secret: authType === 'NONE' ? null : secret,
        enabled: true,
      });
      undo.push(() => del(`/credentials/${credential.id}`));

      stage = t('connect.stepCaller');
      let applicationId = callerId;
      let issued = '';
      if (callerId === NEW) {
        const created = await post<IssuedApplication>('/applications', {
          name: callerName.trim(),
          description: null,
          enabled: true,
        });
        applicationId = created.application.id;
        issued = created.apiKey;
        undo.push(() => del(`/applications/${applicationId}`));
      }

      stage = t('connect.stepGrant');
      await post<Grant>('/grants', {
        applicationId,
        providerId: provider.id,
        credentialId: credential.id,
        enabled: true,
        rateLimitPerMinute: 0,
        rateLimitBurst: 0,
      });

      // Four records at once: everything the console shows is stale, so everything is refetched.
      await Promise.all(
        [keys.applications, keys.providers, keys.credentials, keys.grants, ['audit']].map((key) =>
          client.invalidateQueries({ queryKey: key }),
        ),
      );
      onDone({ apiName: apiName.trim(), slug: effectiveSlug, applicationId, key: issued });
    } catch (x) {
      for (const rollback of undo.reverse()) await rollback().catch(() => undefined);
      setError(t('connect.partial', { step: stage, reason: describe(x) }));
      setBusy(false);
    }
  }

  return (
    <Sheet
      label={t('connect.title')}
      head={
        <div className="flex items-center gap-3">
          <p className="stamp text-text-2" aria-live="polite">
            {t('connect.stepOf', { step, total: 2 })}
          </p>
          <span aria-hidden="true" className="flex gap-1">
            {[1, 2].map((n) => (
              <span key={n} className={`h-1 w-6 rounded-[1px] ${n <= step ? 'bg-accent' : 'bg-line'}`} />
            ))}
          </span>
          {leaving ? (
            <span className="flex items-center gap-1">
              <button className="btn btn-sm btn-destructive font-semibold" onClick={onClose}>
                {t('common.confirm')}
              </button>
              <button className="btn btn-sm btn-quiet" onClick={() => setLeaving(false)}>
                {t('common.cancel')}
              </button>
            </span>
          ) : (
            <button className="btn btn-sm btn-quiet" onClick={() => (started ? setLeaving(true) : onClose())}>
              {t('connect.abandon')}
            </button>
          )}
        </div>
      }
      footer={
        <>
          {step > 1 && (
            <button className="btn btn-secondary" type="button" onClick={() => setStep((step - 1) as Step)}>
              <ArrowLeft size={15} strokeWidth={2.25} />
              {t('common.back')}
            </button>
          )}
          <button
            type="submit"
            form="connect"
            className="btn btn-primary ml-auto min-w-[12rem]"
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
              t('connect.submit')
            )}
          </button>
        </>
      }
    >
      <form id="connect" onSubmit={submit} className="space-y-6">
        {step === 1 && (
          <>
            {/* The contract of the whole flow, said once, on the screen that opens it. */}
            <p className="text-sm text-accent-text">{t('connect.lead')}</p>
            <StepHead title={t('connect.s1Title')} lead={t('connect.s1Lead')} />
            <Field
              label={t('connect.apiName')}
              required
              autoFocus
              autoComplete="off"
              placeholder={t('connect.apiNamePlaceholder')}
              value={apiName}
              onChange={(e) => setApiName(e.target.value)}
              hint={t('connect.apiNameHint')}
            />
            <Field
              label={t('connect.baseUrl')}
              type="url"
              required
              data
              autoComplete="off"
              placeholder="https://api.example.com"
              value={baseUrl}
              onChange={(e) => setBaseUrl(e.target.value)}
              hint={t('connect.baseUrlHint')}
            />
            <GatewayPath
              slug={effectiveSlug}
              editing={slugEdited}
              onEdit={() => {
                setSlug(effectiveSlug);
                setSlugEdited(true);
              }}
              onChange={(value) => setSlug(toSlug(value))}
            />
          </>
        )}

        {step === 2 && (
          <>
            <StepHead
              title={authType === 'NONE' ? t('connect.s2TitleOpen') : t('connect.s2Title')}
              lead={authType === 'NONE' ? t('connect.s2LeadOpen') : t('connect.s2Lead')}
            />
            <ChoiceField
              label={t('connect.howSent')}
              name="authType"
              value={authType}
              onChange={(value) => setAuthType(value as AuthType)}
              options={[
                { value: 'BEARER', label: t('connect.authBearer'), hint: t('connect.authBearerHint') },
                { value: 'API_KEY_HEADER', label: t('connect.authHeader'), hint: t('connect.authHeaderHint') },
                { value: 'API_KEY_QUERY', label: t('connect.authQuery'), hint: t('connect.authQueryHint') },
                { value: 'BASIC', label: t('connect.authBasic'), hint: t('connect.authBasicHint') },
                {
                  value: 'OAUTH2_CLIENT_CREDENTIALS',
                  label: t('connect.authOauth2'),
                  hint: t('connect.authOauth2Hint'),
                },
                // Last, because it is the one case where this step asks for nothing.
                { value: 'NONE', label: t('connect.authNone'), hint: t('connect.authNoneHint') },
              ]}
            />
            {authType === 'API_KEY_HEADER' && (
              <Field
                label={t('connect.headerName')}
                required
                data
                autoComplete="off"
                value={headerName}
                onChange={(e) => setHeaderName(e.target.value)}
              />
            )}
            {authType === 'API_KEY_QUERY' && (
              <Field
                label={t('connect.queryParameter')}
                required
                data
                autoComplete="off"
                placeholder="api_key"
                value={queryParameter}
                onChange={(e) => setQueryParameter(e.target.value)}
              />
            )}
            {authType === 'OAUTH2_CLIENT_CREDENTIALS' && (
              <>
                <Field
                  label={t('connect.tokenUrl')}
                  type="url"
                  required
                  data
                  autoComplete="off"
                  placeholder="https://accounts.spotify.com/api/token"
                  value={tokenUrl}
                  onChange={(e) => setTokenUrl(e.target.value)}
                  hint={t('connect.tokenUrlHint')}
                />
                <Field
                  label={t('connect.tokenScopes')}
                  data
                  autoComplete="off"
                  value={tokenScopes}
                  onChange={(e) => setTokenScopes(e.target.value)}
                  hint={t('connect.tokenScopesHint')}
                />
              </>
            )}
            {authType !== 'NONE' && (
              <Field
                label={
                  authType === 'BASIC'
                    ? t('credentials.fieldSecretBasic')
                    : authType === 'OAUTH2_CLIENT_CREDENTIALS'
                      ? t('credentials.fieldSecretClient')
                      : t('connect.secretValue')
                }
                type="password"
                required
                autoFocus
                autoComplete="new-password"
                placeholder={
                  authType === 'BASIC'
                    ? t('connect.secretBasic')
                    : authType === 'OAUTH2_CLIENT_CREDENTIALS'
                      ? 'client_id:client_secret'
                      : undefined
                }
                value={secret}
                onChange={(e) => setSecret(e.target.value)}
                hint={
                  authType === 'OAUTH2_CLIENT_CREDENTIALS' ? t('connect.secretExchangeHint') : t('connect.secretHint')
                }
              />
            )}
            <div>
              <p className="stamp mb-1.5 text-text-2">{t('connect.preview')}</p>
              <p className="data rounded-control border border-line bg-sunk px-3 py-2 text-xs leading-5">
                {authType === 'NONE'
                  ? t('connect.previewOpen')
                  : authType === 'API_KEY_HEADER'
                    ? `${headerName.trim() || 'X-Api-Key'}: ${'•'.repeat(12)}`
                    : authType === 'API_KEY_QUERY'
                      ? `?${queryParameter.trim() || 'api_key'}=${'•'.repeat(12)}`
                      : `Authorization: ${authType === 'BASIC' ? 'Basic' : 'Bearer'} ${'•'.repeat(12)}`}
              </p>
              {authType === 'OAUTH2_CLIENT_CREDENTIALS' && (
                <p className="mt-1.5 text-xs text-text-2">{t('connect.exchangeNote')}</p>
              )}
            </div>

            {/*
             * Who calls, asked on the screen that finishes the job rather than on one of its own. It
             * is two fields once the routes are gone, and a step that holds two fields and a summary
             * is a click charged for nothing.
             */}
            <div className="space-y-6 border-t border-line pt-6">
              <SelectField
                label={t('connect.caller')}
                value={callerId}
                onChange={(e) => setCallerId(e.target.value)}
                options={[
                  { value: NEW, label: t('connect.callerNew') },
                  ...callers.map((a) => ({ value: a.id, label: a.name })),
                ]}
                hint={callerId === NEW ? t('connect.callerKeyNote') : t('connect.callerExistingNote')}
              />
              {callerId === NEW && (
                <Field
                  label={t('connect.callerName')}
                  required
                  autoComplete="off"
                  placeholder="orders-api"
                  value={callerName}
                  onChange={(e) => setCallerName(e.target.value)}
                  hint={t('connect.callerNameHint')}
                />
              )}

              <Review
                apiName={apiName.trim()}
                slug={effectiveSlug}
                baseUrl={baseUrl.trim()}
                authType={authType}
                caller={callerId === NEW ? callerName.trim() : (callers.find((a) => a.id === callerId)?.name ?? '')}
              />
            </div>
          </>
        )}

        {error && (
          <p role="alert" className="rounded-panel border border-bad/40 bg-bad-wash px-3.5 py-3 text-sm">
            {error}
          </p>
        )}
      </form>
    </Sheet>
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

/**
 * The address the caller will use. Derived from the API's name, because nobody should type the same
 * word twice, and shown as the whole URL rather than a slug field: what a developer needs to
 * recognise later is the address, not the fragment it was built from.
 */
function GatewayPath({
  slug,
  editing,
  onEdit,
  onChange,
}: {
  slug: string;
  editing: boolean;
  onEdit: () => void;
  onChange: (value: string) => void;
}) {
  const { t } = useI18n();
  return (
    <div className="rounded-panel border border-line bg-sunk px-3.5 py-3">
      {editing ? (
        <Field
          label={t('connect.slug')}
          required
          data
          autoComplete="off"
          value={slug}
          onChange={(e) => onChange(e.target.value)}
        />
      ) : (
        <>
          <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
            <p className="stamp text-text-2">{t('connect.slug')}</p>
            <button type="button" className="text-xs text-accent-text underline underline-offset-2" onClick={onEdit}>
              {t('connect.slugEdit')}
            </button>
          </div>
          <p className="data mt-1.5 break-all text-sm">
            <span className="text-text-3">{window.location.origin}</span>
            <span>/gateway/{slug || '…'}/</span>
          </p>
        </>
      )}
      <p className="mt-2 text-xs text-text-2">{t('connect.slugLead')}</p>
    </div>
  );
}

/** The last thing before four records exist: what is about to be created, in one glance. */
function Review({
  apiName,
  slug,
  baseUrl,
  authType,
  caller,
}: {
  apiName: string;
  slug: string;
  baseUrl: string;
  authType: AuthType;
  caller: string;
}) {
  const { t, tEnum } = useI18n();
  return (
    <section className="rounded-panel border border-line">
      <h2 className="stamp border-b border-line px-4 py-2.5 text-accent-text">{t('connect.review')}</h2>
      <dl className="divide-y divide-line text-sm">
        <Line label={t('connect.reviewApi')}>
          <span className="font-medium">{apiName}</span>
          <span className="data ml-2 break-all text-xs text-text-2">{baseUrl}</span>
        </Line>
        <Line label={authType === 'NONE' ? t('connect.reviewAuth') : t('connect.reviewSecret')}>
          {tEnum('authType', authType)}
        </Line>
        <Line label={t('connect.reviewCaller')}>{caller}</Line>
        {/* The caller's own URL, not the upstream's: shown whole, or it reads as a path onto the line above. */}
        <Line label={t('connect.reviewReach')}>
          <span className="data break-all text-xs text-text-2">
            <span className="text-text-3">{window.location.origin}</span>
            /gateway/{slug}/**
          </span>
        </Line>
      </dl>
    </section>
  );
}

function Line({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid gap-1 px-4 py-3 sm:grid-cols-[8rem_1fr] sm:gap-4">
      <dt className="stamp self-center text-text-3">{label}</dt>
      <dd className="min-w-0">{children}</dd>
    </div>
  );
}
