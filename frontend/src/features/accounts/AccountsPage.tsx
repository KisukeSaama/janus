import { useState, type FormEvent } from 'react';
import { Plus } from 'lucide-react';

import {
  useAccounts,
  useCreateAccount,
  useDeleteAccount,
  useUpdateAccount,
  type Account,
  type AccountRole,
  type Identity,
} from '../../api';
import {
  CheckField,
  DataTable,
  DeleteAction,
  Empty,
  EnabledState,
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
import { useI18n, type MessageKey } from '../../i18n';
import { useErrorMessage } from '../../lib/errors';

/**
 * Who may sign in.
 *
 * <p>The only page whose rows are people rather than records, and the only one a role gates. What it
 * does not do is show anybody's registry: an administrator decides who exists, not what they
 * registered — so there is nothing here about services, APIs or secrets.
 */
export function AccountsPage({ identity }: { identity: Identity }) {
  const { t, formatDate } = useI18n();
  const describe = useErrorMessage();

  const accounts = useAccounts();
  const create = useCreateAccount();
  const update = useUpdateAccount();
  const remove = useDeleteAccount();

  const [panel, setPanel] = useState<'closed' | 'new' | Account>('closed');
  const [formError, setFormError] = useState('');
  const [error, setError] = useState('');

  const rows = accounts.data ?? [];
  const editing = typeof panel === 'object' ? panel : null;
  const superAdmin = identity.role === 'SUPER_ADMIN';

  /** Whether the signed-in person may act on this row at all — the rule AccountService enforces. */
  const manageable = (account: Account) =>
    account.id === identity.id || superAdmin || account.role === 'USER';

  function close() {
    setPanel('closed');
    setFormError('');
  }

  async function submit(e: FormEvent<HTMLFormElement>) {
    const form = new FormData(e.currentTarget);
    const password = String(form.get('password') ?? '');
    const input = {
      username: String(form.get('username') ?? ''),
      displayName: String(form.get('displayName') ?? ''),
      email: String(form.get('email') ?? ''),
      role: String(form.get('role') ?? 'USER') as AccountRole,
      // Blank on an edit means "leave the current password alone"; it is never sent back to us.
      password: password === '' ? null : password,
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
    <button className="btn btn-primary w-full sm:w-auto" onClick={() => setPanel('new')}>
      <Plus size={15} strokeWidth={2.25} />
      {t('accounts.new')}
    </button>
  );

  const roles: AccountRole[] = superAdmin ? ['USER', 'ADMIN', 'SUPER_ADMIN'] : ['USER', 'ADMIN'];

  const columns: Column<Account>[] = [
    {
      key: 'username',
      label: t('accounts.colName'),
      primary: true,
      grow: true,
      // A display name that repeats the login names nobody, so the row prints the login once.
      cell: (r) =>
        r.displayName === r.username ? (
          <RecordCell name={<span className="data">{r.username}</span>} />
        ) : (
          <RecordCell name={r.displayName} note={r.username} mono />
        ),
    },
    { key: 'email', label: t('accounts.colEmail'), cell: (r) => r.email },
    {
      key: 'role',
      label: t('accounts.colRole'),
      nowrap: true,
      cell: (r) => t(`roles.${r.role}` as MessageKey),
    },
    {
      key: 'seen',
      label: t('accounts.colLastSeen'),
      nowrap: true,
      cell: (r) => (r.lastSignedInAt ? formatDate(r.lastSignedInAt) : t('accounts.neverSignedIn')),
    },
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
        section={t('nav.administration')}
        title={t('accounts.title')}
        intro={t('accounts.intro')}
        action={newButton}
      />
      {error && <Notice>{error}</Notice>}
      {accounts.isError && <Notice>{describe(accounts.error)}</Notice>}

      {accounts.isPending ? (
        <SkeletonRows cols={5} />
      ) : rows.length === 0 ? (
        <Empty headline={t('accounts.emptyTitle')} hint={t('accounts.emptyHint')} action={newButton} />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(r) => r.id}
          actions={(r) =>
            manageable(r) ? (
              // Two entries do not earn a menu: an overflow control here would cost a click to reach
              // what already fits, and neither verb is ambiguous about what it acts on.
              <>
                <button className="btn btn-sm btn-quiet" onClick={() => setPanel(r)}>
                  {t('common.edit')}
                  <span className="sr-only"> {r.displayName}</span>
                </button>
                {r.id !== identity.id && (
                  <DeleteAction
                    label={r.displayName}
                    consequence={t('accounts.deleteConsequence')}
                    onDelete={async () => {
                      setError('');
                      try {
                        await remove.mutateAsync(r.id);
                      } catch (x) {
                        setError(describe(x));
                      }
                    }}
                  />
                )}
              </>
            ) : (
              // Peers do not hold power over each other; saying so is better than a button that fails.
              <span className="text-xs text-text-3">{t('accounts.notYours')}</span>
            )
          }
        />
      )}

      {panel !== 'closed' && (
        <SidePanel
          title={editing ? t('accounts.editTitle') : t('accounts.panelTitle')}
          intro={editing ? t('accounts.editIntro') : t('accounts.panelIntro')}
          onClose={close}
        >
          <FormLayout
            onSubmit={submit}
            submitLabel={editing ? t('common.saveChanges') : t('accounts.submit')}
            error={formError}
          >
            <Field
              label={t('accounts.fieldUsername')}
              name="username"
              required
              data
              autoComplete="off"
              minLength={6}
              maxLength={60}
              placeholder="adalovelace"
              defaultValue={editing?.username}
              disabled={!!editing}
              hint={t('accounts.usernameHint')}
            />
            {/* A disabled input submits nothing, and the value still has to reach the request. */}
            {editing && <input type="hidden" name="username" value={editing.username} />}
            <Field
              label={t('accounts.fieldDisplayName')}
              name="displayName"
              required
              autoComplete="off"
              placeholder="Ada Lovelace"
              defaultValue={editing?.displayName}
            />
            <Field
              label={t('accounts.fieldEmail')}
              name="email"
              type="email"
              required
              autoComplete="off"
              defaultValue={editing?.email}
              hint={t('accounts.emailHint')}
            />
            <SelectField
              label={t('accounts.fieldRole')}
              name="role"
              defaultValue={editing?.role ?? 'USER'}
              options={roles.map((role) => ({ value: role, label: t(`roles.${role}` as MessageKey) }))}
              hint={superAdmin ? t('accounts.roleHint') : t('accounts.roleHintAdmin')}
            />
            <Field
              label={editing ? t('accounts.replacementPassword') : t('accounts.fieldPassword')}
              name="password"
              type="password"
              required={!editing}
              autoComplete="new-password"
              placeholder={editing ? t('accounts.replacementPlaceholder') : undefined}
              hint={t('accounts.passwordHint')}
            />
            <CheckField
              label={t('accounts.activeLabel')}
              name="enabled"
              defaultChecked={editing ? editing.enabled : true}
              hint={t('accounts.activeHint')}
            />
          </FormLayout>
        </SidePanel>
      )}
    </>
  );
}
