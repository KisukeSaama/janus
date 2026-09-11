import { useState, type ReactNode } from 'react';
import { TriangleAlert } from 'lucide-react';

import {
  useOAuthCallback,
  useProviderCapabilities,
  useUpdateProvider,
  type AuthType,
  type Provider,
  type SignatureAlgorithm,
  type SignatureEncoding,
  type TokenClientAuth,
} from '../../api';
import { CheckField, ChoiceField, CopyField, Field, FormLayout, SidePanel } from '../../components';
import { useI18n } from '../../i18n';
import { useErrorMessage } from '../../lib/errors';
import {
  contractComplete,
  destinationComplete,
  draftFrom,
  toProviderInput,
  type ProviderDraft,
  type SetDraft,
  type Signing,
} from './providerDraft';

/* ── Sections ───────────────────────────────────────────────────────────── */

function Section({ title, lead, children }: { title: string; lead?: string; children: ReactNode }) {
  return (
    <section className="space-y-5 border-t border-line pt-5">
      <div>
        <h2 className="stamp text-text-2">{title}</h2>
        {lead && <p className="mt-1.5 text-xs text-text-2">{lead}</p>}
      </div>
      {children}
    </section>
  );
}

const number = (value: string) => (value === '' ? 0 : Number(value));

/**
 * Where the API lives. The gateway path is a plain field unless the caller brings its own rendering,
 * which the registration flow does to derive it from the name.
 */
export function DestinationFields({
  draft,
  set,
  slug,
  autoFocus = false,
  showEnabled = true,
}: {
  draft: ProviderDraft;
  set: SetDraft;
  slug?: ReactNode;
  autoFocus?: boolean;
  /** A new API is always registered active; the switch only means something afterwards. */
  showEnabled?: boolean;
}) {
  const { t } = useI18n();
  const capabilities = useProviderCapabilities();
  return (
    <>
      <Field
        label={t('connect.apiName')}
        required
        autoFocus={autoFocus}
        autoComplete="off"
        placeholder={t('connect.apiNamePlaceholder')}
        value={draft.name}
        onChange={(e) => set({ name: e.target.value })}
        hint={t('connect.apiNameHint')}
      />
      <Field
        label={t('connect.baseUrl')}
        type="url"
        required
        data
        autoComplete="off"
        placeholder="https://api.example.com"
        value={draft.baseUrl}
        onChange={(e) => set({ baseUrl: e.target.value })}
        hint={t('connect.baseUrlHint')}
      />
      {/*
        Shown when the deployment offers it, and also whenever this destination already carries it —
        a deployment that withdraws the option must not leave a field the form silently unsets.
      */}
      {(capabilities.data?.privateDestinations || draft.allowPrivateDestination) && (
        <CheckField
          label={t('connect.lan')}
          hint={t('connect.lanHint')}
          checked={draft.allowPrivateDestination}
          onChange={(e) => set({ allowPrivateDestination: e.target.checked })}
        />
      )}
      {slug ?? (
        <Field
          label={t('connect.slug')}
          required
          data
          autoComplete="off"
          value={draft.slug}
          onChange={(e) => set({ slug: e.target.value })}
          hint={t('providers.slugHint')}
        />
      )}
      {showEnabled && (
        <CheckField
          label={t('providers.enabledLabel')}
          hint={t('providers.enabledHint')}
          checked={draft.enabled}
          onChange={(e) => set({ enabled: e.target.checked })}
        />
      )}
    </>
  );
}

/** How the API expects to be called, with everything each strategy needs beside it. */
export function ContractFields({ draft, set }: { draft: ProviderDraft; set: SetDraft }) {
  const { t } = useI18n();
  const { authType } = draft;
  const signs = authType === 'HMAC_SIGNATURE';
  const exchanges = authType === 'OAUTH2_CLIENT_CREDENTIALS';
  return (
    <>
      <ChoiceField
        label={t('connect.howSent')}
        name="authType"
        value={authType}
        onChange={(value) => set({ authType: value as AuthType })}
        options={[
          { value: 'BEARER', label: t('connect.authBearer'), hint: t('connect.authBearerHint') },
          { value: 'API_KEY_HEADER', label: t('connect.authHeader'), hint: t('connect.authHeaderHint') },
          { value: 'API_KEY_QUERY', label: t('connect.authQuery'), hint: t('connect.authQueryHint') },
          { value: 'BASIC', label: t('connect.authBasic'), hint: t('connect.authBasicHint') },
          { value: 'OAUTH2_CLIENT_CREDENTIALS', label: t('connect.authOauth2'), hint: t('connect.authOauth2Hint') },
          // Last of those that send something: a reader only picks it knowing they need it.
          { value: 'HMAC_SIGNATURE', label: t('connect.authHmac'), hint: t('connect.authHmacHint') },
          // Last overall, because it is the one case where this asks for nothing.
          { value: 'NONE', label: t('connect.authNone'), hint: t('connect.authNoneHint') },
        ]}
      />
      {(authType === 'API_KEY_HEADER' || signs) && (
        <Field
          label={signs ? t('connect.keyHeaderName') : t('connect.headerName')}
          required={authType === 'API_KEY_HEADER'}
          data
          autoComplete="off"
          value={draft.headerName}
          onChange={(e) => set({ headerName: e.target.value })}
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
          value={draft.queryParameter}
          onChange={(e) => set({ queryParameter: e.target.value })}
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
            value={draft.tokenUrl}
            onChange={(e) => set({ tokenUrl: e.target.value })}
            hint={t('connect.tokenUrlHint')}
          />
          <Field
            label={t('connect.tokenScopes')}
            data
            autoComplete="off"
            value={draft.tokenScopes}
            onChange={(e) => set({ tokenScopes: e.target.value })}
            hint={t('connect.tokenScopesHint')}
          />
          <Field
            label={t('connect.clientIdHeader')}
            data
            autoComplete="off"
            placeholder="Client-Id"
            value={draft.clientIdHeader}
            onChange={(e) => set({ clientIdHeader: e.target.value })}
            hint={t('connect.clientIdHeaderHint')}
          />
          <ClientAuthField
            name="tokenClientAuth"
            value={draft.tokenClientAuth}
            onChange={(tokenClientAuth) => set({ tokenClientAuth })}
          />
        </>
      )}
      {signs && <SigningFields value={draft.signing} onChange={(signing) => set({ signing })} />}
    </>
  );
}

/**
 * The second identity the same API may offer: an account holder's own, obtained with their consent.
 * Beside the contract, not instead of it.
 */
export function ConnectionOfferFields({ draft, set }: { draft: ProviderDraft; set: SetDraft }) {
  const { t } = useI18n();
  return (
    <>
      <CheckField
        label={t('connect.connectionLabel')}
        checked={draft.connectable}
        onChange={(e) => set({ connectable: e.target.checked })}
        hint={t('connect.connectionHint')}
      />
      {draft.connectable && (
        <>
          {/* Registering this with the provider is a step outside Janus, and the one nobody is told
              about until an authorisation is refused for an undeclared redirect. */}
          <CallbackToRegister />
          <Field
            label={t('connect.authorizationUrl')}
            type="url"
            required
            data
            autoComplete="off"
            placeholder="https://accounts.spotify.com/authorize"
            value={draft.connectionAuthorizationUrl}
            onChange={(e) => set({ connectionAuthorizationUrl: e.target.value })}
            hint={t('connect.authorizationUrlHint')}
          />
          <Field
            label={t('connect.tokenUrl')}
            type="url"
            required
            data
            autoComplete="off"
            placeholder="https://accounts.spotify.com/api/token"
            value={draft.connectionTokenUrl}
            onChange={(e) => set({ connectionTokenUrl: e.target.value })}
            hint={t('connect.tokenUrlHint')}
          />
          <Field
            label={t('connect.tokenScopes')}
            data
            autoComplete="off"
            value={draft.connectionScopes}
            onChange={(e) => set({ connectionScopes: e.target.value })}
            hint={t('connect.tokenScopesHintUser')}
          />
          <ClientAuthField
            name="connectionClientAuth"
            value={draft.connectionClientAuth}
            onChange={(connectionClientAuth) => set({ connectionClientAuth })}
          />
        </>
      )}
    </>
  );
}

/** Cache, response conversion, GraphQL and the outbound ceiling: inherited by every caller. */
export function PolicyFields({ draft, set }: { draft: ProviderDraft; set: SetDraft }) {
  const { t } = useI18n();
  return (
    <>
      <CheckField
        label={t('providers.cacheLabel')}
        checked={draft.cacheEnabled}
        onChange={(e) => set({ cacheEnabled: e.target.checked })}
        hint={t('providers.cacheHint')}
      />
      <Field
        label={t('providers.cacheTtlLabel')}
        type="number"
        min={0}
        max={86400}
        data
        value={draft.cacheTtlSeconds}
        onChange={(e) => set({ cacheTtlSeconds: number(e.target.value) })}
        hint={t('providers.cacheTtlHint')}
      />
      <CheckField
        label={t('providers.normalizeLabel')}
        checked={draft.normalizeJson}
        onChange={(e) => set({ normalizeJson: e.target.checked })}
        hint={t('providers.normalizeHint')}
      />
      {draft.normalizeJson && (
        <Field
          label={t('providers.arrayPathsLabel')}
          data
          autoComplete="off"
          maxLength={1000}
          placeholder="MediaContainer.Directory, Location"
          value={draft.jsonArrayPaths}
          onChange={(e) => set({ jsonArrayPaths: e.target.value })}
          hint={t('providers.arrayPathsHint')}
        />
      )}
      <CheckField
        label={t('providers.graphqlLabel')}
        checked={draft.graphql}
        onChange={(e) => set({ graphql: e.target.checked })}
        hint={t('providers.graphqlHint')}
      />
      {draft.graphql && (
        <>
          <Field
            label={t('providers.graphqlPathLabel')}
            required
            data
            autoComplete="off"
            maxLength={200}
            placeholder="/graphql"
            value={draft.graphqlPath}
            onChange={(e) => set({ graphqlPath: e.target.value })}
            hint={t('providers.graphqlPathHint')}
          />
          <Field
            label={t('providers.graphqlDepthLabel')}
            type="number"
            min={0}
            max={100}
            data
            value={draft.graphqlMaxDepth}
            onChange={(e) => set({ graphqlMaxDepth: number(e.target.value) })}
            hint={t('providers.graphqlDepthHint')}
          />
          <Field
            label={t('providers.graphqlAliasesLabel')}
            type="number"
            min={0}
            max={10000}
            data
            value={draft.graphqlMaxAliases}
            onChange={(e) => set({ graphqlMaxAliases: number(e.target.value) })}
            hint={t('providers.graphqlAliasesHint')}
          />
        </>
      )}
      <Field
        label={t('providers.rateLimitLabel')}
        type="number"
        min={0}
        max={1000000}
        data
        value={draft.rateLimitPerMinute}
        onChange={(e) => set({ rateLimitPerMinute: number(e.target.value) })}
        hint={t('providers.rateLimitHint')}
      />
      <Field
        label={t('providers.burstLabel')}
        type="number"
        min={0}
        max={100000}
        data
        value={draft.rateLimitBurst}
        onChange={(e) => set({ rateLimitBurst: number(e.target.value) })}
        hint={t('providers.burstHint')}
      />
    </>
  );
}

/**
 * Every setting an API has, in one panel. Opened from the catalogue and from a connection alike, so
 * an administrator meets the same form wherever they decide to change the API.
 */
export function ProviderEditPanel({ provider, onClose }: { provider: Provider; onClose: () => void }) {
  const { t } = useI18n();
  const describe = useErrorMessage();
  const updateProvider = useUpdateProvider();
  const [draft, setDraft] = useState(() => draftFrom(provider));
  const [error, setError] = useState('');
  const set: SetDraft = (patch) => setDraft((current) => ({ ...current, ...patch }));

  async function submit() {
    setError('');
    try {
      await updateProvider.mutateAsync({ id: provider.id, input: toProviderInput(draft) });
      onClose();
    } catch (x) {
      setError(describe(x));
    }
  }

  return (
    <SidePanel title={t('credentials.editApi')} intro={t('credentials.adminIntro')} onClose={onClose}>
      <FormLayout
        onSubmit={submit}
        submitLabel={t('common.saveChanges')}
        submitDisabled={!destinationComplete(draft) || !contractComplete(draft)}
        error={error}
      >
        <DestinationFields draft={draft} set={set} />
        <Section title={t('connect.reviewAuth')}>
          <ContractFields draft={draft} set={set} />
          <ConnectionOfferFields draft={draft} set={set} />
        </Section>
        <Section title={t('providers.policySection')} lead={t('providers.policyIntro')}>
          <PolicyFields draft={draft} set={set} />
        </Section>
      </FormLayout>
    </SidePanel>
  );
}

/* ── Pieces ─────────────────────────────────────────────────────────────── */

function ClientAuthField({
  name,
  value,
  onChange,
}: {
  name: string;
  value: TokenClientAuth;
  onChange: (value: TokenClientAuth) => void;
}) {
  const { t } = useI18n();
  return (
    <ChoiceField
      label={t('connect.clientAuth')}
      name={name}
      value={value}
      onChange={(next) => onChange(next as TokenClientAuth)}
      options={[
        { value: 'BASIC', label: t('connect.clientAuthBasic'), hint: t('connect.clientAuthBasicHint') },
        { value: 'POST', label: t('connect.clientAuthPost'), hint: t('connect.clientAuthPostHint') },
      ]}
    />
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
 * The only place that asks a reader to know something structural about their API, which is why it is
 * grouped and set apart: somebody who arrived here has their provider's documentation open anyway.
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
        label={t('connect.signAlgorithm')}
        name="signatureAlgorithm"
        value={value.algorithm}
        onChange={(next) => set({ algorithm: next as SignatureAlgorithm })}
        options={[
          { value: 'HMAC_SHA256', label: 'HMAC-SHA256', hint: t('connect.signSha256Hint') },
          { value: 'HMAC_SHA512', label: 'HMAC-SHA512', hint: t('connect.signSha512Hint') },
        ]}
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
