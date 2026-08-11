import { useState, type FormEvent, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, ArrowRight } from 'lucide-react';

import {
  del,
  keys,
  post,
  type AuthType,
  type Credential,
  type Provider,
} from '../../api';
import { ChoiceField, Field, Sheet } from '../../components';
import { useI18n } from '../../i18n';
import { gatewayUrl, toSlug } from '../../lib/connections';
import { useErrorMessage } from '../../lib/errors';
import { fromDateInput, NOTICE_DAYS, WARNING_DAYS } from '../../lib/expiry';

/**
 * An API is registered independently from the applications that may call it. This flow creates the
 * provider and its credential; application forms create the grants afterwards.
 *
 * Two steps: where it goes, and what it presents on arrival. Then a key and a request that works as
 * pasted. Nothing here asks which paths the caller may reach — registering an API admits it to all
 * of them, and the API's own authorisation decides the rest — which is what let the third step go.
 */

type Step = 1 | 2;

export function ConnectFlow({
  username,
  onClose,
  onDone,
}: {
  username: string;
  onClose: () => void;
  onDone: () => void;
}) {
  const { t } = useI18n();
  const describe = useErrorMessage();
  const client = useQueryClient();

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
  const [expiresAt, setExpiresAt] = useState('');
  const [busy, setBusy] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [error, setError] = useState('');

  const effectiveSlug = slugEdited ? slug : toSlug(apiName);
  const started = apiName !== '' || baseUrl !== '' || secret !== '' || expiresAt !== '';

  const complete: Record<Step, boolean> = {
    1: apiName.trim() !== '' && effectiveSlug.length >= 3 && baseUrl.trim() !== '',
    2:
      (secret !== '' || authType === 'NONE') &&
      (authType !== 'API_KEY_HEADER' || headerName.trim() !== '') &&
      (authType !== 'API_KEY_QUERY' || queryParameter.trim() !== '') &&
      (authType !== 'OAUTH2_CLIENT_CREDENTIALS' || tokenUrl.trim() !== ''),
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
        // Empty is a supported answer: many upstream keys have no published end date. Converting
        // only here keeps the form in the operator's calendar while the API receives an instant.
        expiresAt: authType === 'NONE' ? null : fromDateInput(expiresAt),
        enabled: true,
      });
      undo.push(() => del(`/credentials/${credential.id}`));

      // Registering an API is independent from authorising callers. Applications subscribe to it
      // later from their own form, which may create any number of grants.
      await Promise.all(
        [keys.providers, keys.credentials, ['audit']].map((key) =>
          client.invalidateQueries({ queryKey: key }),
        ),
      );
      onDone();
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
              username={username}
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
                      : authType === 'API_KEY_HEADER' || authType === 'API_KEY_QUERY'
                        ? t('connect.apiKeyValue')
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
            {authType !== 'NONE' && (
              <Field
                label={t('expiry.field')}
                type="date"
                data
                value={expiresAt}
                onChange={(e) => setExpiresAt(e.target.value)}
                hint={
                  authType === 'OAUTH2_CLIENT_CREDENTIALS'
                    ? t('credentials.expiryHintExchange')
                    : t('expiry.fieldHint', { notice: NOTICE_DAYS, warning: WARNING_DAYS })
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

            <div className="space-y-6 border-t border-line pt-6">
              <Review
                apiName={apiName.trim()}
                username={username}
                slug={effectiveSlug}
                baseUrl={baseUrl.trim()}
                authType={authType}
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
  username,
  slug,
  editing,
  onEdit,
  onChange,
}: {
  username: string;
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
          <p className="data mt-1.5 break-all text-sm">{gatewayUrl(username, slug || '…')}/</p>
        </>
      )}
      <p className="mt-2 text-xs text-text-2">{t('connect.slugLead')}</p>
    </div>
  );
}

/** The last thing before the API and its credential exist: what is about to be created. */
function Review({
  apiName,
  username,
  slug,
  baseUrl,
  authType,
}: {
  apiName: string;
  username: string;
  slug: string;
  baseUrl: string;
  authType: AuthType;
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
        <Line label={t('connect.reviewReach')}>
          <span className="data break-all text-xs text-text-2">{gatewayUrl(username, slug)}/**</span>
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
