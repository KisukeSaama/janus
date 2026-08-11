import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { AlertTriangle } from 'lucide-react';

import {
  del,
  keys,
  post,
  useApplications,
  useCredentials,
  useDeleteGrant,
  useGrants,
  usePingProvider,
  useProviders,
  usePurgeProviderCache,
  useRotateApplicationKey,
  useUpdateCredential,
  useOAuthCallback,
  useUpdateGrant,
  useUpdateProvider,
  type Credential,
  type Grant,
  type Identity,
  type Provider,
} from '../../api';
import {
  Block,
  CheckField,
  ConfirmAction,
  CopyField,
  DeleteAction,
  ExpiryState,
  Field,
  FormLayout,
  KeyIssued,
  LiveState,
  Notice,
  PageHead,
  PageSkeleton,
  PingState,
  SidePanel,
} from '../../components';
import { useI18n } from '../../i18n';
import { buildConnections, curlFor, gatewayUrl, type Connection } from '../../lib/connections';
import { useErrorMessage } from '../../lib/errors';

/**
 * One connection, and everything anybody asks about it: how to call it, where it goes, how often,
 * which secret it presents, how old the caller's key is, and how to stop it.
 *
 * This is where a registered API lives, not a row in a table somewhere else: the destination, its
 * traffic policy and the caller's quota are all edited here, beside the diagnosis that says why an
 * active-looking connection forwards nothing. It is the only place that reads all four `enabled`
 * flags in the order the gateway reads them.
 */

/** Where an operator has to go to lift a block that is not about the destination. */
const FIX_TARGET: Record<'application' | 'credential', 'applications' | 'credentials'> = {
  application: 'applications',
  credential: 'credentials',
};

export function ConnectionPage({
  id,
  onBack,
  onFix,
  identity,
}: {
  id: string;
  onBack: () => void;
  onFix: (to: 'applications' | 'credentials') => void;
  identity: Identity;
}) {
  const { t, tEnum, formatAge, formatDate } = useI18n();
  const describe = useErrorMessage();

  const grants = useGrants();
  const applications = useApplications();
  const providers = useProviders();
  const credentials = useCredentials();
  const updateGrant = useUpdateGrant();
  const deleteGrant = useDeleteGrant();
  const updateCredential = useUpdateCredential();
  const updateProvider = useUpdateProvider();
  const purgeCache = usePurgeProviderCache();
  const pingProvider = usePingProvider();
  const rotateKey = useRotateApplicationKey();

  const [panel, setPanel] = useState<'closed' | 'destination' | 'quota' | 'secret'>('closed');
  const [issuedKey, setIssuedKey] = useState('');
  const [error, setError] = useState('');

  const loading = grants.isPending || applications.isPending || providers.isPending || credentials.isPending;
  const connection = useMemo(
    () =>
      buildConnections(
        grants.data ?? [],
        applications.data ?? [],
        providers.data ?? [],
        credentials.data ?? [],
      ).find((c) => c.id === id),
    [grants.data, applications.data, providers.data, credentials.data, id],
  );

  // Deleted from its own page, or opened from a link that no longer resolves. Navigating is an
  // effect, never something done while rendering.
  useEffect(() => {
    if (!loading && !connection) onBack();
  }, [loading, connection, onBack]);

  if (loading || !connection) return <PageSkeleton rows={5} cols={3} />;

  const { grant, provider, credential } = connection;
  const application = connection.application;
  const endpoint = `${gatewayUrl(provider?.slug ?? '')}/`;
  // Any path reaches the destination now, so the sample states the one nobody can get wrong and
  // leaves the reader to replace it with whatever the API actually exposes.
  const curl = curlFor(provider?.slug ?? '', '/', grant.applicationId, '$JANUS_API_KEY');

  /** Every grant write sends the whole record: the endpoint replaces, it does not patch. */
  const writeGrant = (changes: Partial<{ enabled: boolean; perMinute: number; burst: number }>) =>
    updateGrant.mutateAsync({
      id: grant.id,
      input: {
        applicationId: grant.applicationId,
        providerId: grant.providerId,
        credentialId: grant.credentialId,
        enabled: changes.enabled ?? grant.enabled,
        rateLimitPerMinute: changes.perMinute ?? grant.rateLimitPerMinute,
        rateLimitBurst: changes.burst ?? grant.rateLimitBurst,
      },
    });

  async function guard(run: () => Promise<unknown>) {
    setError('');
    try {
      await run();
    } catch (x) {
      setError(describe(x));
    }
  }

  return (
    <>
      {/*
       * A record opened from a list keeps the shape of the list it came from: the section line
       * becomes the way back, the status takes the slot a collection gives its primary button, and
       * the title lands on the same pixel it did a click earlier.
       */}
      <PageHead
        back={{ label: t('dashboard.title'), onClick: onBack }}
        title={
          <>
            {grant.applicationName}
            <span className="sr-only"> {t('connections.reaches')} </span>
            <span aria-hidden="true" className="mx-2.5 font-normal text-text-3">
              &rarr;
            </span>
            {grant.providerName}
          </>
        }
        intro={t('detail.lead')}
        action={<LiveState live={connection.live} paused={connection.blockedBy === 'grant'} />}
      />

      <Diagnosis
        connection={connection}
        onFix={onFix}
        onFixDestination={identity.role === 'USER' ? undefined : () => setPanel('destination')}
      />

      {error && <Notice>{error}</Notice>}

      <div className="space-y-8">
        <Block title={t('detail.callTitle')} lead={t('detail.callLead')}>
          <div className="space-y-4">
            <CopyField label={t('detail.endpoint')} value={endpoint} />
            <CopyField label={t('detail.identifier')} value={grant.applicationId} />
            <CopyField label={t('detail.example')} value={curl} block />
          </div>
        </Block>

        {provider && (
          <Block
            title={t('detail.destinationTitle')}
            lead={t('detail.destinationLead')}
            aside={
              <span className="flex items-center gap-1">
                {/* Asked, never watched: a probe leaves the deployment, so it happens when somebody
                    wants to know and not on a timer behind an open console. */}
                {identity.role !== 'USER' && (
                  <button
                    className="btn btn-sm btn-secondary"
                    disabled={pingProvider.isPending}
                    /* The row states the verdict in two words; a probe that could not even be sent
                       says why in the page's own notice, rather than reading as a silent API. */
                    onClick={() => guard(() => pingProvider.mutateAsync(provider.id))}
                  >
                    {pingProvider.isPending ? t('providers.pinging') : t('providers.ping')}
                  </button>
                )}
                {identity.role !== 'USER' && provider.cacheEnabled && (
                  <ConfirmAction
                    trigger={t('providers.purge')}
                    confirm={t('providers.purgeConfirm')}
                    pending={t('providers.purging')}
                    description={t('providers.purgeDescription', { name: provider.name })}
                    onConfirm={() => guard(() => purgeCache.mutateAsync(provider.id))}
                  />
                )}
                {identity.role !== 'USER' && (
                  <button className="btn btn-sm btn-secondary" onClick={() => setPanel('destination')}>
                    {t('detail.destinationEdit')}
                  </button>
                )}
              </span>
            }
          >
            <dl className="panel divide-y divide-line text-sm">
              <div className="flex flex-wrap items-baseline justify-between gap-4 px-4 py-3">
                <dt className="text-text-2">{t('providers.fieldBaseUrl')}</dt>
                <dd className="data break-all text-right">{provider.baseUrl}</dd>
              </div>
              <div className="flex flex-wrap items-baseline justify-between gap-4 px-4 py-3">
                <dt className="text-text-2">{t('providers.cacheLabel')}</dt>
                <dd className={provider.cacheEnabled ? undefined : 'text-text-3'}>
                  {!provider.cacheEnabled
                    ? t('providers.cacheOff')
                    : provider.cacheTtlSeconds > 0
                      ? t('providers.cacheTtl', { seconds: provider.cacheTtlSeconds })
                      : t('providers.cacheUpstream')}
                </dd>
              </div>
              {identity.role !== 'USER' && (
                <div className="flex flex-wrap items-baseline justify-between gap-4 px-4 py-3">
                  <dt className="text-text-2">{t('providers.pingLabel')}</dt>
                  <dd>
                    {/* A probe that could not be sent is the same news as one that came back with
                        nothing: the destination is not answering this console. */}
                    <PingState
                      result={pingProvider.data}
                      pending={pingProvider.isPending}
                      failed={pingProvider.isError}
                    />
                  </dd>
                </div>
              )}
              <div className="flex flex-wrap items-baseline justify-between gap-4 px-4 py-3">
                <dt className="text-text-2">{t('providers.rateLimitLabel')}</dt>
                <dd className={provider.rateLimitPerMinute > 0 ? 'data' : 'text-text-3'}>
                  {provider.rateLimitPerMinute > 0
                    ? t('providers.rateLimitValue', { count: provider.rateLimitPerMinute })
                    : t('providers.rateLimitNone')}
                </dd>
              </div>
            </dl>
          </Block>
        )}

        <Block
          title={t('detail.quotaTitle')}
          lead={t('detail.quotaLead')}
          aside={
            <button className="btn btn-sm btn-secondary" onClick={() => setPanel('quota')}>
              {t('detail.quotaEdit')}
            </button>
          }
        >
          <dl className="panel flex flex-wrap items-baseline justify-between gap-4 px-4 py-3 text-sm">
            <dt className="text-text-2">{t('detail.quotaLabel')}</dt>
            <dd className={grant.rateLimitPerMinute > 0 ? 'data font-medium' : 'text-text-3'}>
              {grant.rateLimitPerMinute > 0
                ? t('detail.quotaValue', { count: grant.rateLimitPerMinute })
                : t('detail.quotaNone')}
            </dd>
          </dl>
        </Block>

        {/* An open API has nothing held for it, so there is nothing to replace and no date to watch.
            The block stays, because "what does Janus present here" is still worth an answer. */}
        {credential && credential.authType === 'NONE' && (
          <Block title={t('detail.openTitle')} lead={t('detail.openLead')} />
        )}

        {credential && credential.authType !== 'NONE' && (
          <Block
            title={t('detail.secretTitle')}
            lead={t('detail.secretLead', {
              ref: credential.secretRef ?? '',
              mode: tEnum('authType', credential.authType),
            })}
            aside={
              <button className="btn btn-sm btn-secondary" onClick={() => setPanel('secret')}>
                {t('detail.secretReplace')}
              </button>
            }
          >
            <dl className="panel flex flex-wrap items-baseline justify-between gap-4 px-4 py-3 text-sm">
              <dt className="text-text-2">{t('expiry.label')}</dt>
              <dd>
                <ExpiryState expiresAt={credential.expiresAt} />
              </dd>
            </dl>
          </Block>
        )}

        {/* The one thing on this page that an administrator cannot do by editing a field: somebody
            has to agree, at the provider, in their own browser. So it gets its own block rather than
            a line in the one above, and it says which of the two states it is in. */}
        {credential && credential.authType === 'OAUTH2_AUTHORIZATION_CODE' && (
          <AuthorizationBlock credential={credential} />
        )}

        {application && (
          <Block title={t('detail.keyTitle')} lead={t('detail.keyNote')}>
            <div className="panel flex flex-wrap items-center justify-between gap-4 px-4 py-3">
              <p className="text-sm text-text-2" title={formatDate(application.apiKeyRotatedAt)}>
                {t('detail.keyIssued', { age: formatAge(application.apiKeyRotatedAt) })}
              </p>
              <ConfirmAction
                trigger={t('detail.keyRotate')}
                confirm={t('detail.keyRotateConfirm')}
                pending={t('detail.keyRotating')}
                description={t('detail.keyRotateDescription', { name: application.name })}
                onConfirm={() =>
                  guard(async () => {
                    const rotated = await rotateKey.mutateAsync(application.id);
                    setIssuedKey(rotated.apiKey);
                  })
                }
              />
            </div>
          </Block>
        )}

        <Block title={t('detail.stopTitle')}>
          <div className="panel flex flex-wrap items-center justify-between gap-4 px-4 py-3">
            <p className="max-w-[64ch] text-sm text-text-2">
              {grant.enabled ? t('detail.pauseDescription') : t('detail.resumeDescription')}
            </p>
            <span className="flex items-center gap-1">
              <ConfirmAction
                trigger={grant.enabled ? t('detail.pause') : t('detail.resume')}
                confirm={grant.enabled ? t('detail.pauseConfirm') : t('detail.resumeConfirm')}
                pending={grant.enabled ? t('detail.pausing') : t('detail.resuming')}
                description={grant.enabled ? t('detail.pauseDescription') : t('detail.resumeDescription')}
                prominent={!grant.enabled}
                onConfirm={() => guard(() => writeGrant({ enabled: !grant.enabled }))}
              />
              <DeleteAction
                label={t('detail.removeLabel', { app: grant.applicationName, api: grant.providerName })}
                consequence={t('detail.removeDescription')}
                onDelete={() =>
                  guard(async () => {
                    await deleteGrant.mutateAsync(grant.id);
                    onBack();
                  })
                }
              />
            </span>
          </div>
        </Block>
      </div>

      {panel === 'destination' && provider && identity.role !== 'USER' && (
        <DestinationPanel
          provider={provider}
          onClose={() => setPanel('closed')}
          onSave={async (input) => {
            await updateProvider.mutateAsync({ id: provider.id, input });
            setPanel('closed');
          }}
        />
      )}
      {panel === 'quota' && (
        <QuotaPanel
          grant={grant}
          onClose={() => setPanel('closed')}
          onSave={async (perMinute, burst) => {
            await writeGrant({ perMinute, burst });
            setPanel('closed');
          }}
        />
      )}
      {panel === 'secret' && credential && (
        <SecretPanel
          onClose={() => setPanel('closed')}
          onSave={async (secret) => {
            await updateCredential.mutateAsync({
              id: credential.id,
              input: {
                name: credential.name,
                providerId: credential.providerId,
                authType: credential.authType,
                headerName: credential.headerName ?? null,
                secret,
                expiresAt: credential.expiresAt ?? null,
                enabled: credential.enabled,
              },
            });
            setPanel('closed');
          }}
        />
      )}
      {issuedKey && <KeyIssued value={issuedKey} onDismiss={() => setIssuedKey('')} />}
    </>
  );
}

/**
 * Why nothing is being forwarded, named. "Inactive" is what four separate tables can say; which of
 * the four is inactive is what an operator actually needs.
 */
function Diagnosis({
  connection,
  onFix,
  onFixDestination,
}: {
  connection: Connection;
  onFix: (to: 'applications' | 'credentials') => void;
  /** The destination is edited here rather than elsewhere, so its fix opens a panel instead. */
  onFixDestination?: () => void;
}) {
  const { t } = useI18n();
  const { grant, blockedBy } = connection;

  // A forwarding connection says so in the status beside its title. Only a fault needs a paragraph.
  if (!blockedBy) return null;

  const name =
    blockedBy === 'application'
      ? grant.applicationName
      : blockedBy === 'provider'
        ? grant.providerName
        : grant.credentialName;
  const message =
    blockedBy === 'grant'
      ? t('detail.blockedGrant')
      : blockedBy === 'application'
        ? t('detail.blockedApplication', { name })
        : blockedBy === 'provider'
          ? t('detail.blockedProvider', { name })
          : t('detail.blockedCredential', { name });

  return (
    <div className="mb-7 flex flex-wrap items-center gap-x-4 gap-y-2 rounded-panel border border-warn/45 bg-warn-wash px-3.5 py-3">
      <AlertTriangle size={16} strokeWidth={2.25} className="shrink-0 text-warn" />
      <p className="min-w-0 flex-1 text-sm">{message}</p>
      {blockedBy !== 'grant' && (blockedBy !== 'provider' || onFixDestination) && (
        <button
          className="btn btn-sm btn-secondary"
          onClick={() => {
            if (blockedBy === 'provider') onFixDestination?.();
            else onFix(FIX_TARGET[blockedBy]);
          }}
        >
          {t('detail.fix')}
        </button>
      )}
    </div>
  );
}

/* ── Editing, one question at a time ───────────────────────────────────── */

/**
 * The destination itself, edited where it is read. A registered API used to be a row in a registry
 * table one click away from the connection that is the only reason it exists; there is no second
 * place to keep in sync now.
 */
function DestinationPanel({
  provider,
  onClose,
  onSave,
}: {
  provider: Provider;
  onClose: () => void;
  onSave: (input: {
    name: string;
    slug: string;
    baseUrl: string;
    enabled: boolean;
    cacheEnabled: boolean;
    cacheTtlSeconds: number;
    rateLimitPerMinute: number;
    rateLimitBurst: number;
    authType: Provider['authType'];
    headerName?: string;
    queryParameter?: string;
    tokenUrl?: string;
    tokenScopes?: string;
    tokenClientAuth?: Provider['tokenClientAuth'];
  }) => Promise<void>;
}) {
  const { t } = useI18n();
  const describe = useErrorMessage();
  const [error, setError] = useState('');

  async function submit(e: FormEvent<HTMLFormElement>) {
    const form = new FormData(e.currentTarget);
    setError('');
    try {
      await onSave({
        name: String(form.get('name') ?? ''),
        slug: String(form.get('slug') ?? ''),
        baseUrl: String(form.get('baseUrl') ?? ''),
        enabled: form.get('enabled') === 'on',
        cacheEnabled: form.get('cacheEnabled') === 'on',
        cacheTtlSeconds: Number(form.get('cacheTtlSeconds') || 0),
        rateLimitPerMinute: Number(form.get('rateLimitPerMinute') || 0),
        rateLimitBurst: Number(form.get('rateLimitBurst') || 0),
        authType: provider.authType,
        headerName: provider.headerName,
        queryParameter: provider.queryParameter,
        tokenUrl: provider.tokenUrl,
        tokenScopes: provider.tokenScopes,
        tokenClientAuth: provider.tokenClientAuth,
      });
    } catch (x) {
      setError(describe(x));
    }
  }

  return (
    <SidePanel title={t('detail.destinationEdit')} intro={t('providers.panelIntro')} onClose={onClose}>
      <FormLayout onSubmit={submit} submitLabel={t('common.saveChanges')} error={error}>
        <Field label={t('providers.fieldName')} name="name" required autoComplete="off" defaultValue={provider.name} />
        <Field
          label={t('providers.fieldSlug')}
          name="slug"
          required
          data
          autoComplete="off"
          defaultValue={provider.slug}
          hint={t('providers.slugHint')}
        />
        <Field
          label={t('providers.fieldBaseUrl')}
          name="baseUrl"
          type="url"
          required
          data
          autoComplete="off"
          defaultValue={provider.baseUrl}
        />
        <CheckField
          label={t('providers.enabledLabel')}
          name="enabled"
          defaultChecked={provider.enabled}
          hint={t('providers.enabledHint')}
        />

        <div className="space-y-5 border-t border-line pt-5">
          <div>
            <p className="stamp text-text-2">{t('providers.policySection')}</p>
            <p className="mt-1.5 text-xs text-text-2">{t('providers.policyIntro')}</p>
          </div>
          <CheckField
            label={t('providers.cacheLabel')}
            name="cacheEnabled"
            defaultChecked={provider.cacheEnabled}
            hint={t('providers.cacheHint')}
          />
          <Field
            label={t('providers.cacheTtlLabel')}
            name="cacheTtlSeconds"
            type="number"
            min={0}
            max={86400}
            data
            defaultValue={provider.cacheTtlSeconds}
            hint={t('providers.cacheTtlHint')}
          />
          <Field
            label={t('providers.rateLimitLabel')}
            name="rateLimitPerMinute"
            type="number"
            min={0}
            max={1000000}
            data
            defaultValue={provider.rateLimitPerMinute}
            hint={t('providers.rateLimitHint')}
          />
          <Field
            label={t('providers.burstLabel')}
            name="rateLimitBurst"
            type="number"
            min={0}
            max={100000}
            data
            defaultValue={provider.rateLimitBurst}
            hint={t('providers.burstHint')}
          />
        </div>
      </FormLayout>
    </SidePanel>
  );
}

function QuotaPanel({
  grant,
  onClose,
  onSave,
}: {
  grant: Grant;
  onClose: () => void;
  onSave: (perMinute: number, burst: number) => Promise<void>;
}) {
  const { t } = useI18n();
  const describe = useErrorMessage();
  const [perMinute, setPerMinute] = useState(grant.rateLimitPerMinute);
  const [burst, setBurst] = useState(grant.rateLimitBurst);
  const [error, setError] = useState('');

  async function submit(_e: FormEvent<HTMLFormElement>) {
    setError('');
    try {
      await onSave(perMinute, burst);
    } catch (x) {
      setError(describe(x));
    }
  }

  return (
    <SidePanel title={t('detail.quotaEdit')} intro={t('detail.quotaLead')} onClose={onClose}>
      <FormLayout onSubmit={submit} submitLabel={t('common.saveChanges')} error={error}>
        <Field
          label={t('detail.quotaLabel')}
          type="number"
          min={0}
          max={1000000}
          data
          value={perMinute}
          onChange={(e) => setPerMinute(Number(e.target.value || 0))}
          hint={t('detail.quotaHint')}
        />
        <Field
          label={t('detail.burstLabel')}
          type="number"
          min={0}
          max={100000}
          data
          value={burst}
          onChange={(e) => setBurst(Number(e.target.value || 0))}
          hint={t('detail.burstHint')}
        />
      </FormLayout>
    </SidePanel>
  );
}

function SecretPanel({ onClose, onSave }: { onClose: () => void; onSave: (secret: string) => Promise<void> }) {
  const { t } = useI18n();
  const describe = useErrorMessage();
  const [secret, setSecret] = useState('');
  const [error, setError] = useState('');

  async function submit(_e: FormEvent<HTMLFormElement>) {
    setError('');
    try {
      await onSave(secret);
    } catch (x) {
      setError(describe(x));
    }
  }

  return (
    <SidePanel title={t('detail.secretReplace')} onClose={onClose}>
      <FormLayout onSubmit={submit} submitLabel={t('detail.secretSave')} submitDisabled={secret === ''} error={error}>
        <Field
          label={t('detail.secretNew')}
          type="password"
          required
          autoComplete="new-password"
          value={secret}
          onChange={(e) => setSecret(e.target.value)}
          hint={t('detail.secretNewHint')}
        />
      </FormLayout>
    </SidePanel>
  );
}

/**
 * Consent: whether somebody has agreed at the provider, and the one action that changes it.
 *
 * Everything else about a connection is settled by an administrator filling in a field. This is not,
 * and the block says so plainly rather than reporting "disabled" and leaving a reader to work out
 * that the fix is a person rather than a value. Until consent exists there is one button; afterwards
 * there is who it belongs to, and the way to withdraw it.
 */
function AuthorizationBlock({ credential }: { credential: Credential }) {
  const { t, formatAge } = useI18n();
  const describe = useErrorMessage();
  const client = useQueryClient();
  const callback = useOAuthCallback();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function authorize() {
    setBusy(true);
    setError('');
    try {
      // A full navigation rather than a popup: the provider decides what its consent screen looks
      // like, several refuse to be framed, and a browser that blocks popups would fail silently.
      const started = await post<{ authorizationUrl: string }>(
        `/credentials/${credential.id}/authorization`,
        {},
      );
      window.location.assign(started.authorizationUrl);
    } catch (x) {
      setError(describe(x));
      setBusy(false);
    }
  }

  async function revoke() {
    await del(`/credentials/${credential.id}/authorization`);
    await client.invalidateQueries({ queryKey: keys.credentials });
  }

  return (
    <Block
      title={t('detail.consentTitle')}
      lead={credential.awaitingAuthorization ? t('detail.consentLeadPending') : t('detail.consentLeadGiven')}
      aside={
        credential.awaitingAuthorization ? (
          <button className="btn btn-sm btn-primary" onClick={authorize} disabled={busy}>
            {busy ? t('common.working') : t('detail.consentConnect')}
          </button>
        ) : (
          <ConfirmAction
            trigger={t('detail.consentRevoke')}
            confirm={t('detail.consentRevokeConfirm')}
            pending={t('common.working')}
            description={t('detail.consentRevokeDescription')}
            onConfirm={revoke}
          />
        )
      }
    >
      {/* Before the first consent, the redirect is the thing most likely to be missing at the
          provider's end, and the refusal it causes names nothing. Shown while it is still useful. */}
      {credential.awaitingAuthorization && callback.data && (
        <div className="space-y-2">
          <CopyField label={t('connect.callbackLabel')} value={callback.data.url} />
          <p className="text-xs text-text-2">{t('connect.callbackHint')}</p>
        </div>
      )}
      {!credential.awaitingAuthorization && (
        <dl className="panel flex flex-wrap items-baseline justify-between gap-4 px-4 py-3 text-sm">
          <dt className="text-text-2">{t('detail.consentHolder')}</dt>
          <dd className="data">
            {credential.authorizedSubject ?? t('detail.consentHolderUnknown')}
            {credential.authorizedAt && (
              <span className="ml-2 text-xs text-text-2">
                {t('detail.consentSince', { age: formatAge(credential.authorizedAt) })}
              </span>
            )}
          </dd>
        </dl>
      )}
      {error && (
        <p role="alert" className="mt-3 rounded-panel border border-bad/40 bg-bad-wash px-3.5 py-3 text-sm">
          {error}
        </p>
      )}
    </Block>
  );
}
