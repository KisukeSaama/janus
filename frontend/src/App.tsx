import { useState } from 'react';
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { keys, useSession } from './api';
import { Console } from './app/Console';
import { SignIn } from './app/SignIn';
import { isAuthError } from './lib/errors';

/**
 * One client for the whole console.
 *
 * Retrying is deliberately narrow: a refused password or a rejected record is an answer, not a
 * blip, and repeating it only delays the sentence the operator needs to read. Refetching when the
 * window regains focus is left on — a console left open on a second monitor should not be showing
 * yesterday's records.
 *
 * A session refused anywhere returns the whole console to the sign-in screen. It used to be the one
 * loader's job, which meant a background refetch could keep failing quietly against a session that
 * had stopped working.
 */
function createClient(onAuthLost: () => void) {
  const handle = (error: unknown) => {
    if (isAuthError(error)) onAuthLost();
  };
  return new QueryClient({
    queryCache: new QueryCache({ onError: handle }),
    mutationCache: new MutationCache({ onError: handle }),
    defaultOptions: {
      queries: {
        retry: (failureCount, error) => !isAuthError(error) && failureCount < 2,
        refetchOnWindowFocus: true,
      },
      mutations: { retry: false },
    },
  });
}

export default function App() {
  const [client] = useState(() =>
    createClient(() => {
      // The cookie is the server's to invalidate; all the console can do is stop believing in it.
      client.setQueryData(keys.session, null);
    }),
  );

  return (
    <QueryClientProvider client={client}>
      <Gate />
    </QueryClientProvider>
  );
}

/**
 * Whether to show the console or the sign-in screen is the answer to one request, so it is asked as
 * one. The blank first frame is deliberate: a sign-in form that appears for an instant before the
 * session comes back reads as having been signed out.
 */
function Gate() {
  const session = useSession();

  if (session.isPending) return <main className="min-h-svh" aria-busy="true" />;
  return session.data ? <Console identity={session.data} /> : <SignIn />;
}
