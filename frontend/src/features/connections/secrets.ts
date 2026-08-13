import type { AuthType } from '../../api';
import type { MessageKey } from '../../i18n';

/**
 * What the stored value is called, per strategy.
 *
 * Two flows ask for it — registering an API and activating one from the list of a service's
 * destinations — and a field labelled "secret" over a box that wants `client_id:client_secret` is
 * how somebody pastes half of what Janus needs. The wording lives here so both ask the same thing.
 */
export function secretLabel(authType: AuthType, t: (key: MessageKey) => string): string {
  switch (authType) {
    case 'BASIC':
      return t('credentials.fieldSecretBasic');
    case 'OAUTH2_CLIENT_CREDENTIALS':
    case 'OAUTH2_AUTHORIZATION_CODE':
      return t('credentials.fieldSecretClient');
    case 'HMAC_SIGNATURE':
      return t('connect.signSecret');
    case 'API_KEY_HEADER':
    case 'API_KEY_QUERY':
      return t('connect.apiKeyValue');
    default:
      return t('connect.secretValue');
  }
}

/** The shape of the value, where it is a pair rather than one string. */
export function secretPlaceholder(authType: AuthType, t: (key: MessageKey) => string): string | undefined {
  switch (authType) {
    case 'BASIC':
      return t('connect.secretBasic');
    case 'OAUTH2_CLIENT_CREDENTIALS':
    case 'OAUTH2_AUTHORIZATION_CODE':
      return 'client_id:client_secret';
    case 'HMAC_SIGNATURE':
      return 'key:secret';
    default:
      return undefined;
  }
}
