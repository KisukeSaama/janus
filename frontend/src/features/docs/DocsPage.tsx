import { useEffect, useMemo, useState, type ReactNode } from 'react';

import { useApplications, useCredentials, useGrants, useProviders } from '../../api';
import { CopyButton, PageHead } from '../../components';
import { useI18n, type MessageKey } from '../../i18n';
import { buildConnections } from '../../lib/connections';

import { Bullet, Note, prose, Steps } from './parts';
import {
  environmentSample,
  firstRequestSample,
  gatewayUrl,
  PLACEHOLDER,
  samples,
  tokenSample,
  type SampleContext,
  type Snippet,
} from './samples';

/**
 * How to call an API through Janus, for the developer of the service that will call it.
 *
 * The console's other pages are for whoever registers a connection. This one is for whoever consumes
 * it, and it answers the four questions that follow being handed a key: what do I send, what may I
 * call, what comes back, and who refused when it fails. It is deliberately one page — a guide split
 * into eight of them is a guide nobody finishes — with a contents list on the side so the reader
 * lands on the question they arrived with.
 *
 * Every example is built from a connection that exists in this deployment, so a snippet is copied and
 * run rather than adapted. The one value never printed here is the API key: it is shown once, on the
 * screen that issues it, and a page anybody can open is not that screen.
 */

const SECTIONS = ['flow', 'need', 'first', 'code', 'call', 'token', 'handled', 'fail', 'sound'] as const;
type Section = (typeof SECTIONS)[number];

export function DocsPage() {
  const { t } = useI18n();

  // The same four queries every other page reads, so the guide costs no request of its own.
  const grants = useGrants();
  const applications = useApplications();
  const providers = useProviders();
  const credentials = useCredentials();

  const connections = useMemo(
    () =>
      buildConnections(grants.data ?? [], applications.data ?? [], providers.data ?? [], credentials.data ?? [])
        .filter((c) => c.provider)
        .sort((a, b) => a.grant.providerName.localeCompare(b.grant.providerName)),
    [grants.data, applications.data, providers.data, credentials.data],
  );

  const [chosen, setChosen] = useState('');
  const connection = connections.find((c) => c.id === chosen) ?? connections[0];

  const ctx: SampleContext = connection?.provider
    ? {
        origin: window.location.origin,
        slug: connection.provider.slug,
        applicationId: connection.grant.applicationId,
        // Any path reaches the destination, so the examples keep the placeholder rather than pretend
        // to know which one this API answers on.
        path: PLACEHOLDER.path,
      }
    : PLACEHOLDER;

  const active = useActiveSection();

  return (
    <>
      <PageHead section={t('nav.reference')} title={t('docs.title')} intro={t('docs.lead')} />

      <div className="gap-x-12 lg:grid lg:grid-cols-[minmax(0,1fr)_13rem] lg:items-start">
        <div className="min-w-0">
          {/* Which connection the examples speak for. Held open at one row, so the page does not
              reflow under the reader when the four queries land. */}
          <div className="panel mb-8 flex min-h-[3.75rem] flex-col gap-x-4 gap-y-2 px-4 py-3 sm:flex-row sm:items-center">
            <p className="stamp shrink-0 text-text-2">{t('docs.use.label')}</p>
            {connections.length === 0 ? (
              <p className="text-sm text-text-2">{grants.isPending ? '' : t('docs.use.none')}</p>
            ) : connections.length === 1 ? (
              <p className="text-sm">
                {t('detail.subtitle', {
                  app: connections[0].grant.applicationName,
                  api: connections[0].grant.providerName,
                })}
              </p>
            ) : (
              <select
                className="field sm:max-w-xs"
                aria-label={t('docs.use.label')}
                value={connection?.id ?? ''}
                onChange={(e) => setChosen(e.target.value)}
              >
                {connections.map((c) => (
                  <option key={c.id} value={c.id}>
                    {t('detail.subtitle', { app: c.grant.applicationName, api: c.grant.providerName })}
                  </option>
                ))}
              </select>
            )}
          </div>

          <div className="space-y-10">
            <Doc id="flow">
              <Steps
                items={[t('docs.flow.step1'), t('docs.flow.step2'), t('docs.flow.step3')]}
              />
              <Note>{t('docs.flow.note')}</Note>
            </Doc>

            <Doc id="need">
              <Terms
                items={[
                  { term: t('docs.need.address'), value: gatewayUrl(ctx), note: t('docs.need.addressNote') },
                  { term: t('docs.need.id'), value: ctx.applicationId, note: t('docs.need.idNote') },
                  { term: t('docs.need.key'), note: t('docs.need.keyNote') },
                ]}
              />
              <Code snippet={environmentSample(ctx)} />
              <Note>{t('docs.need.env')}</Note>
            </Doc>

            <Doc id="first">
              <Code snippet={firstRequestSample(ctx)} />
              <Note>{t('docs.first.note')}</Note>
            </Doc>

            <Doc id="code">
              <Tabs ctx={ctx} />
              <Note>{t('docs.code.note')}</Note>
            </Doc>

            <Doc id="call">
              <Terms
                items={[
                  {
                    term: t('docs.call.pathTerm'),
                    note: t('docs.call.pathNote', { gateway: `/gateway/${ctx.slug}` }),
                  },
                  { term: t('docs.call.reachTerm'), note: t('docs.call.reachNote') },
                  { term: t('docs.call.methodTerm'), note: t('docs.call.methodNote') },
                  { term: t('docs.call.queryTerm'), note: t('docs.call.queryNote') },
                  { term: t('docs.call.bodyTerm'), note: t('docs.call.bodyNote') },
                  { term: t('docs.call.refusedTerm'), note: t('docs.call.refusedNote') },
                ]}
              />
            </Doc>

            <Doc id="token">
              <Code snippet={tokenSample(ctx)} />
              <div className="mt-4">
                <Terms
                  items={[
                    { term: t('docs.token.exchangeTerm'), note: t('docs.token.exchangeNote') },
                    { term: t('docs.token.useTerm'), note: t('docs.token.useNote') },
                    { term: t('docs.token.lifeTerm'), note: t('docs.token.lifeNote') },
                    { term: t('docs.token.browserTerm'), note: t('docs.token.browserNote') },
                  ]}
                />
              </div>
              <Note>{t('docs.token.note')}</Note>
            </Doc>

            <Doc id="handled">
              <Terms
                mono
                items={[
                  { term: 'X-Janus-Cache', note: t('docs.handled.cache') },
                  { term: 'Age', note: t('docs.handled.age') },
                  { term: 'X-Janus-RateLimit-Limit / -Remaining / -Reset', note: t('docs.handled.limit') },
                  { term: 'X-Janus-Upstream-Attempts', note: t('docs.handled.attempts') },
                  { term: 'Retry-After', note: t('docs.handled.retryAfter') },
                  { term: 'X-Janus-Correlation-Id', note: t('docs.handled.correlation') },
                ]}
              />
              <ul className="mt-4 space-y-2">
                <Bullet>{t('docs.handled.retries')}</Bullet>
                <Bullet>{t('docs.handled.stripped')}</Bullet>
                <Bullet>{t('docs.handled.timeout')}</Bullet>
              </ul>
            </Doc>

            <Doc id="fail">
              <Terms
                mono
                items={[
                  { term: '400', note: t('docs.fail.s400') },
                  { term: '401', note: t('docs.fail.s401') },
                  { term: '403', note: t('docs.fail.s403') },
                  { term: '404', note: t('docs.fail.s404') },
                  { term: '405', note: t('docs.fail.s405') },
                  { term: '413', note: t('docs.fail.s413') },
                  { term: '429', note: t('docs.fail.s429') },
                  { term: '502', note: t('docs.fail.s502') },
                ]}
              />
              <Note>{t('docs.fail.correlation')}</Note>
            </Doc>

            <Doc id="sound">
              <ul className="space-y-2">
                <Bullet>{t('docs.sound.one')}</Bullet>
                <Bullet>{t('docs.sound.narrow')}</Bullet>
                <Bullet>{t('docs.sound.rotate')}</Bullet>
                <Bullet>{t('docs.sound.browser')}</Bullet>
                <Bullet>{t('docs.sound.environments')}</Bullet>
              </ul>
            </Doc>
          </div>
        </div>

        <Contents active={active} />
      </div>
    </>
  );
}

/* ── The page's own parts ──────────────────────────────────────────────── */

/** A section, its heading, and the anchor the contents list points at. */
function Doc({ id, children }: { id: Section; children: ReactNode }) {
  const { t } = useI18n();
  return (
    <section id={anchor(id)} aria-labelledby={`${anchor(id)}-title`} className="scroll-mt-24">
      <h2 id={`${anchor(id)}-title`} className="border-t border-line pt-6 text-lg font-semibold tracking-title">
        {t(`docs.${id}.title` as MessageKey)}
      </h2>
      <p className="mb-5 mt-2 max-w-[68ch] text-sm text-text-2">{t(`docs.${id}.lead` as MessageKey)}</p>
      {children}
    </section>
  );
}

const anchor = (id: Section) => `guide-${id}`;

/**
 * A term and what it means. Two columns where a table would fit and one where it would not: a
 * six-row reference behind a horizontal scrollbar is not a reference.
 */
function Terms({
  items,
  mono = false,
}: {
  items: { term: string; value?: string; note: string }[];
  mono?: boolean;
}) {
  return (
    <dl className="panel divide-y divide-line">
      {items.map((item) => (
        <div key={item.term} className="grid gap-x-6 gap-y-1 px-4 py-3.5 lg:grid-cols-[16rem_minmax(0,1fr)]">
          <dt className={`min-w-0 text-sm font-medium ${mono ? 'data break-words text-text' : ''}`}>{item.term}</dt>
          <dd className="min-w-0 text-sm text-text-2">
            {item.value && <p className="data mb-1 break-all text-xs text-text">{item.value}</p>}
            {prose(item.note)}
          </dd>
        </div>
      ))}
    </dl>
  );
}

/**
 * One block of code, with the file it belongs in and the one thing anybody wants to do with it.
 *
 * Comment lines are dimmed and nothing else is coloured. A full highlighter is a dependency and a
 * theme of its own; what a reader actually needs is to tell the sentence explaining the code from
 * the code, and a whole-line rule cannot mistake a URL for a comment the way a token rule can.
 */
function Code({ snippet }: { snippet: Snippet }) {
  return (
    <div className="panel overflow-hidden">
      <div className="flex items-center gap-3 border-b border-line px-3 py-2">
        <p className="data min-w-0 flex-1 truncate text-2xs text-text-2">{snippet.file}</p>
        <CopyButton value={snippet.code} label={snippet.file} />
      </div>
      <pre className="data scroll-x px-4 py-3.5 text-xs leading-[1.6]">
        <code>
          {snippet.code.split('\n').map((line, index) => (
            <span key={index} className={isComment(line) ? 'block text-text-3' : 'block'}>
              {line || ' '}
            </span>
          ))}
        </code>
      </pre>
    </div>
  );
}

function isComment(line: string): boolean {
  const trimmed = line.trimStart();
  return trimmed.startsWith('//') || trimmed.startsWith('#');
}

/** The same call, in the runtimes this deployment is called from. */
function Tabs({ ctx }: { ctx: SampleContext }) {
  const { t } = useI18n();
  const all = samples(ctx);
  const [chosen, setChosen] = useState(all[0].id);
  const sample = all.find((s) => s.id === chosen) ?? all[0];

  return (
    <div>
      <div className="scroll-x mb-3 flex items-center gap-1 rounded-control border border-line bg-surface p-1" role="group" aria-label={t('docs.code.language')}>
        {all.map((s) => (
          <button
            key={s.id}
            onClick={() => setChosen(s.id)}
            aria-pressed={s.id === sample.id}
            className={`stamp flex min-h-7 shrink-0 items-center justify-center rounded-[3px] px-2.5 transition-colors pointer-coarse:min-h-9 ${
              s.id === sample.id ? 'bg-accent text-on-accent' : 'text-text-2 hover:bg-sunk hover:text-text'
            }`}
          >
            {s.label}
          </button>
        ))}
      </div>

      <div className="space-y-3">
        {sample.snippets.map((snippet) => (
          <Code key={snippet.file} snippet={snippet} />
        ))}
      </div>
    </div>
  );
}

/** Where you are in the guide, and everywhere else you can be. Wide screens only: on a phone the
    contents list is one more screen between the reader and the first sentence. */
function Contents({ active }: { active: Section }) {
  const { t } = useI18n();
  return (
    <nav aria-label={t('docs.onThisPage')} className="sticky top-24 hidden lg:block">
      <p className="stamp mb-2.5 text-text-3">{t('docs.onThisPage')}</p>
      <ul className="space-y-0.5 border-l border-line">
        {SECTIONS.map((id) => (
          <li key={id}>
            <a
              href={`#${anchor(id)}`}
              aria-current={id === active ? 'location' : undefined}
              className={`-ml-px block border-l py-1 pl-3 text-sm transition-colors ${
                id === active
                  ? 'border-accent text-accent-text'
                  : 'border-transparent text-text-2 hover:border-line-strong hover:text-text'
              }`}
            >
              {t(`docs.${id}.title` as MessageKey)}
            </a>
          </li>
        ))}
      </ul>
    </nav>
  );
}

/**
 * Which section the reader is in. The band is the top third of the viewport, so a heading marks its
 * section as current from the moment it settles under the bar rather than when it leaves the screen.
 */
function useActiveSection(): Section {
  const [active, setActive] = useState<Section>(SECTIONS[0]);

  useEffect(() => {
    const seen = new Map<string, boolean>();
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) seen.set(entry.target.id, entry.isIntersecting);
        const current = SECTIONS.find((id) => seen.get(anchor(id)));
        if (current) setActive(current);
      },
      { rootMargin: '-88px 0px -66% 0px' },
    );

    for (const id of SECTIONS) {
      const element = document.getElementById(anchor(id));
      if (element) observer.observe(element);
    }
    return () => observer.disconnect();
  }, []);

  return active;
}
