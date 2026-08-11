import { lazy, Suspense, useCallback, useEffect, useState } from 'react';
import { useIsFetching, useQueryClient } from '@tanstack/react-query';
import { Menu, RefreshCw } from 'lucide-react';

import { useApplications, useCredentials, useSignOut, type Identity } from '../api';
import { PageSkeleton, Wordmark } from '../components';
import { ConnectFlow } from '../features/connections/ConnectFlow';
import { NotificationsMenu } from '../features/notifications/NotificationsMenu';
import { useMediaQuery, WIDE } from '../hooks/useMediaQuery';
import { useI18n } from '../i18n';

import { NavDrawer, Sidebar } from './Navigation';
import { PAGE_TITLE, useLocation, type Page } from './routes';
import { SettingsMenu } from './SettingsMenu';

/**
 * A rail, a bar, and one page under them.
 *
 * The rail names every destination and holds the current one. The bar holds what is true of all of
 * them: what Janus has announced, a way to refresh, and the settings. Nothing else moves between
 * pages, which is what lets a title, a table, and a panel land on the same pixel throughout.
 *
 * Pages are code-split. The registry and the setup flow are not what most visits are for, and there
 * is no reason for their code to be in the bundle that draws the first screen.
 */

const DashboardPage = lazy(() =>
  import('../features/dashboard/DashboardPage').then((m) => ({ default: m.DashboardPage })),
);
const ConnectionPage = lazy(() =>
  import('../features/connections/ConnectionPage').then((m) => ({ default: m.ConnectionPage })),
);
const ActivityPage = lazy(() => import('../features/activity/ActivityPage').then((m) => ({ default: m.ActivityPage })));
const ApplicationsPage = lazy(() =>
  import('../features/registry/ApplicationsPage').then((m) => ({ default: m.ApplicationsPage })),
);
const CredentialsPage = lazy(() =>
  import('../features/registry/CredentialsPage').then((m) => ({ default: m.CredentialsPage })),
);
const DocsPage = lazy(() => import('../features/docs/DocsPage').then((m) => ({ default: m.DocsPage })));
const AgentsPage = lazy(() => import('../features/docs/AgentsPage').then((m) => ({ default: m.AgentsPage })));
const AccountsPage = lazy(() =>
  import('../features/accounts/AccountsPage').then((m) => ({ default: m.AccountsPage })),
);

export function Console({ identity }: { identity: Identity }) {
  const { t } = useI18n();
  const signOut = useSignOut();
  const wide = useMediaQuery(WIDE);
  const client = useQueryClient();
  const [location, navigate] = useLocation();

  const [menu, setMenu] = useState(false);
  const [connecting, setConnecting] = useState(false);

  // Counts for the rail. These are the same queries the pages read, so the rail costs no request of
  // its own and stays in step with whatever is on screen.
  const applications = useApplications();
  const credentials = useCredentials();
  const fetching = useIsFetching() > 0;

  const openId = location.page === 'dashboard' ? location.id : undefined;

  useEffect(() => {
    document.title = `${t(PAGE_TITLE[location.page])} · Janus`;
  }, [location.page, t]);

  // The rail closes behind a choice, and a page is always entered from its list rather than on top
  // of the record that was open.
  const go = useCallback(
    (page: Page) => {
      navigate({ page });
      setMenu(false);
    },
    [navigate],
  );

  const nav = {
    page: location.page,
    onNavigate: go,
    identity,
    onSignOut: () =>
      void signOut
        .mutateAsync()
        .then(() => window.history.replaceState(null, '', '/'))
        .catch(() => undefined),
    // What each destination holds, withheld until the first load lands: a rail that counts to zero
    // and then corrects itself reads as an empty install for as long as the request takes. The
    // dashboard and the log carry no figure, because neither is a collection.
    counts: {
      applications: applications.data?.length,
      credentials: credentials.data?.length,
    },
  };

  return (
    <div>
      {/*
       * The rail names seven destinations and the bar three more, all of them before the page in
       * reading order: without this, reaching the first row of a table from the keyboard costs a
       * dozen tab stops on every navigation. Rendered rather than conditional, so it is in the tab
       * order from the first keystroke, and moved off the top of the window until it is focused.
       */}
      <a
        href="#content"
        className="btn btn-secondary fixed left-4 top-3 z-50 -translate-y-[calc(100%+1rem)] shadow-overlay transition-transform focus-visible:translate-y-0"
      >
        {t('nav.skip')}
      </a>

      <Sidebar {...nav} />
      {menu && !wide && <NavDrawer {...nav} onClose={() => setMenu(false)} />}

      <div className="min-h-svh lg:pl-64">
        <header className="sticky top-0 z-30 bg-surface">
          <div className="mx-auto flex max-w-[85rem] items-center gap-3 px-4 py-3 md:px-6">
            {!wide && (
              <>
                <button
                  className="btn btn-secondary aspect-square px-0"
                  aria-label={t('nav.open')}
                  aria-expanded={menu}
                  onClick={() => setMenu(true)}
                >
                  <Menu size={17} strokeWidth={2} />
                </button>
                <Wordmark />
              </>
            )}

            <div className="ml-auto flex items-center gap-2">
              <NotificationsMenu onNavigate={() => go('credentials')} />
              <button
                className="btn btn-secondary max-md:aspect-square max-md:px-0"
                onClick={() => void client.invalidateQueries()}
                disabled={fetching}
                aria-label={t('common.refresh')}
              >
                <RefreshCw size={14} strokeWidth={2.25} className={fetching ? 'animate-spin' : undefined} />
                <span className="max-md:sr-only">{t('common.refresh')}</span>
              </button>
              <SettingsMenu />
            </div>
          </div>

          <div className="h-0.5 bg-line" />
        </header>

        <main id="content" tabIndex={-1} className="mx-auto max-w-[85rem] px-4 py-7 md:px-6 md:py-9">
          <Suspense fallback={<PageSkeleton />}>
            {location.page === 'dashboard' &&
              (openId ? (
                <ConnectionPage
                  identity={identity}
                  id={openId}
                  onBack={() => navigate({ page: 'dashboard' })}
                  onFix={go}
                />
              ) : (
                <DashboardPage
                  onOpen={(id) => navigate({ page: 'dashboard', id })}
                  onConnect={() => (identity.role === 'USER' ? go('credentials') : setConnecting(true))}
                  onNavigate={go}
                />
              ))}

            {location.page === 'activity' && <ActivityPage />}
            {location.page === 'applications' && <ApplicationsPage />}
            {location.page === 'credentials' && <CredentialsPage identity={identity} />}
            {location.page === 'accounts' && <AccountsPage identity={identity} />}
            {location.page === 'documentation' && <DocsPage />}
            {location.page === 'agents' && <AgentsPage />}
          </Suspense>
        </main>
      </div>

      {connecting && identity.role !== 'USER' && (
        <Suspense fallback={null}>
          <ConnectFlow
            onClose={() => setConnecting(false)}
            onDone={() => {
              setConnecting(false);
              navigate({ page: 'credentials' });
            }}
          />
        </Suspense>
      )}
    </div>
  );
}
