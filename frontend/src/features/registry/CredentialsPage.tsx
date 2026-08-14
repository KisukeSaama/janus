import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { Plus } from 'lucide-react';

import {
  useCreateCredential,
  useCredentials,
  useDeleteCredential,
  useDeleteProvider,
  usePingProvider,
  useProviderCapabilities,
  useProviderCatalog,
  useUpdateCredential,
  useUpdateProvider,
  type AuthType,
  type Credential,
  type Identity,
  type Provider,
  type TokenClientAuth,
} from '../../api';
import {
  CheckField,
  DataTable,
  Empty,
  EnabledState,
  ExpiryState,
  Field,
  FormLayout,
  Notice,
  PageHead,
  Pager,
  PingState,
  RecordCell,
  RowMenu,
  SearchField,
  SelectField,
  SidePanel,
  SkeletonRows,
  type Column,
  type RowAction,
} from '../../components';
import { useLocation } from '../../app/routes';
import { useI18n } from '../../i18n';
import { useErrorMessage } from '../../lib/errors';
import { fromDateInput, NOTICE_DAYS, toDateInput, WARNING_DAYS } from '../../lib/expiry';
import { ConnectFlow } from '../connections/ConnectFlow';

const STRATEGIES: AuthType[] = [
  'BEARER',
  'API_KEY_HEADER',
  'API_KEY_QUERY',
  'BASIC',
  'OAUTH2_CLIENT_CREDENTIALS',
  'NONE',
];

type Panel =
  | { kind: 'activate'; provider: Provider }
  | { kind: 'credential'; credential: Credential; provider: Provider }
  // Editing only. Registering an API is the setup flow's job, here as on the console's home page,
  // so an operator never meets two different ways of adding the same record.
  | { kind: 'api'; provider: Provider }
  | null;

/** Shared API catalogue, with a personal activation and credential boundary layered onto each row. */
export function CredentialsPage({ identity }: { identity: Identity }) {
  const { t, tEnum } = useI18n();
  const describe = useErrorMessage();
  const [, navigate] = useLocation();
  const capabilities = useProviderCapabilities();
  const administrator = identity.role !== 'USER';

  const [query, setQuery] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [panel, setPanel] = useState<Panel>(null);
  const [connecting, setConnecting] = useState(false);
  const [strategy, setStrategy] = useState<AuthType>('BEARER');
  // The array declaration only means anything while normalisation is on, so it follows the switch
  // rather than sitting there inert — and a saved form would have cleared it anyway.
  const [normalizing, setNormalizing] = useState(false);
  /** Whether this API also lets an account holder connect theirs, beside whatever it presents. */
  const [connectable, setConnectable] = useState(false);
  const [formError, setFormError] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setSearch(query.trim());
      setPage(0);
    }, 250);
    return () => window.clearTimeout(timer);
  }, [query]);

  const catalog = useProviderCatalog(search, page);
  const credentials = useCredentials();
  const createCredential = useCreateCredential();
  const updateCredential = useUpdateCredential();
  const deleteCredential = useDeleteCredential();
  const updateProvider = useUpdateProvider();
  const deleteProvider = useDeleteProvider();
  const pingProvider = usePingProvider();

  /**
   * The API the last probe was about. Kept beside the result rather than in the row, because a
   * catalogue of a hundred entries with a `Reachable` column would ask an operator to read ninety-nine
   * dashes to find the one they just tested — and would tempt the console into probing every row.
   */
  const [probed, setProbed] = useState<Provider | null>(null);

  const personal = useMemo(
    () => new Map((credentials.data ?? []).map((credential) => [credential.providerId, credential])),
    [credentials.data],
  );
  const rows = catalog.data?.content ?? [];

  function close() {
    setPanel(null);
    setFormError('');
  }

  function openApi(provider: Provider) {
    setStrategy(provider.authType);
    setNormalizing(provider.normalizeJson ?? false);
    setConnectable(Boolean(provider.connectionAuthorizationUrl));
    setPanel({ kind: 'api', provider });
  }

  /** The contract the API states, which a credential only ever repeats back. */
  function contract(provider: Provider) {
    return {
      providerId: provider.id,
      authType: provider.authType,
      headerName: provider.headerName ?? null,
      queryParameter: provider.queryParameter ?? null,
      tokenUrl: provider.tokenUrl ?? null,
      tokenScopes: provider.tokenScopes ?? null,
      tokenClientAuth: provider.tokenClientAuth ?? null,
    };
  }

  function credentialInput(provider: Provider, form: FormData) {
    const secret = String(form.get('secret') ?? '');
    return {
      name: provider.slug,
      ...contract(provider),
      secret: secret || null,
      expiresAt: fromDateInput(String(form.get('expiresAt') ?? '')),
      enabled: form.get('enabled') === 'on',
    };
  }

  /**
   * An open API holds no secret, so the only state its activation carries is on or off. That is one
   * click, not a panel whose single field is a checkbox.
   */
  function toggle(provider: Provider, credential: Credential) {
    return act(() =>
      updateCredential.mutateAsync({
        id: credential.id,
        input: {
          name: credential.name,
          ...contract(provider),
          secret: null,
          expiresAt: credential.expiresAt ?? null,
          enabled: !credential.enabled,
        },
      }),
    );
  }

  async function submitCredential(e: FormEvent<HTMLFormElement>) {
    const form = new FormData(e.currentTarget);
    if (!panel || (panel.kind !== 'activate' && panel.kind !== 'credential')) return;
    const provider = panel.provider;
    setFormError('');
    try {
      if (panel.kind === 'credential') {
        await updateCredential.mutateAsync({ id: panel.credential.id, input: credentialInput(provider, form) });
      } else {
        await createCredential.mutateAsync(credentialInput(provider, form));
      }
      close();
    } catch (x) {
      setFormError(describe(x));
    }
  }

  async function submitApi(e: FormEvent<HTMLFormElement>) {
    const form = new FormData(e.currentTarget);
    if (!panel || panel.kind !== 'api') return;
    const input = {
      name: String(form.get('name') ?? ''),
      slug: String(form.get('slug') ?? ''),
      baseUrl: String(form.get('baseUrl') ?? ''),
      enabled: form.get('enabled') === 'on',
      // Sent on every save. Omitting it read as no, so saving anything else on a local destination
      // unset it and the address it already carried was refused.
      allowPrivateDestination: form.get('allowPrivateDestination') === 'on',
      cacheEnabled: form.get('cacheEnabled') === 'on',
      cacheTtlSeconds: Number(form.get('cacheTtlSeconds') || 0),
      normalizeJson: form.get('normalizeJson') === 'on',
      jsonArrayPaths: String(form.get('jsonArrayPaths') ?? '') || null,
      rateLimitPerMinute: Number(form.get('rateLimitPerMinute') || 0),
      rateLimitBurst: Number(form.get('rateLimitBurst') || 0),
      authType: strategy,
      headerName: String(form.get('headerName') ?? '') || null,
      queryParameter: String(form.get('queryParameter') ?? '') || null,
      tokenUrl: String(form.get('tokenUrl') ?? '') || null,
      tokenScopes: String(form.get('tokenScopes') ?? '') || null,
      tokenClientAuth: (String(form.get('tokenClientAuth') ?? '') || null) as TokenClientAuth | null,
      // Cleared as a block when the box is unticked, so withdrawing a connection is one gesture
      // rather than three emptied fields the backend would refuse as half a flow.
      connectionAuthorizationUrl: connectable ? String(form.get('connectionAuthorizationUrl') ?? '') || null : null,
      connectionTokenUrl: connectable ? String(form.get('connectionTokenUrl') ?? '') || null : null,
      connectionScopes: connectable ? String(form.get('connectionScopes') ?? '') || null : null,
      connectionClientAuth: connectable
        ? ((String(form.get('connectionClientAuth') ?? '') || null) as TokenClientAuth | null)
        : null,
    };
    setFormError('');
    try {
      await updateProvider.mutateAsync({ id: panel.provider.id, input });
      close();
    } catch (x) {
      setFormError(describe(x));
    }
  }

  /** Row actions report into the page banner: the row they belonged to may no longer be there. */
  async function act(run: () => Promise<unknown>) {
    setError('');
    try {
      await run();
    } catch (x) {
      setError(describe(x));
    }
  }

  const columns: Column<Provider>[] = [
    {
      key: 'api',
      label: t('credentials.colProvider'),
      primary: true,
      grow: true,
      cell: (provider) => <RecordCell name={provider.name} note={provider.slug} mono />,
    },
    {
      key: 'auth',
      label: t('credentials.colStrategy'),
      nowrap: true,
      cell: (provider) => tEnum('authType', provider.authType),
    },
    {
      key: 'activation',
      label: t('credentials.activation'),
      badge: true,
      cell: (provider) =>
        personal.has(provider.id) ? <EnabledState enabled={personal.get(provider.id)!.enabled} /> : t('credentials.available'),
    },
    {
      key: 'expiry',
      label: t('credentials.colExpiry'),
      nowrap: true,
      cell: (provider) => {
        const credential = personal.get(provider.id);
        return credential ? <ExpiryState expiresAt={credential.expiresAt} /> : '—';
      },
    },
  ];

  const primary = administrator ? (
    <button className="btn btn-primary w-full sm:w-auto" onClick={() => setConnecting(true)}>
      <Plus size={15} strokeWidth={2.25} />
      {t('credentials.addApi')}
    </button>
  ) : undefined;

  return (
    <>
      <PageHead section={t('nav.registry')} title={t('credentials.title')} intro={t('credentials.catalogIntro')} action={primary} />
      {error && <Notice>{error}</Notice>}

      <SearchField
        value={query}
        onChange={setQuery}
        label={t('credentials.searchLabel')}
        placeholder={t('credentials.searchPlaceholder')}
      />

      {probed && (
        <div className="panel mb-4 flex flex-wrap items-center justify-between gap-3 px-4 py-3 text-sm">
          <span>{probed.name}</span>
          <PingState result={pingProvider.data} pending={pingProvider.isPending} failed={pingProvider.isError} />
        </div>
      )}

      {catalog.isError && <Notice>{describe(catalog.error)}</Notice>}
      {catalog.isPending || credentials.isPending ? (
        <SkeletonRows cols={4} />
      ) : rows.length === 0 ? (
        <Empty headline={t('credentials.noResults')} hint={t('credentials.noResultsHint')} />
      ) : (
        <>
          <DataTable
            columns={columns}
            rows={rows}
            rowKey={(provider) => provider.id}
            /*
             * One verb in the row, the one this account came to use, and the rest under a menu that
             * names whose records they touch. Both deletions used to sit here as buttons reading
             * `Delete`, side by side, one destroying a personal credential and the other the API for
             * every account in the deployment.
             */
            actions={(provider) => {
              const credential = personal.get(provider.id);
              const menu: RowAction[] = [];
              if (credential) {
                menu.push({
                  key: 'deactivate',
                  label: t('credentials.deactivate'),
                  group: t('credentials.groupAccount'),
                  destructive: true,
                  consequence: t('credentials.deactivateConsequence'),
                  confirm: t('common.confirmDelete'),
                  pending: t('common.deleting'),
                  onConfirm: () => act(() => deleteCredential.mutateAsync(credential.id)),
                });
              }
              if (administrator) {
                menu.push(
                  {
                    key: 'ping',
                    label: t('providers.ping'),
                    group: t('credentials.groupAdmin'),
                    onSelect: () => {
                      setProbed(provider);
                      // Reported twice on purpose: the verdict beside the name, and the reason a
                      // probe never left at all in the notice the rest of this page writes to.
                      void act(() => pingProvider.mutateAsync(provider.id));
                    },
                  },
                  {
                    key: 'edit',
                    label: t('credentials.editApi'),
                    group: t('credentials.groupAdmin'),
                    onSelect: () => openApi(provider),
                  },
                  {
                    key: 'delete',
                    label: t('credentials.deleteApi'),
                    group: t('credentials.groupAdmin'),
                    destructive: true,
                    consequence: `${t('credentials.deleteApi')} ${provider.name}. ${t('credentials.deleteGlobalConsequence')}`,
                    confirm: t('common.confirmDelete'),
                    pending: t('common.deleting'),
                    onConfirm: () => act(() => deleteProvider.mutateAsync(provider.id)),
                  },
                );
              }
              return (
                <>
                  {credential && provider.authType === 'NONE' ? (
                    <button className="btn btn-sm btn-quiet" onClick={() => toggle(provider, credential)}>
                      {credential.enabled ? t('credentials.turnOff') : t('credentials.turnOn')}
                      <span className="sr-only"> {provider.name}</span>
                    </button>
                  ) : credential ? (
                    <button
                      className="btn btn-sm btn-quiet"
                      onClick={() => setPanel({ kind: 'credential', credential, provider })}
                    >
                      {t('credentials.manageCredentials')}
                      <span className="sr-only"> {provider.name}</span>
                    </button>
                  ) : (
                    <button className="btn btn-sm btn-secondary" onClick={() => setPanel({ kind: 'activate', provider })}>
                      {t('credentials.activate')}
                      <span className="sr-only"> {provider.name}</span>
                    </button>
                  )}
                  <RowMenu label={provider.name} actions={menu} />
                </>
              );
            }}
          />
          <Pager
            page={catalog.data?.page ?? page}
            totalPages={catalog.data?.totalPages ?? 0}
            totalElements={catalog.data?.totalElements ?? 0}
            unit={t('pager.apis')}
            busy={catalog.isFetching}
            onPage={setPage}
          />
        </>
      )}

      {panel && (panel.kind === 'activate' || panel.kind === 'credential') && (
        <SidePanel
          title={panel.kind === 'activate' ? t('credentials.activateTitle') : t('credentials.credentialsTitle')}
          intro={t('credentials.personalIntro', { api: panel.provider.name })}
          onClose={close}
        >
          <FormLayout
            onSubmit={submitCredential}
            submitLabel={panel.kind === 'activate' ? t('credentials.activate') : t('common.saveChanges')}
            error={formError}
          >
            <div className="border-y border-line py-3 text-sm">
              <span className="text-text-2">{t('credentials.fieldStrategy')}</span>
              <span className="ml-2 font-medium">{tEnum('authType', panel.provider.authType)}</span>
            </div>
            {panel.provider.authType !== 'NONE' && (
              <>
                <Field
                  label={panel.kind === 'credential' ? t('credentials.replacementSecret') : t('credentials.fieldSecret')}
                  name="secret"
                  type="password"
                  required={panel.kind === 'activate'}
                  autoComplete="new-password"
                  placeholder={panel.provider.authType === 'BASIC' ? 'username:password' : panel.provider.authType === 'OAUTH2_CLIENT_CREDENTIALS' ? 'client_id:client_secret' : undefined}
                  hint={panel.kind === 'credential' ? t('credentials.replacementHint') : t('credentials.secretHint')}
                />
                <Field
                  label={t('expiry.field')}
                  name="expiresAt"
                  type="date"
                  data
                  defaultValue={toDateInput(panel.kind === 'credential' ? panel.credential.expiresAt : undefined)}
                  hint={t('expiry.fieldHint', { notice: NOTICE_DAYS, warning: WARNING_DAYS })}
                />
              </>
            )}
            <CheckField
              label={t('credentials.enabledLabel')}
              name="enabled"
              defaultChecked={panel.kind === 'credential' ? panel.credential.enabled : true}
              hint={t('credentials.enabledHint')}
            />
          </FormLayout>
        </SidePanel>
      )}

      {panel?.kind === 'api' && (
        <SidePanel title={t('credentials.editApi')} intro={t('credentials.adminIntro')} onClose={close}>
          <FormLayout onSubmit={submitApi} submitLabel={t('common.saveChanges')} error={formError}>
            <Field label={t('providers.fieldName')} name="name" required defaultValue={panel.provider.name} />
            <Field label={t('providers.fieldSlug')} name="slug" required data defaultValue={panel.provider.slug} />
            <Field label={t('providers.fieldBaseUrl')} name="baseUrl" required data defaultValue={panel.provider.baseUrl} />
            {/*
              Shown when the deployment offers it, and also whenever this destination already carries
              it — a deployment that withdraws the option must not leave a field the form silently
              unsets.
            */}
            {(capabilities.data?.privateDestinations || panel.provider.allowPrivateDestination) && (
              <CheckField
                label={t('connect.lan')}
                name="allowPrivateDestination"
                defaultChecked={panel.provider.allowPrivateDestination}
                hint={t('connect.lanHint')}
              />
            )}
            <SelectField
              label={t('credentials.fieldStrategy')}
              name="authType"
              value={strategy}
              onChange={(event) => setStrategy(event.target.value as AuthType)}
              options={STRATEGIES.map((value) => ({ value, label: tEnum('authType', value) }))}
            />
            {strategy === 'API_KEY_HEADER' && <Field label={t('credentials.fieldHeader')} name="headerName" required data defaultValue={panel.provider.headerName} />}
            {strategy === 'API_KEY_QUERY' && <Field label={t('credentials.fieldQueryParameter')} name="queryParameter" required data defaultValue={panel.provider.queryParameter} />}
            {strategy === 'OAUTH2_CLIENT_CREDENTIALS' && (
              <>
                <Field label={t('credentials.fieldTokenUrl')} name="tokenUrl" required data defaultValue={panel.provider.tokenUrl} />
                <Field label={t('credentials.fieldTokenScopes')} name="tokenScopes" data defaultValue={panel.provider.tokenScopes} />
                <SelectField label={t('credentials.fieldTokenClientAuth')} name="tokenClientAuth" defaultValue={panel.provider.tokenClientAuth ?? 'BASIC'} options={[{ value: 'BASIC', label: t('credentials.tokenClientAuthBasic') }, { value: 'POST', label: t('credentials.tokenClientAuthPost') }]} />
              </>
            )}
            <CheckField
              label={t('providers.connectionLabel')}
              name="connectable"
              hint={t('providers.connectionHint')}
              checked={connectable}
              onChange={(event) => setConnectable(event.target.checked)}
            />
            {connectable && (
              <>
                <Field label={t('credentials.authorizationUrl')} name="connectionAuthorizationUrl" required data defaultValue={panel.provider.connectionAuthorizationUrl} hint={t('credentials.authorizationUrlHint')} />
                <Field label={t('credentials.fieldTokenUrl')} name="connectionTokenUrl" required data defaultValue={panel.provider.connectionTokenUrl} />
                <Field label={t('credentials.fieldTokenScopes')} name="connectionScopes" data defaultValue={panel.provider.connectionScopes} hint={t('credentials.tokenScopesHintUser')} />
                <SelectField label={t('credentials.fieldTokenClientAuth')} name="connectionClientAuth" defaultValue={panel.provider.connectionClientAuth ?? 'BASIC'} options={[{ value: 'BASIC', label: t('credentials.tokenClientAuthBasic') }, { value: 'POST', label: t('credentials.tokenClientAuthPost') }]} />
              </>
            )}
            <CheckField label={t('providers.cacheLabel')} name="cacheEnabled" defaultChecked={panel.provider.cacheEnabled ?? true} />
            <Field label={t('providers.cacheTtlLabel')} name="cacheTtlSeconds" type="number" min={0} defaultValue={panel.provider.cacheTtlSeconds ?? 0} />
            <CheckField label={t('providers.normalizeLabel')} name="normalizeJson" defaultChecked={panel.provider.normalizeJson ?? false} onChange={(e) => setNormalizing(e.currentTarget.checked)} hint={t('providers.normalizeHint')} />
            {normalizing && (
              <Field label={t('providers.arrayPathsLabel')} name="jsonArrayPaths" data autoComplete="off" maxLength={1000} placeholder="MediaContainer.Directory, Location" defaultValue={panel.provider.jsonArrayPaths ?? ''} hint={t('providers.arrayPathsHint')} />
            )}
            <Field label={t('providers.rateLimitLabel')} name="rateLimitPerMinute" type="number" min={0} defaultValue={panel.provider.rateLimitPerMinute ?? 0} />
            <Field label={t('providers.burstLabel')} name="rateLimitBurst" type="number" min={0} defaultValue={panel.provider.rateLimitBurst ?? 0} />
            <CheckField label={t('providers.enabledLabel')} name="enabled" defaultChecked={panel.provider.enabled ?? true} />
          </FormLayout>
        </SidePanel>
      )}

      {connecting && (
        <ConnectFlow
          onClose={() => setConnecting(false)}
          // Raised from this page, so closing it uncovers this page again. The wordmark is the one
          // exit that leaves, and it says where it leads before it is taken.
          onHome={() => {
            setConnecting(false);
            navigate({ page: 'dashboard' });
          }}
          onDone={() => setConnecting(false)}
        />
      )}
    </>
  );
}
