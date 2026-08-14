import { useState, type FormEvent, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, ArrowRight, TriangleAlert } from 'lucide-react';

import {
  del,
  keys,
  post,
  useOAuthCallback,
  useProviderCapabilities,
  type AuthType,
  type Credential,
  type Provider,
  type SignatureEncoding,
} from '../../api';
import { CheckField, ChoiceField, ConfirmDialog, CopyField, Field, Sheet } from '../../components';
import { useI18n } from '../../i18n';
import { gatewayUrl, toSlug } from '../../lib/connections';
import { useErrorMessage } from '../../lib/errors';
import { fromDateInput, NOTICE_DAYS, WARNING_DAYS } from '../../lib/expiry';
import { secretLabel, secretPlaceholder } from './secrets';

/**
 * An API is registered independently from the applications that may call it. This flow writes the
 * catalogue entry: the destination and the authentication contract, both deployment-wide.
 *
 * Registering is not activating. What makes an API active for somebody is the credential their own
 * account holds for it, so this flow only provisions one when it is asked to, and the box that asks
 * is unticked. An administrator writing the catalogue on behalf of the deployment is not thereby a
 * caller of every destination in it.
 *
 * What it never writes is a connection. Admitting a service to a destination is the service's own
 * decision, taken where the service is registered, and this flow is reached from there: the reader
 * ticking APIs for their new service opens it, describes the one the catalogue is missing, and comes
 * straight back to that list. Activating here only means this account now holds a credential, which
 * is what makes the entry tickable at all.
 *
 * Two steps: where it goes, and what it expects on arrival. Nothing here asks which paths the caller
 * may reach — registering an API admits it to all of them, and the API's own authorisation decides
 * the rest — which is what let the third step go.
 */

type Step = 1 | 2;

/** Everything a signed request needs, kept together because none of it means anything alone. */
type Signing = {
  template: string;
  encoding: SignatureEncoding;
  signatureHeader: string;
  signatureParameter: string;
  timestampHeader: string;
  timestampParameter: string;
};

const NO_SIGNING: Signing = {
  template: '',
  encoding: 'HEX',
  signatureHeader: '',
  signatureParameter: '',
  timestampHeader: '',
  timestampParameter: '',
};

export function ConnectFlow({
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

  const [step, setStep] = useState<Step>(1);
  const [apiName, setApiName] = useState('');
  const [slug, setSlug] = useState('');
  const [slugEdited, setSlugEdited] = useState(false);
  const [baseUrl, setBaseUrl] = useState('');
  // Only ever true where the deployment offers it: the box that sets it is not rendered otherwise.
  const [onLan, setOnLan] = useState(false);
  const capabilities = useProviderCapabilities();
  const [authType, setAuthType] = useState<AuthType>('BEARER');
  const [headerName, setHeaderName] = useState('X-Api-Key');
  const [queryParameter, setQueryParameter] = useState('api_key');
  const [tokenUrl, setTokenUrl] = useState('');
  const [tokenScopes, setTokenScopes] = useState('');
  // The account connection, set beside whatever the application itself presents rather than instead
  // of it. One API is one entry here, however many identities it happens to offer.
  const [connectable, setConnectable] = useState(false);
  const [authorizationUrl, setAuthorizationUrl] = useState('');
  const [connectionTokenUrl, setConnectionTokenUrl] = useState('');
  const [connectionScopes, setConnectionScopes] = useState('');
  const [connectionSecret, setConnectionSecret] = useState('');
  const [signing, setSigning] = useState<Signing>(NO_SIGNING);
  const [activate, setActivate] = useState(false);
  const [secret, setSecret] = useState('');
  const [expiresAt, setExpiresAt] = useState('');
  const [busy, setBusy] = useState(false);
  // The way out that is waiting on an answer, not merely the fact that one is. There are two exits
  // from this flow — the button that closes it and the wordmark that leaves for the console's home —
  // and the dialog has to fire the one that was actually clicked.
  const [leaving, setLeaving] = useState<(() => void) | null>(null);
  const [error, setError] = useState('');

  const effectiveSlug = slugEdited ? slug : toSlug(apiName);
  const started = apiName !== '' || baseUrl !== '' || secret !== '' || expiresAt !== '';

  /** Nothing typed, nothing to lose: an empty form leaves on the click rather than on a question. */
  const leave = (exit: () => void) => (started ? setLeaving(() => exit) : exit());

  const signs = authType === 'HMAC_SIGNATURE';
  const exchanges = authType === 'OAUTH2_CLIENT_CREDENTIALS';
  // Whether the connection needs an OAuth client of its own. It does not when the application
  // already stores one, which is every API that mints both kinds of token from a single client id.
  const connectionNeedsSecret = connectable && authType !== 'NONE' && !exchanges;

  const complete: Record<Step, boolean> = {
    1: apiName.trim() !== '' && effectiveSlug.length >= 3 && baseUrl.trim() !== '',
    2:
      // The contract is always required; the secret only when this account is activating with it.
      (authType !== 'API_KEY_HEADER' || headerName.trim() !== '') &&
      (authType !== 'API_KEY_QUERY' || queryParameter.trim() !== '') &&
      (!exchanges || tokenUrl.trim() !== '') &&
      (!connectable || (authorizationUrl.trim() !== '' && connectionTokenUrl.trim() !== '')) &&
      // A signature travels in exactly one place, which is the one rule a reader can get wrong here.
      (!signs ||
        (signing.template.trim() !== '' &&
          (signing.signatureHeader.trim() !== '') !== (signing.signatureParameter.trim() !== ''))) &&
      (!activate || authType === 'NONE' || secret !== '') &&
      (!activate || !connectionNeedsSecret || connectionSecret !== ''),
  };

  /** The authentication contract, in the shape both endpoints take it. */
  function contract() {
    return {
      authType,
      headerName:
        authType === 'API_KEY_HEADER' || (signs && headerName.trim() !== '') ? headerName.trim() : null,
      queryParameter: authType === 'API_KEY_QUERY' ? queryParameter.trim() : null,
      tokenUrl: exchanges ? tokenUrl.trim() : null,
      tokenScopes: exchanges ? tokenScopes.trim() || null : null,
      signatureAlgorithm: signs ? 'HMAC_SHA256' : null,
      signatureTemplate: signs ? signing.template.trim() : null,
      signatureEncoding: signs ? signing.encoding : null,
      signatureHeader: signs ? signing.signatureHeader.trim() || null : null,
      signatureParameter: signs ? signing.signatureParameter.trim() || null : null,
      timestampHeader: signs ? signing.timestampHeader.trim() || null : null,
      timestampParameter: signs ? signing.timestampParameter.trim() || null : null,
    };
  }

  /** What the API offers an account holder, cleared as a block when it offers nothing. */
  function connection() {
    return {
      connectionAuthorizationUrl: connectable ? authorizationUrl.trim() : null,
      connectionTokenUrl: connectable ? connectionTokenUrl.trim() : null,
      connectionScopes: connectable ? connectionScopes.trim() || null : null,
      connectionClientAuth: connectable ? 'BASIC' : null,
    };
  }

  /** Every list this flow can have written to, told at once that it is out of date. */
  const refresh = () =>
    Promise.all(
      [keys.providers, keys.credentials, ['audit']].map((key) => client.invalidateQueries({ queryKey: key })),
    );

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
        allowPrivateDestination: onLan,
        ...contract(),
        ...connection(),
      });
      undo.push(() => del(`/providers/${provider.id}`));

      // Only when this account asked for it. Without a credential the entry stays available in the
      // catalogue, which is what every other account sees of it until they activate it themselves.
      if (activate) {
        stage = t('connect.stepSecret');
        const credential = await post<Credential>('/credentials', {
          // An open API still gets a record: it is what the grant, the cache and the journal hang
          // off. Named for what it is, since "-secret" would describe a value that does not exist.
          name: authType === 'NONE' ? `${effectiveSlug}-open` : `${effectiveSlug}-secret`,
          providerId: provider.id,
          ...contract(),
          secret: authType === 'NONE' ? null : secret,
          // Only where the connection does not share what the application already stores.
          connectionSecret: connectionNeedsSecret ? connectionSecret : null,
          // Empty is a supported answer: many upstream keys have no published end date. Converting
          // only here keeps the form in the operator's calendar while the API receives an instant.
          expiresAt: authType === 'NONE' ? null : fromDateInput(expiresAt),
          enabled: true,
        });
        undo.push(() => del(`/credentials/${credential.id}`));
      }

      await refresh();
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
      onHome={() => leave(onHome)}
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
        </div>
      }
      /* Leaving sits at the far end of the bar from the button that carries on: the two answers to
         the same question, told apart by distance and by weight rather than by corner. */
      footer={
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
              <button className="btn btn-secondary" type="button" onClick={() => setStep((step - 1) as Step)}>
                <ArrowLeft size={15} strokeWidth={2.25} />
                {t('common.back')}
              </button>
            )}
            <button
              type="submit"
              form="connect"
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
                t('connect.submit')
              )}
            </button>
          </div>
        </>
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
            {capabilities.data?.privateDestinations && (
              <CheckField
                label={t('connect.lan')}
                hint={t('connect.lanHint')}
                checked={onLan}
                onChange={(e) => setOnLan(e.target.checked)}
              />
            )}
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
                // Last of those that send something: a reader only picks it knowing they need it.
                { value: 'HMAC_SIGNATURE', label: t('connect.authHmac'), hint: t('connect.authHmacHint') },
                // Last overall, because it is the one case where this step asks for nothing.
                { value: 'NONE', label: t('connect.authNone'), hint: t('connect.authNoneHint') },
              ]}
            />
            {(authType === 'API_KEY_HEADER' || signs) && (
              <Field
                label={signs ? t('connect.keyHeaderName') : t('connect.headerName')}
                required={authType === 'API_KEY_HEADER'}
                data
                autoComplete="off"
                value={headerName}
                onChange={(e) => setHeaderName(e.target.value)}
                hint={signs ? t('connect.keyHeaderNameHint') : undefined}
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
            {exchanges && (
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
            {signs && <SigningFields value={signing} onChange={setSigning} />}

            {/* Beside the contract above, not instead of it: this is the second identity the same
                API may offer, and the reason it no longer has to be registered twice. */}
            <div className="space-y-6 border-t border-line pt-6">
              <CheckField
                label={t('connect.connectionLabel')}
                checked={connectable}
                onChange={(e) => setConnectable(e.target.checked)}
                hint={t('connect.connectionHint')}
              />
              {connectable && (
                <>
                  {/* Registering this with the provider is a step outside Janus, and the one nobody
                      is told about until an authorisation is refused for an undeclared redirect. */}
                  <CallbackToRegister />
                  <Field
                    label={t('connect.authorizationUrl')}
                    type="url"
                    required
                    data
                    autoComplete="off"
                    placeholder="https://accounts.spotify.com/authorize"
                    value={authorizationUrl}
                    onChange={(e) => setAuthorizationUrl(e.target.value)}
                    hint={t('connect.authorizationUrlHint')}
                  />
                  <Field
                    label={t('connect.tokenUrl')}
                    type="url"
                    required
                    data
                    autoComplete="off"
                    placeholder="https://accounts.spotify.com/api/token"
                    value={connectionTokenUrl}
                    onChange={(e) => setConnectionTokenUrl(e.target.value)}
                    hint={t('connect.tokenUrlHint')}
                  />
                  <Field
                    label={t('connect.tokenScopes')}
                    data
                    autoComplete="off"
                    value={connectionScopes}
                    onChange={(e) => setConnectionScopes(e.target.value)}
                    hint={t('connect.tokenScopesHintUser')}
                  />
                  <p className="text-xs text-text-2">{t('connect.consentNote')}</p>
                </>
              )}
            </div>
            <div>
              <p className="stamp mb-1.5 text-text-2">{t('connect.preview')}</p>
              <p className="data rounded-control border border-line bg-sunk px-3 py-2 text-xs leading-5">
                <Preview
                  authType={authType}
                  headerName={headerName}
                  queryParameter={queryParameter}
                  signing={signing}
                />
              </p>
              {authType === 'OAUTH2_CLIENT_CREDENTIALS' && (
                <p className="mt-1.5 text-xs text-text-2">{t('connect.exchangeNote')}</p>
              )}
            </div>

            {/* The one question in this flow that is about the operator's own account rather than
                the deployment, so it is separated from the contract above it. */}
            <div className="space-y-6 border-t border-line pt-6">
              <CheckField
                label={t('connect.activateLabel')}
                checked={activate}
                onChange={(e) => setActivate(e.target.checked)}
                hint={t('connect.activateHint')}
              />
              {activate && authType !== 'NONE' && (
                <>
                  <Field
                    label={secretLabel(authType, t)}
                    type="password"
                    required
                    autoFocus
                    autoComplete="new-password"
                    placeholder={secretPlaceholder(authType, t)}
                    value={secret}
                    onChange={(e) => setSecret(e.target.value)}
                    hint={
                      authType === 'OAUTH2_CLIENT_CREDENTIALS'
                        ? t('connect.secretExchangeHint')
                        : t('connect.secretHint')
                    }
                  />
                  {connectionNeedsSecret && (
                    <Field
                      label={t('connect.connectionSecret')}
                      type="password"
                      required
                      autoComplete="new-password"
                      placeholder="client_id:client_secret"
                      value={connectionSecret}
                      onChange={(e) => setConnectionSecret(e.target.value)}
                      hint={t('connect.connectionSecretHint')}
                    />
                  )}
                  <Field
                    label={t('expiry.field')}
                    type="date"
                    data
                    value={expiresAt}
                    onChange={(e) => setExpiresAt(e.target.value)}
                    hint={
                      exchanges
                        ? t('credentials.expiryHintExchange')
                        : t('expiry.fieldHint', { notice: NOTICE_DAYS, warning: WARNING_DAYS })
                    }
                  />
                </>
              )}
            </div>

            <div className="space-y-6 border-t border-line pt-6">
              <Review
                apiName={apiName.trim()}
                slug={effectiveSlug}
                baseUrl={baseUrl.trim()}
                authType={authType}
                activate={activate}
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

/**
 * The redirect an operator has to declare at the provider before any of this works.
 *
 * Shown here rather than left in the documentation because it is the one prerequisite Janus knows
 * and the reader does not: it is built from this deployment's public URL, which is why it is asked
 * of the server. When that URL was never configured, the address below is a localhost default that
 * every provider will refuse, and saying so here costs less than discovering it after a consent.
 */
function CallbackToRegister() {
  const { t } = useI18n();
  const callback = useOAuthCallback();
  if (!callback.data) return null;
  return (
    <div className="space-y-2">
      <CopyField label={t('connect.callbackLabel')} value={callback.data.url} />
      <p className="text-xs text-text-2">{t('connect.callbackHint')}</p>
      {!callback.data.configured && <Caveat>{t('connect.callbackUnconfigured')}</Caveat>}
    </div>
  );
}

/** Something the API needs that Janus does not do for it. Said plainly rather than left to be found. */
function Caveat({ children }: { children: ReactNode }) {
  return (
    <p className="flex gap-2.5 rounded-panel border border-warn/40 bg-warn-wash px-3.5 py-3 text-sm">
      <TriangleAlert size={15} strokeWidth={2.25} aria-hidden="true" className="mt-0.5 shrink-0" />
      <span>{children}</span>
    </p>
  );
}

/**
 * The recipe for a signed request.
 *
 * The only place in this flow that asks a reader to know something structural about their API, which
 * is why it is grouped and set apart: somebody who arrived here has their provider's documentation
 * open anyway.
 */
function SigningFields({ value, onChange }: { value: Signing; onChange: (next: Signing) => void }) {
  const { t } = useI18n();
  const set = (patch: Partial<Signing>) => onChange({ ...value, ...patch });
  return (
    <div className="space-y-6 rounded-panel border border-line bg-sunk p-4">
      <Field
        label={t('connect.signTemplate')}
        required
        data
        autoComplete="off"
        placeholder="{timestamp}{method}{path}{body}"
        value={value.template}
        onChange={(e) => set({ template: e.target.value })}
        hint={t('connect.signTemplateHint')}
      />
      <ChoiceField
        label={t('connect.signEncoding')}
        name="signatureEncoding"
        value={value.encoding}
        onChange={(next) => set({ encoding: next as SignatureEncoding })}
        options={[
          { value: 'HEX', label: t('connect.signHex'), hint: t('connect.signHexHint') },
          { value: 'BASE64', label: t('connect.signBase64'), hint: t('connect.signBase64Hint') },
        ]}
      />
      {/* Setting either one clears the other: the signature goes in exactly one place. */}
      <div className="grid gap-6 sm:grid-cols-2">
        <Field
          label={t('connect.signHeader')}
          data
          autoComplete="off"
          placeholder="CB-ACCESS-SIGN"
          value={value.signatureHeader}
          onChange={(e) => set({ signatureHeader: e.target.value, signatureParameter: '' })}
        />
        <Field
          label={t('connect.signParameter')}
          data
          autoComplete="off"
          placeholder="signature"
          value={value.signatureParameter}
          onChange={(e) => set({ signatureParameter: e.target.value, signatureHeader: '' })}
        />
      </div>
      <p className="text-xs text-text-2">{t('connect.signWhereHint')}</p>
      <div className="grid gap-6 sm:grid-cols-2">
        <Field
          label={t('connect.timestampHeader')}
          data
          autoComplete="off"
          placeholder="CB-ACCESS-TIMESTAMP"
          value={value.timestampHeader}
          onChange={(e) => set({ timestampHeader: e.target.value, timestampParameter: '' })}
        />
        <Field
          label={t('connect.timestampParameter')}
          data
          autoComplete="off"
          placeholder="timestamp"
          value={value.timestampParameter}
          onChange={(e) => set({ timestampParameter: e.target.value, timestampHeader: '' })}
        />
      </div>
    </div>
  );
}

/** What will actually leave, in the shape it will leave in. */
function Preview({
  authType,
  headerName,
  queryParameter,
  signing,
}: {
  authType: AuthType;
  headerName: string;
  queryParameter: string;
  signing: Signing;
}) {
  const { t } = useI18n();
  const dots = '•'.repeat(12);
  switch (authType) {
    case 'NONE':
      return <>{t('connect.previewOpen')}</>;
    case 'API_KEY_HEADER':
      return <>{`${headerName.trim() || 'X-Api-Key'}: ${dots}`}</>;
    case 'API_KEY_QUERY':
      return <>{`?${queryParameter.trim() || 'api_key'}=${dots}`}</>;
    case 'BASIC':
      return <>{`Authorization: Basic ${dots}`}</>;
    case 'HMAC_SIGNATURE':
      return (
        <>
          {headerName.trim() !== '' && (
            <>
              {`${headerName.trim()}: ${dots}`}
              <br />
            </>
          )}
          {signing.signatureParameter.trim() !== ''
            ? `?${signing.signatureParameter.trim()}=${dots}`
            : `${signing.signatureHeader.trim() || 'X-Signature'}: ${dots}`}
        </>
      );
    default:
      return <>{`Authorization: Bearer ${dots}`}</>;
  }
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
          <p className="data mt-1.5 break-all text-sm">{gatewayUrl(slug || '…')}/</p>
        </>
      )}
      <p className="mt-2 text-xs text-text-2">{t('connect.slugLead')}</p>
    </div>
  );
}

/** The last thing before the catalogue entry exists: what is about to be created, and for whom. */
function Review({
  apiName,
  slug,
  baseUrl,
  authType,
  activate,
}: {
  apiName: string;
  slug: string;
  baseUrl: string;
  authType: AuthType;
  activate: boolean;
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
        <Line label={t('connect.reviewAuth')}>{tEnum('authType', authType)}</Line>
        <Line label={t('connect.reviewReach')}>
          <span className="data break-all text-xs text-text-2">{gatewayUrl(slug)}/**</span>
        </Line>
        {/* Two different records, and the reader is about to create either one or both. */}
        <Line label={t('connect.reviewActivation')}>
          {activate ? t('connect.reviewActivationNow') : t('connect.reviewActivationLater')}
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
