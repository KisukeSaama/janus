import { lazy, Suspense, useState } from 'react';
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { keys, useSession } from './api';
import { Console } from './app/Console';
import { CONSENT_PATH, usePathname } from './app/routes';
import { SignIn } from './app/SignIn';
import { isAuthError } from './lib/errors';

// Reached only from an MCP client's authorization request, so it is not in the first bundle.
const ConsentPage = lazy(() => import('./app/Consent').then((m) => ({ default: m.ConsentPage })));

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
function createClient() {
  // The handler reads the client declared below rather than a binding from the component, which is
  // what the compiler rules object to. Reading it is safe wherever it is written: nothing calls this
  // before a request has gone out through the client itself.
  const handle = (error: unknown) => {
    // The cookie is the server's to invalidate; all the console can do is stop believing in it.
    if (isAuthError(error)) client.setQueryData(keys.session, null);
  };
  const client = new QueryClient({
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
  return client;
}

export default function App() {
  const [client] = useState(createClient);

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
 *
 * An MCP client's authorization request is answered here too, above the console rather than inside
 * it: the consent screen stands alone, with no rail offering seven other places to go in the middle
 * of a decision. Signing in does not move the address, so a request that arrived signed out is the
 * screen that appears the moment the session lands.
 */
function Gate() {
  const session = useSession();
  const pathname = usePathname();

  if (session.isPending) return <main className="min-h-svh" aria-busy="true" />;
  if (!session.data) return <SignIn />;
  if (pathname === CONSENT_PATH) {
    return (
      <Suspense fallback={<main className="min-h-svh" aria-busy="true" />}>
        <ConsentPage identity={session.data} />
      </Suspense>
    );
  }
  return <Console identity={session.data} />;
}
