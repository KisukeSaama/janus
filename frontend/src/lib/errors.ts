import { useCallback } from 'react';
import { ApiError } from '../api';
import { useI18n } from '../i18n';

/**
 * Turns any thrown value into a sentence the reader can act on, translating the failures Janus
 * recognises and passing the backend's own wording through untouched.
 *
 * Memoised on the active locale: callers put it in `useCallback` dependency lists, and an unstable
 * identity here would re-trigger their loaders on every render.
 */
export function useErrorMessage() {
  const { t } = useI18n();
  return useCallback(
    (error: unknown): string => {
      if (error instanceof ApiError) {
        if (error.code === 'OFFLINE') return t('errors.unreachable');
        if (error.code === 'THROTTLED') return t('errors.throttled');
        if (error.message) return error.message;
        return t('errors.generic', { status: error.status });
      }
      return error instanceof Error && error.message ? error.message : t('errors.generic', { status: 0 });
    },
    [t],
  );
}

export const isAuthError = (error: unknown) => error instanceof ApiError && error.code === 'AUTH_REQUIRED';
