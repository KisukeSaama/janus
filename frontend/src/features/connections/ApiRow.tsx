import type { ReactNode } from 'react';

import type { Credential, Provider } from '../../api';
import { Field } from '../../components';
import { useI18n } from '../../i18n';
import type { ApiActivation } from './activation';
import { secretLabel, secretPlaceholder } from './secrets';

/**
 * One registered API in a list of what something may be allowed to reach.
 *
 * Two screens ask that question — a service being registered, and one being edited — and both list
 * every registered API, including those this account holds no credential for: leaving those out is
 * what makes an API registered a minute earlier look like it vanished. What they cannot do is tick
 * them, since a grant has to name the credential it presents, so the row carries the way to supply
 * one rather than only the sentence saying it is missing.
 *
 * The tick itself belongs to the list, which is why it is passed in: one form holds it as state, the
 * other leaves it to the browser.
 */
export function ApiRow({
  provider,
  credential,
  /** What this row's tick opens, written the way the caller will write it. */
  path,
  activation,
  onActivated,
  children,
}: {
  provider: Provider;
  credential?: Credential;
  path: string;
  activation: ApiActivation;
  /** Told once the credential exists, so the list may tick the row it just unlocked. */
  onActivated: (provider: Provider, credential: Credential) => void;
  children: ReactNode;
}) {
  const { t } = useI18n();
  const asking = activation.activating === provider.id;

  async function activate() {
    const written = await activation.activate(provider);
    if (written) onActivated(provider, written);
  }

  return (
    <div className="py-3">
      <div className="flex items-start gap-3">
        <label className={`flex min-w-0 flex-1 items-start gap-3 ${credential ? 'cursor-pointer' : 'cursor-not-allowed'}`}>
          {children}
          <span className="min-w-0">
            <span className={`block text-sm ${credential ? '' : 'text-text-3'}`}>{provider.name}</span>
            {/* Why a row cannot be ticked, on the row rather than under a list where it would read
                as being about all of them. */}
            <span className="data mt-0.5 block truncate text-xs text-text-2">
              {credential ? path : t('applications.apiNoCredential')}
            </span>
          </span>
        </label>
        {/* Not a dead end: what the row is missing is asked for here, and an open API needs nothing
            at all. */}
        {!credential && !asking && (
          <button
            type="button"
            className="btn btn-sm btn-secondary shrink-0"
            disabled={activation.busy === provider.id}
            onClick={() => (provider.authType === 'NONE' ? activate() : activation.ask(provider))}
          >
            {activation.busy === provider.id ? t('common.working') : t('service.activate')}
            <span className="sr-only"> {provider.name}</span>
          </button>
        )}
      </div>

      {asking && (
        <div className="mt-3 space-y-3 border-l-2 border-accent/40 pl-3.5">
          <Field
            label={secretLabel(provider.authType, t)}
            type="password"
            autoFocus
            autoComplete="new-password"
            placeholder={secretPlaceholder(provider.authType, t)}
            value={activation.secret}
            onChange={(e) => activation.setSecret(e.target.value)}
            // This field sits inside a form of its own list's, whose Enter submits the whole thing.
            // Here it activates the API, which is what the reader is looking at.
            onKeyDown={(e) => {
              if (e.key !== 'Enter') return;
              e.preventDefault();
              if (activation.secret !== '' && activation.busy === null) void activate();
            }}
            hint={provider.authType === 'OAUTH2_AUTHORIZATION_CODE' ? t('service.activateConsent') : undefined}
          />
          {activation.error && (
            <p role="alert" className="text-sm text-bad">
              {activation.error}
            </p>
          )}
          <div className="flex gap-2">
            <button
              type="button"
              className="btn btn-sm btn-primary"
              disabled={activation.secret === '' || activation.busy !== null}
              onClick={activate}
            >
              {activation.busy === provider.id ? t('common.working') : t('service.activate')}
            </button>
            <button type="button" className="btn btn-sm btn-quiet" onClick={activation.cancel}>
              {t('common.cancel')}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
