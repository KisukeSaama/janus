import { useState, type FormEvent, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, ArrowRight } from 'lucide-react';

import { del, keys, post, type AuthType, type Credential, type Provider } from '../../api';
import { CheckField, ConfirmDialog, Field, Sheet } from '../../components';
import { useI18n } from '../../i18n';
import { gatewayUrl, toSlug } from '../../lib/connections';
import { useErrorMessage } from '../../lib/errors';
import { fromDateInput, NOTICE_DAYS, WARNING_DAYS } from '../../lib/expiry';
import { ConnectionOfferFields, ContractFields, DestinationFields, PolicyFields } from './ProviderForm';
import {
  contractComplete,
  contractOf,
  destinationComplete,
  NEW_PROVIDER,
  toProviderInput,
  type ProviderDraft,
  type SetDraft,
} from './providerDraft';
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
  // The same draft the edit panels hold, so what can be set afterwards can be set here too.
  const [draft, setDraft] = useState<ProviderDraft>(NEW_PROVIDER);
  const set: SetDraft = (patch) => setDraft((current) => ({ ...current, ...patch }));
  const [slugEdited, setSlugEdited] = useState(false);
  const [connectionSecret, setConnectionSecret] = useState('');
  const [activate, setActivate] = useState(false);
  const [secret, setSecret] = useState('');
  const [expiresAt, setExpiresAt] = useState('');
  const [busy, setBusy] = useState(false);
  // The way out that is waiting on an answer, not merely the fact that one is. There are two exits
  // from this flow — the button that closes it and the wordmark that leaves for the console's home —
  // and the dialog has to fire the one that was actually clicked.
  const [leaving, setLeaving] = useState<(() => void) | null>(null);
  const [error, setError] = useState('');

  // The gateway path follows the name until somebody edits it.
  const effective: ProviderDraft = slugEdited ? draft : { ...draft, slug: toSlug(draft.name) };
  const { authType } = draft;
  const started = draft.name !== '' || draft.baseUrl !== '' || secret !== '' || expiresAt !== '';

  /** Nothing typed, nothing to lose: an empty form leaves on the click rather than on a question. */
  const leave = (exit: () => void) => (started ? setLeaving(() => exit) : exit());

  const exchanges = authType === 'OAUTH2_CLIENT_CREDENTIALS';
  // Whether the connection needs an OAuth client of its own. It does not when the application
  // already stores one, which is every API that mints both kinds of token from a single client id.
  const connectionNeedsSecret = draft.connectable && authType !== 'NONE' && !exchanges;

  const complete: Record<Step, boolean> = {
    1: destinationComplete(effective),
    2:
      // The contract is always required; the secret only when this account is activating with it.
      contractComplete(effective) &&
      (!activate || authType === 'NONE' || secret !== '') &&
      (!activate || !connectionNeedsSecret || connectionSecret !== ''),
  };

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
      const provider = await post<Provider>('/providers', toProviderInput(effective));
      undo.push(() => del(`/providers/${provider.id}`));

      // Only when this account asked for it. Without a credential the entry stays available in the
      // catalogue, which is what every other account sees of it until they activate it themselves.
      if (activate) {
        stage = t('connect.stepSecret');
        const credential = await post<Credential>('/credentials', {
          // An open API still gets a record: it is what the grant, the cache and the journal hang
          // off. Named for what it is, since "-secret" would describe a value that does not exist.
          name: authType === 'NONE' ? `${effective.slug}-open` : `${effective.slug}-secret`,
          providerId: provider.id,
          ...contractOf(effective),
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
            <DestinationFields
              draft={effective}
              set={set}
              autoFocus
              showEnabled={false}
              slug={
                <GatewayPath
                  slug={effective.slug}
                  editing={slugEdited}
                  onEdit={() => {
                    set({ slug: effective.slug });
                    setSlugEdited(true);
                  }}
                  onChange={(value) => set({ slug: toSlug(value) })}
                />
              }
            />
          </>
        )}

        {step === 2 && (
          <>
            <StepHead
              title={authType === 'NONE' ? t('connect.s2TitleOpen') : t('connect.s2Title')}
              lead={authType === 'NONE' ? t('connect.s2LeadOpen') : t('connect.s2Lead')}
            />
            <ContractFields draft={draft} set={set} />

            {/* Beside the contract above, not instead of it: this is the second identity the same
                API may offer, and the reason it no longer has to be registered twice. */}
            <div className="space-y-6 border-t border-line pt-6">
              <ConnectionOfferFields draft={draft} set={set} />
              {draft.connectable && <p className="text-xs text-text-2">{t('connect.consentNote')}</p>}
            </div>
            <div>
              <p className="stamp mb-1.5 text-text-2">{t('connect.preview')}</p>
              <p className="data rounded-control border border-line bg-sunk px-3 py-2 text-xs leading-5">
                <Preview draft={draft} />
              </p>
              {authType === 'OAUTH2_CLIENT_CREDENTIALS' && (
                <p className="mt-1.5 text-xs text-text-2">{t('connect.exchangeNote')}</p>
              )}
            </div>

            {/* The same traffic policy the edit panels offer, folded away because its defaults suit
                most APIs: nothing here has to be decided before the API exists. */}
            <details className="group border-t border-line pt-6">
              <summary className="stamp cursor-pointer text-text-2 marker:text-text-3">
                {t('connect.policyToggle')}
              </summary>
              <div className="mt-5 space-y-6">
                <p className="text-xs text-text-2">{t('providers.policyIntro')}</p>
                <PolicyFields draft={draft} set={set} />
              </div>
            </details>

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
                apiName={effective.name.trim()}
                slug={effective.slug}
                baseUrl={effective.baseUrl.trim()}
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

/** What will actually leave, in the shape it will leave in. */
function Preview({ draft }: { draft: ProviderDraft }) {
  const { authType, headerName, queryParameter, signing } = draft;
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
