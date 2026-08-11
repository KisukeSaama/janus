import { useState, type FormEvent } from 'react';

import { useSignIn } from '../api';
import { Field, Wordmark } from '../components';
import { useI18n } from '../i18n';
import { useErrorMessage } from '../lib/errors';

import { SettingsMenu } from './SettingsMenu';

/**
 * Signing in is a request, and the answer is a cookie the browser keeps and no script can read.
 *
 * <p>Nothing is held here: there is no password in memory to be replayed on the next call, and
 * signing out is the server forgetting rather than this page emptying a variable.
 */
export function SignIn() {
  const { t } = useI18n();
  const describe = useErrorMessage();
  const signIn = useSignIn();
  const [error, setError] = useState('');

  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    setError('');
    try {
      await signIn.mutateAsync({
        username: String(form.get('username') ?? ''),
        password: String(form.get('password') ?? ''),
      });
    } catch (x) {
      // A refused password and an unknown username are the same answer, deliberately.
      setError(describe(x) === t('errors.throttled') ? describe(x) : t('signIn.rejected'));
    }
  }

  return (
    <main className="grid min-h-svh place-items-center px-5 pb-[12vh] pt-8">
      <div className="w-full max-w-[24rem]">
        <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
          <Wordmark />
          <div className="flex items-center gap-2">
            <span className="stamp text-text-3">{t('signIn.tagline')}</span>
            <SettingsMenu />
          </div>
        </div>

        <div className="panel px-5 py-5 md:px-6 md:py-6">
          <h1 className="text-lg font-semibold tracking-title">{t('signIn.title')}</h1>
          <p className="mt-1.5 text-sm text-text-2">{t('signIn.intro')}</p>

          <form onSubmit={submit} className="mt-6 space-y-4">
            <Field label={t('signIn.username')} name="username" autoComplete="username" required autoFocus />
            <Field
              label={t('signIn.password')}
              name="password"
              type="password"
              autoComplete="current-password"
              required
            />
            {error && (
              <p role="alert" className="rounded-panel border border-bad/40 bg-bad-wash px-3 py-2.5 text-sm">
                {error}
              </p>
            )}
            <button type="submit" className="btn btn-primary mt-1 w-full" disabled={signIn.isPending}>
              {signIn.isPending ? t('signIn.checking') : t('signIn.submit')}
            </button>
          </form>
        </div>

        <p className="mt-5 text-xs text-text-3">{t('signIn.ephemeral')}</p>
      </div>
    </main>
  );
}
