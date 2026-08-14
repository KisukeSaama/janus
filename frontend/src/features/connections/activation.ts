import { useState } from 'react';

import { useCreateCredential, type Credential, type Provider } from '../../api';
import { useErrorMessage } from '../../lib/errors';

/**
 * Provisioning this account's credential for an API, from the list that says it is missing one.
 *
 * A row nobody holds a credential for cannot be ticked, and every list that says so used to stop
 * there: the reader was told what was missing on a screen with no way to supply it, and the way
 * forward was to abandon a half-written form, go to the catalogue, and come back. What is missing is
 * one value — or, for an open API, nothing at all — so it is asked for where it is noticed.
 *
 * The record it writes is this account's, not the deployment's: the API stays as its catalogue entry
 * states it, and what changes is that this account now holds something to present at it.
 */
export function useApiActivation() {
  const describe = useErrorMessage();
  const createCredential = useCreateCredential();

  // Credentials written here, held alongside the query as well as invalidating it: the row they
  // unlock has to become tickable on the click, not once a refetch has come back.
  const [minted, setMinted] = useState<Credential[]>([]);
  /** The row whose secret is being asked for, if any. */
  const [activating, setActivating] = useState<string | null>(null);
  const [secret, setSecret] = useState('');
  /** Which row is being written, rather than merely that one is: the button that says so is on it. */
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState('');

  function ask(provider: Provider) {
    setActivating(provider.id);
    setSecret('');
    setError('');
  }

  function cancel() {
    setActivating(null);
    setSecret('');
  }

  /** Writes the credential, or reports why it was refused. Null means nothing was written. */
  async function activate(provider: Provider): Promise<Credential | null> {
    const open = provider.authType === 'NONE';
    setBusy(provider.id);
    setError('');
    try {
      const credential = await createCredential.mutateAsync({
        // Named for what it is, since "-secret" would describe a value an open API does not hold.
        name: open ? `${provider.slug}-open` : `${provider.slug}-secret`,
        providerId: provider.id,
        // The contract belongs to the API; a credential only ever repeats it back.
        authType: provider.authType,
        headerName: provider.headerName ?? null,
        queryParameter: provider.queryParameter ?? null,
        tokenUrl: provider.tokenUrl ?? null,
        tokenScopes: provider.tokenScopes ?? null,
        tokenClientAuth: provider.tokenClientAuth ?? null,
        signatureAlgorithm: provider.signatureAlgorithm ?? null,
        signatureTemplate: provider.signatureTemplate ?? null,
        signatureEncoding: provider.signatureEncoding ?? null,
        signatureHeader: provider.signatureHeader ?? null,
        signatureParameter: provider.signatureParameter ?? null,
        timestampHeader: provider.timestampHeader ?? null,
        timestampParameter: provider.timestampParameter ?? null,
        secret: open ? null : secret,
        // Not asked for here: a deadline is an edit on a record that now exists, and these lists are
        // about getting one written at all.
        expiresAt: null,
        enabled: true,
      });
      setMinted((current) => [...current, credential]);
      setActivating(null);
      setSecret('');
      return credential;
    } catch (x) {
      setError(describe(x));
      return null;
    } finally {
      setBusy(null);
    }
  }

  return { minted, activating, secret, setSecret, busy, error, ask, cancel, activate };
}

export type ApiActivation = ReturnType<typeof useApiActivation>;
