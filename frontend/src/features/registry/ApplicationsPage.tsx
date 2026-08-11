import { useMemo, useState, type FormEvent } from 'react';
import { Plus } from 'lucide-react';

import {
  useApplications,
  useCreateGrant,
  useCreateApplication,
  useDeleteApplication,
  useDeleteGrant,
  useCredentials,
  useGrants,
  useProviders,
  useRotateApplicationKey,
  useUpdateApplication,
  useUpdateGrant,
  type Application,
  type Credential,
} from '../../api';
import {
  ArmedAction,
  CheckField,
  CopyField,
  DataTable,
  DeleteAction,
  Empty,
  EnabledState,
  Field,
  FormLayout,
  KeyIssued,
  Notice,
  PageHead,
  RecordCell,
  SidePanel,
  SkeletonRows,
  TextAreaField,
  type Column,
} from '../../components';
import { useI18n } from '../../i18n';
import { isKeyStale } from '../../lib/attention';
import { useErrorMessage } from '../../lib/errors';

/**
 * The machine identities allowed to approach the gateway, and the age of the key each one holds.
 *
 * Every one of them exists to be pasted into somebody's configuration, so the two values a caller
 * actually sends are one click from the row rather than buried in a form: the address and the
 * identifier, ready to copy. The key itself is not among them — it was shown once, and rotating is
 * the only way back to a value this console can display.
 */
export function ApplicationsPage({ username }: { username: string }) {
  const { t, formatAge, formatDate } = useI18n();
  const describe = useErrorMessage();

  const applications = useApplications();
  const grants = useGrants();
  const providers = useProviders();
  const credentials = useCredentials();
  const create = useCreateApplication();
  const update = useUpdateApplication();
  const createGrant = useCreateGrant();
  const updateGrant = useUpdateGrant();
  const deleteGrant = useDeleteGrant();
  const rotate = useRotateApplicationKey();
  const remove = useDeleteApplication();

  const [panel, setPanel] = useState<'closed' | 'new' | Application>('closed');
  const [access, setAccess] = useState<Application | null>(null);
  const [formError, setFormError] = useState('');
  const [error, setError] = useState('');
  const [issuedKey, setIssuedKey] = useState('');

  const rows = applications.data ?? [];
  const editing = typeof panel === 'object' ? panel : null;

  // A grant is unique per application and destination. The API list therefore has one choice per
  // provider, backed by the credential already registered for it (including NONE for open APIs).
  const apiOptions = useMemo(() => {
    const providerById = new Map((providers.data ?? []).map((provider) => [provider.id, provider]));
    const preferredCredentials = new Set(
      (grants.data ?? [])
        .filter((grant) => grant.applicationId === editing?.id)
        .map((grant) => grant.credentialId),
    );
    const credentialByProvider = new Map<string, Credential>();
    for (const credential of credentials.data ?? []) {
      if (!credentialByProvider.has(credential.providerId) || preferredCredentials.has(credential.id)) {
        credentialByProvider.set(credential.providerId, credential);
      }
    }
    return [...credentialByProvider.entries()]
      .map(([providerId, credential]) => ({ provider: providerById.get(providerId), credential }))
      .filter((option) => option.provider)
      .sort((a, b) => a.provider!.name.localeCompare(b.provider!.name));
  }, [credentials.data, editing?.id, grants.data, providers.data]);

  /**
   * Which of our APIs each application is allowed to reach, read off the grants. The name of the
   * app is what identifies it in a list; what it may call is what the reader actually came to
   * check, and it is not derivable from the row itself.
   */
  const reachable = useMemo(() => {
    const bySlug = new Map((providers.data ?? []).map((p) => [p.id, p.slug]));
    const byApp = new Map<string, { name: string; slug?: string }[]>();
    for (const grant of grants.data ?? []) {
      const list = byApp.get(grant.applicationId) ?? [];
      list.push({ name: grant.providerName, slug: bySlug.get(grant.providerId) });
      byApp.set(grant.applicationId, list);
    }
    for (const list of byApp.values()) list.sort((a, b) => a.name.localeCompare(b.name));
    return byApp;
  }, [grants.data, providers.data]);

  function close() {
    setPanel('closed');
    setFormError('');
  }

  async function submit(e: FormEvent<HTMLFormElement>) {
    const form = new FormData(e.currentTarget);
    const selectedProviders = new Set(form.getAll('apiProviderIds').map(String));
    const input = {
      name: String(form.get('name') ?? ''),
      description: String(form.get('description') ?? '') || null,
      enabled: form.get('enabled') === 'on',
      // One per line is how a short list is edited without inventing a widget. Empty means any
      // origin, which is what most services want and what the field's hint says.
      allowedOrigins: String(form.get('allowedOrigins') ?? '')
        .split('\n')
        .map((origin) => origin.trim())
        .filter(Boolean),
    };
    setFormError('');
    try {
      let applicationId: string;
      let issuedKey = '';
      if (editing) {
        await update.mutateAsync({ id: editing.id, input });
        applicationId = editing.id;
      } else {
        const issued = await create.mutateAsync(input);
        applicationId = issued.application.id;
        issuedKey = issued.apiKey;
      }

      const current = (grants.data ?? []).filter((grant) => grant.applicationId === applicationId);
      const currentByProvider = new Map(current.map((grant) => [grant.providerId, grant]));
      const desired = apiOptions.filter((option) => selectedProviders.has(option.provider!.id));

      try {
        await Promise.all([
          ...current
            .filter((grant) => !selectedProviders.has(grant.providerId))
            .map((grant) => deleteGrant.mutateAsync(grant.id)),
          ...desired.map(({ provider, credential }) => {
            const existing = currentByProvider.get(provider!.id);
            const grantInput = {
              applicationId,
              providerId: provider!.id,
              credentialId: credential.id,
              enabled: true,
              rateLimitPerMinute: existing?.rateLimitPerMinute ?? 0,
              rateLimitBurst: existing?.rateLimitBurst ?? 0,
            };
            if (!existing) return createGrant.mutateAsync(grantInput);
            if (existing.credentialId !== credential.id || !existing.enabled) {
              return updateGrant.mutateAsync({ id: existing.id, input: grantInput });
            }
            return Promise.resolve();
          }),
        ]);
      } catch (grantError) {
        // A newly issued key is useless without the requested access statement. Removing the new
        // application also removes any grants already created by the parallel reconciliation.
        if (!editing) await remove.mutateAsync(applicationId).catch(() => undefined);
        throw grantError;
      }

      close();
      if (issuedKey) setIssuedKey(issuedKey);
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

  const newButton = (
    <button className="btn btn-primary w-full sm:w-auto" onClick={() => setPanel('new')}>
      <Plus size={15} strokeWidth={2.25} />
      {t('applications.new')}
    </button>
  );

  const columns: Column<Application>[] = [
    {
      key: 'name',
      label: t('applications.colName'),
      primary: true,
      grow: true,
      // Falls back to the identifier, set as machine data, when nobody wrote a description.
      cell: (r) => <RecordCell name={r.name} note={r.description || r.id} mono={!r.description} />,
    },
    {
      key: 'apis',
      label: t('applications.colApis'),
      grow: true,
      cell: (r) => {
        const apis = reachable.get(r.id) ?? [];
        if (apis.length === 0) return <span className="text-text-3">{t('applications.noApi')}</span>;
        return (
          <span className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
            {apis.slice(0, 3).map((api) => (
              <span key={api.name} className="truncate">
                {api.name}
              </span>
            ))}
            {apis.length > 3 && <span className="num text-2xs text-text-3">+{apis.length - 3}</span>}
          </span>
        );
      },
    },
    {
      key: 'state',
      label: t('state.label'),
      badge: true,
      nowrap: true,
      cell: (r) => <EnabledState enabled={r.enabled} />,
    },
    {
      // Key age, not registration date: the operator maintains keys, not records.
      key: 'apiKey',
      label: t('keys.label'),
      nowrap: true,
      cell: (r) => (
        <span className="inline-flex items-center gap-2">
          <span className={isKeyStale(r) ? 'text-warn' : undefined} title={formatDate(r.apiKeyRotatedAt)}>
            {formatAge(r.apiKeyRotatedAt)}
          </span>
          {isKeyStale(r) && (
            <span className="stamp rounded-[3px] border border-warn/55 px-1.5 py-1 text-warn">{t('keys.stale')}</span>
          )}
        </span>
      ),
    },
  ];

  return (
    <>
      <PageHead
        section={t('nav.registry')}
        title={t('applications.title')}
        intro={t('applications.intro')}
        action={newButton}
      />
      {error && <Notice>{error}</Notice>}
      {applications.isError && <Notice>{describe(applications.error)}</Notice>}

      {applications.isPending ? (
        <SkeletonRows cols={3} />
      ) : rows.length === 0 ? (
        <Empty headline={t('applications.emptyTitle')} hint={t('applications.emptyHint')} action={newButton} />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(r) => r.id}
          actions={(r) => (
            <span className="flex flex-wrap items-center justify-end gap-1">
              <button className="btn btn-sm btn-secondary" onClick={() => setAccess(r)}>
                {t('applications.access')}
                <span className="sr-only"> {r.name}</span>
              </button>
              <button className="btn btn-sm btn-quiet" onClick={() => setPanel(r)}>
                {t('common.edit')}
                <span className="sr-only"> {r.name}</span>
              </button>
              <ArmedAction
                trigger={t('applications.rotate')}
                confirm={t('applications.rotateConfirm')}
                pending={t('applications.rotating')}
                description={t('applications.rotateDescription', { name: r.name })}
                prominent={isKeyStale(r)}
                onConfirm={() =>
                  act(async () => {
                    const issued = await rotate.mutateAsync(r.id);
                    setIssuedKey(issued.apiKey);
                  })
                }
              />
              <DeleteAction
                label={r.name}
                consequence={t('applications.deleteConsequence')}
                onDelete={() => act(() => remove.mutateAsync(r.id))}
              />
            </span>
          )}
        />
      )}

      {panel !== 'closed' && (
        <SidePanel
          title={editing ? t('applications.editTitle') : t('applications.panelTitle')}
          intro={editing ? t('applications.editIntro') : t('applications.panelIntro')}
          onClose={close}
        >
          <FormLayout
            onSubmit={submit}
            submitLabel={editing ? t('common.saveChanges') : t('applications.submit')}
            error={formError}
          >
            <Field
              label={t('applications.fieldName')}
              name="name"
              required
              autoComplete="off"
              placeholder="orders-api"
              defaultValue={editing?.name}
            />
            <Field
              label={t('applications.fieldDescription')}
              name="description"
              autoComplete="off"
              defaultValue={editing?.description}
            />
            <TextAreaField
              label={t('applications.fieldOrigins')}
              name="allowedOrigins"
              data
              autoComplete="off"
              spellCheck={false}
              placeholder="https://example.com"
              defaultValue={editing?.allowedOrigins.join('\n')}
              hint={t('applications.originsHint')}
            />
            <fieldset>
              <legend className="stamp mb-2 text-text-2">{t('applications.fieldApis')}</legend>
              {apiOptions.length === 0 ? (
                <p className="rounded-control border border-line bg-sunk px-3 py-2.5 text-sm text-text-2">
                  {t('applications.fieldApisEmpty')}
                </p>
              ) : (
                <div className="divide-y divide-line rounded-control border border-line bg-sunk px-3">
                  {apiOptions.map(({ provider }) => {
                    const checked = !!editing && (grants.data ?? []).some(
                      (grant) => grant.applicationId === editing.id && grant.providerId === provider!.id,
                    );
                    return (
                      <label key={provider!.id} className="flex cursor-pointer items-start gap-3 py-3">
                        <input
                          type="checkbox"
                          name="apiProviderIds"
                          value={provider!.id}
                          defaultChecked={checked}
                          className="mt-0.5 h-4 w-4 shrink-0 accent-[var(--c-accent)]"
                        />
                        <span className="min-w-0">
                          <span className="block text-sm">{provider!.name}</span>
                          <span className="data mt-0.5 block truncate text-xs text-text-2">/{provider!.slug}/**</span>
                        </span>
                      </label>
                    );
                  })}
                </div>
              )}
              <p className="mt-1.5 text-xs text-text-2">{t('applications.fieldApisHint')}</p>
            </fieldset>
            <CheckField
              label={t('applications.activeLabel')}
              name="enabled"
              defaultChecked={editing ? editing.enabled : true}
              hint={t('applications.activeHint')}
            />
          </FormLayout>
        </SidePanel>
      )}
      {access && (
        <SidePanel
          title={t('applications.accessTitle', { name: access.name })}
          intro={t('applications.accessIntro')}
          onClose={() => setAccess(null)}
        >
          <div className="space-y-4">
            <CopyField
              label={t('applications.accessGateway')}
              value={`${window.location.origin}/${encodeURIComponent(username)}/gateway/`}
            />
            <CopyField label="X-Janus-Application-Id" value={access.id} />
            <div>
              <CopyField
                label={t('applications.accessHeaders')}
                value={[`X-Janus-Application-Id: ${access.id}`, 'X-Janus-Api-Key: $JANUS_API_KEY'].join('\n')}
                block
              />
              <p className="mt-1.5 text-xs text-text-3">
                {t('applications.accessKeyNote', { age: formatAge(access.apiKeyRotatedAt) })}
              </p>
            </div>

            {/* What this key opens. Every path under each address below is reachable with it. */}
            <div className="border-t border-line pt-4">
              <p className="stamp mb-2 text-text-2">{t('applications.accessApis')}</p>
              {(reachable.get(access.id) ?? []).length === 0 ? (
                <p className="text-sm text-text-3">{t('applications.noApi')}</p>
              ) : (
                <ul className="panel divide-y divide-line text-sm">
                  {(reachable.get(access.id) ?? []).map((api) => (
                    <li key={api.name} className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 px-4 py-2.5">
                      <span>{api.name}</span>
                      <span className="data text-xs text-text-2">
                        /{username}/gateway/{api.slug ?? '…'}/**
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </SidePanel>
      )}
      {issuedKey && <KeyIssued value={issuedKey} onDismiss={() => setIssuedKey('')} />}
    </>
  );
}
