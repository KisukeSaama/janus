import { useState, type FormEvent } from 'react';
import { Plus } from 'lucide-react';

import {
  useCreateCredential,
  useCredentials,
  useDeleteCredential,
  useProviders,
  useUpdateCredential,
  type AuthType,
  type Credential,
  type TokenClientAuth,
} from '../../api';
import {
  CheckField,
  DataTable,
  DeleteAction,
  Empty,
  EnabledState,
  ExpiryState,
  Field,
  FormLayout,
  Notice,
  PageHead,
  RecordCell,
  SelectField,
  SidePanel,
  SkeletonRows,
  type Column,
} from '../../components';
import { useI18n } from '../../i18n';
import { useErrorMessage } from '../../lib/errors';
import { fromDateInput, NOTICE_DAYS, toDateInput, WARNING_DAYS } from '../../lib/expiry';

const STRATEGY_HINT: Record<
  string,
  | 'credentials.hintBearer'
  | 'credentials.hintApiKeyHeader'
  | 'credentials.hintApiKeyQuery'
  | 'credentials.hintBasic'
  | 'credentials.hintOauth2'
  | 'credentials.hintNone'
> = {
  BEARER: 'credentials.hintBearer',
  API_KEY_HEADER: 'credentials.hintApiKeyHeader',
  API_KEY_QUERY: 'credentials.hintApiKeyQuery',
  BASIC: 'credentials.hintBasic',
  OAUTH2_CLIENT_CREDENTIALS: 'credentials.hintOauth2',
  NONE: 'credentials.hintNone',
};

/** Every strategy Janus can present, in the order a reader is likely to need them. */
const STRATEGIES: AuthType[] = [
  'BEARER',
  'API_KEY_HEADER',
  'API_KEY_QUERY',
  'BASIC',
  'OAUTH2_CLIENT_CREDENTIALS',
  'NONE',
];

/** What the secret field is asking for, which differs for the two strategies holding two values. */
const SECRET_PLACEHOLDER: Partial<Record<AuthType, string>> = {
  BASIC: 'username:password',
  OAUTH2_CLIENT_CREDENTIALS: 'client_id:client_secret',
};

/** Secret metadata. The value itself only ever travels one way, into OpenBao. */
export function CredentialsPage() {
  const { t, tEnum } = useI18n();
  const describe = useErrorMessage();

  const credentials = useCredentials();
  const providers = useProviders();
  const create = useCreateCredential();
  const update = useUpdateCredential();
  const remove = useDeleteCredential();

  const [panel, setPanel] = useState<'closed' | 'new' | Credential>('closed');
  const [strategy, setStrategy] = useState<AuthType>('BEARER');
  const [formError, setFormError] = useState('');
  const [error, setError] = useState('');

  const rows = credentials.data ?? [];
  const destinations = providers.data ?? [];
  const editing = typeof panel === 'object' ? panel : null;

  function open(credential: Credential | 'new') {
    setStrategy(credential === 'new' ? 'BEARER' : credential.authType);
    setPanel(credential);
  }

  function close() {
    setPanel('closed');
    setFormError('');
  }

  async function submit(e: FormEvent<HTMLFormElement>) {
    const form = new FormData(e.currentTarget);
    const secret = String(form.get('secret') ?? '');
    const input = {
      name: String(form.get('name') ?? ''),
      providerId: String(form.get('providerId') ?? ''),
      authType: String(form.get('authType') ?? 'BEARER') as AuthType,
      headerName: String(form.get('headerName') ?? '') || null,
      queryParameter: String(form.get('queryParameter') ?? '') || null,
      tokenUrl: String(form.get('tokenUrl') ?? '') || null,
      tokenScopes: String(form.get('tokenScopes') ?? '') || null,
      tokenClientAuth: (String(form.get('tokenClientAuth') ?? '') || null) as TokenClientAuth | null,
      // On edit an empty field means "keep the stored secret"; it is never sent back to the browser.
      secret: secret === '' ? null : secret,
      // An empty date field means "no known end", and clears a deadline recorded before.
      expiresAt: fromDateInput(String(form.get('expiresAt') ?? '')),
      enabled: form.get('enabled') === 'on',
    };
    setFormError('');
    try {
      if (editing) await update.mutateAsync({ id: editing.id, input });
      else await create.mutateAsync(input);
      close();
    } catch (x) {
      setFormError(describe(x));
    }
  }

  const newButton = (
    <button className="btn btn-primary w-full sm:w-auto" onClick={() => open('new')} disabled={destinations.length === 0}>
      <Plus size={15} strokeWidth={2.25} />
      {t('credentials.new')}
    </button>
  );

  const columns: Column<Credential>[] = [
    {
      // The API leads, because that is what the reader came looking for: the record is only ever
      // "the secret I hold for Spotify", never a secret with a life of its own.
      key: 'provider',
      label: t('credentials.colProvider'),
      primary: true,
      grow: true,
      cell: (r) => <RecordCell name={r.providerName} note={r.name} mono />,
    },
    {
      key: 'strategy',
      label: t('credentials.colStrategy'),
      nowrap: true,
      cell: (r) => (
        <>
          <span>{tEnum('authType', r.authType)}</span>
          {r.headerName && <span className="data ml-2 text-text-3">{r.headerName}</span>}
        </>
      ),
    },
    { key: 'expiry', label: t('credentials.colExpiry'), cell: (r) => <ExpiryState expiresAt={r.expiresAt} /> },
    {
      key: 'state',
      label: t('state.label'),
      badge: true,
      nowrap: true,
      cell: (r) => <EnabledState enabled={r.enabled} />,
    },
  ];

  return (
    <>
      <PageHead
        section={t('nav.registry')}
        title={t('credentials.title')}
        intro={t('credentials.intro')}
        action={newButton}
      />
      {error && <Notice>{error}</Notice>}
      {credentials.isError && <Notice>{describe(credentials.error)}</Notice>}

      {credentials.isPending ? (
        <SkeletonRows cols={5} />
      ) : rows.length === 0 ? (
        <Empty
          headline={t('credentials.emptyTitle')}
          hint={destinations.length === 0 ? t('credentials.emptyHintNoProvider') : t('credentials.emptyHint')}
          action={destinations.length > 0 ? newButton : undefined}
        />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(r) => r.id}
          actions={(r) => (
            <span className="flex flex-wrap items-center justify-end gap-1">
              <button className="btn btn-sm btn-quiet" onClick={() => open(r)}>
                {t('common.edit')}
                <span className="sr-only"> {r.name}</span>
              </button>
              <DeleteAction
                label={r.name}
                consequence={t('credentials.deleteConsequence')}
                onDelete={async () => {
                  setError('');
                  try {
                    await remove.mutateAsync(r.id);
                  } catch (x) {
                    setError(describe(x));
                  }
                }}
              />
            </span>
          )}
        />
      )}

      {panel !== 'closed' && (
        <SidePanel
          title={editing ? t('credentials.editTitle') : t('credentials.panelTitle')}
          intro={editing ? t('credentials.editIntro') : t('credentials.panelIntro')}
          onClose={close}
        >
          <FormLayout
            onSubmit={submit}
            submitLabel={
              editing
                ? t('common.saveChanges')
                : strategy === 'NONE'
                  ? t('credentials.submitOpen')
                  : t('credentials.submit')
            }
            error={formError}
          >
            <Field
              label={t('credentials.fieldName')}
              name="name"
              required
              autoComplete="off"
              placeholder="payments-live"
              defaultValue={editing?.name}
            />
            <SelectField
              label={t('credentials.colProvider')}
              name="providerId"
              defaultValue={editing?.providerId}
              disabled={!!editing}
              options={destinations.map((p) => ({ value: p.id, label: p.name }))}
            />
            {/* A disabled select submits nothing, so the value still has to reach the request. */}
            {editing && <input type="hidden" name="providerId" value={editing.providerId} />}
            <SelectField
              label={t('credentials.fieldStrategy')}
              name="authType"
              value={strategy}
              onChange={(e) => setStrategy(e.target.value as AuthType)}
              options={STRATEGIES.map((v) => ({ value: v, label: tEnum('authType', v) }))}
              hint={t(STRATEGY_HINT[strategy])}
            />
            {strategy === 'API_KEY_HEADER' && (
              <Field
                label={t('credentials.fieldHeader')}
                name="headerName"
                required
                data
                autoComplete="off"
                placeholder="X-Api-Key"
                defaultValue={editing?.headerName}
              />
            )}
            {strategy === 'API_KEY_QUERY' && (
              <Field
                label={t('credentials.fieldQueryParameter')}
                name="queryParameter"
                required
                data
                autoComplete="off"
                placeholder="api_key"
                defaultValue={editing?.queryParameter}
                hint={t('credentials.queryParameterHint')}
              />
            )}
            {strategy === 'OAUTH2_CLIENT_CREDENTIALS' && (
              <>
                <Field
                  label={t('credentials.fieldTokenUrl')}
                  name="tokenUrl"
                  required
                  data
                  autoComplete="off"
                  placeholder="https://accounts.spotify.com/api/token"
                  defaultValue={editing?.tokenUrl}
                  hint={t('credentials.tokenUrlHint')}
                />
                <Field
                  label={t('credentials.fieldTokenScopes')}
                  name="tokenScopes"
                  data
                  autoComplete="off"
                  placeholder="playlist-read-private user-read-email"
                  defaultValue={editing?.tokenScopes}
                  hint={t('credentials.tokenScopesHint')}
                />
                <SelectField
                  label={t('credentials.fieldTokenClientAuth')}
                  name="tokenClientAuth"
                  defaultValue={editing?.tokenClientAuth ?? 'BASIC'}
                  options={[
                    { value: 'BASIC', label: t('credentials.tokenClientAuthBasic') },
                    { value: 'POST', label: t('credentials.tokenClientAuthPost') },
                  ]}
                  hint={t('credentials.tokenClientAuthHint')}
                />
              </>
            )}
            {/* Nothing is stored for an open API, so there is nothing to type and nothing to expire.
                Both fields are withheld rather than disabled: an empty box invites a value. */}
            {strategy !== 'NONE' && (
              <>
                <Field
                  label={
                    editing
                      ? t('credentials.replacementSecret')
                      : strategy === 'BASIC'
                        ? t('credentials.fieldSecretBasic')
                        : strategy === 'OAUTH2_CLIENT_CREDENTIALS'
                          ? t('credentials.fieldSecretClient')
                          : t('credentials.fieldSecret')
                  }
                  name="secret"
                  type="password"
                  // Required on create, and on an edit that gives a strategy to what had none: no
                  // value was ever stored for it.
                  required={!editing || editing.authType === 'NONE'}
                  autoComplete="new-password"
                  placeholder={editing ? t('credentials.replacementPlaceholder') : SECRET_PLACEHOLDER[strategy]}
                  hint={
                    editing && editing.authType !== 'NONE'
                      ? t('credentials.replacementHint')
                      : t('credentials.secretHint')
                  }
                />
                <Field
                  label={t('expiry.field')}
                  name="expiresAt"
                  type="date"
                  data
                  defaultValue={toDateInput(editing?.expiresAt)}
                  hint={
                    strategy === 'OAUTH2_CLIENT_CREDENTIALS'
                      ? t('credentials.expiryHintExchange')
                      : t('expiry.fieldHint', { notice: NOTICE_DAYS, warning: WARNING_DAYS })
                  }
                />
              </>
            )}
            <CheckField
              label={t('credentials.enabledLabel')}
              name="enabled"
              defaultChecked={editing ? editing.enabled : true}
              hint={t('credentials.enabledHint')}
            />
          </FormLayout>
        </SidePanel>
      )}
    </>
  );
}
