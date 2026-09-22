import { useMemo, useState } from 'react';
import { Download } from 'lucide-react';

import { useAgentFile, useApplications } from '../../api';
import { Notice, PageHead } from '../../components';
import { useI18n } from '../../i18n';
import { useErrorMessage } from '../../lib/errors';
import { toPath } from '../../app/routes';

import { AGENT_FILE_NAME } from './agentFile';
import { Bullet, Note, prose, Section, Steps } from './parts';

/**
 * The page for the developer whose next line of code will be written by an agent.
 *
 * Everything else under Reference is read by a person. This is read once, to fetch a file, and the
 * file is what does the work afterwards: dropped in a repository, it is the context Claude Code or
 * Codex is missing when it is told "fetch the playlists from Spotify" — that the call goes through a
 * gateway, with two headers, and that nobody in this repository ever holds Spotify's secret.
 *
 * The file is offered as a download rather than a block to copy. It is a file in the end, it is
 * longer than anything worth reading on a screen, and a `JANUS.md` that arrives named and complete
 * is one step, where selecting a code block and pasting it into a new file is three.
 *
 * It is written for one calling service, because that is what a repository is: one application id,
 * and the APIs that service is allowed to reach. Choosing the service is therefore the only question
 * this page asks. The file itself is the server's to write: the same one an assistant connected over
 * MCP fetches for itself, so the two can never disagree.
 */
export function AgentsPage({ onOpenMcp }: { onOpenMcp: () => void }) {
  const { t, tc, formatNumber } = useI18n();
  const describe = useErrorMessage();

  // The services list every other page reads; the file itself is one request per service chosen.
  const applications = useApplications();

  const services = useMemo(
    () => [...(applications.data ?? [])].sort((a, b) => a.name.localeCompare(b.name)),
    [applications.data],
  );

  const [chosen, setChosen] = useState('');
  const service = services.find((s) => s.id === chosen) ?? services[0];

  // Held back until the services have landed, so the placeholder file is not fetched on the way to
  // the real one. With no service at all, the placeholder is the file.
  const agentFile = useAgentFile(service?.id, !applications.isPending);
  const file = agentFile.data;
  const apiCount = file?.apiCount ?? 0;

  return (
    <>
      <PageHead section={t('nav.reference')} title={t('agents.title')} intro={t('agents.lead')} />
      {agentFile.isError && <Notice>{describe(agentFile.error)}</Notice>}

      <div className="max-w-[72ch] space-y-10">
        {/* Which service the file speaks for. Held open at one row so the page does not reflow under
            the reader when the services land. */}
        <div className="panel flex min-h-[3.75rem] flex-col gap-x-4 gap-y-2 px-4 py-3 sm:flex-row sm:items-center">
          <p className="stamp shrink-0 text-text-2">{t('agents.for')}</p>
          {services.length === 0 ? (
            <p className="text-sm text-text-2">{applications.isPending ? '' : t('agents.noService')}</p>
          ) : services.length === 1 ? (
            <p className="text-sm">{services[0].name}</p>
          ) : (
            <select
              className="field sm:max-w-xs"
              aria-label={t('agents.for')}
              value={service?.id ?? ''}
              onChange={(e) => setChosen(e.target.value)}
            >
              {services.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          )}
        </div>

        {/* The file itself: what it is, and the one thing to do with it. */}
        <section className="panel px-4 py-4 sm:px-5">
          <div className="flex flex-col gap-x-6 gap-y-4 sm:flex-row sm:items-center sm:justify-between">
            <div className="min-w-0">
              <p className="data text-sm font-medium">{file?.fileName ?? AGENT_FILE_NAME}</p>
              {/* Held at its line while the file loads, so the button beside it does not jump. */}
              <p className="mt-1 min-h-5 text-sm text-text-2">
                {!file ? '' : apiCount === 0 ? t('agents.fileEmpty') : tc('agents.fileApis', apiCount)}
              </p>
            </div>
            <button
              className="btn btn-primary shrink-0"
              disabled={!file}
              onClick={() => file && download(file.fileName, file.content)}
            >
              <Download size={15} strokeWidth={2.25} />
              {t('agents.download')}
            </button>
          </div>
          {file && <Note>{t('agents.fileNote', { tokens: formatNumber(estimateTokens(file.content)) })}</Note>}

          {/*
            The same file, without the download: an assistant connected over MCP asks for it by name.
            A real link, so it can be opened in a new tab, and the console's own navigator on a click.
          */}
          <p className="mt-3.5 max-w-[72ch] border-t border-line pt-3.5 text-sm text-text-2">
            {prose(t('agents.mcpNote'))}{' '}
            <a
              href={toPath({ page: 'mcp' })}
              className="text-accent-text underline underline-offset-2 hover:no-underline"
              onClick={(e) => {
                if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
                e.preventDefault();
                onOpenMcp();
              }}
            >
              {t('agents.mcpLink')}
            </a>
          </p>
        </section>

        <Section title={t('agents.holds.title')} lead={t('agents.holds.lead')}>
          <ul className="space-y-2">
            <Bullet>{t('agents.holds.gateway')}</Bullet>
            <Bullet>{t('agents.holds.routes')}</Bullet>
            <Bullet>{t('agents.holds.handled')}</Bullet>
            <Bullet>{t('agents.holds.errors')}</Bullet>
            <Bullet>{t('agents.holds.missing')}</Bullet>
          </ul>
        </Section>

        <Section title={t('agents.place.title')} lead={t('agents.place.lead')}>
          <Steps items={[t('agents.place.step1'), t('agents.place.step2'), t('agents.place.step3')]} />
          <Note>{t('agents.place.note')}</Note>
        </Section>

        <Section title={t('agents.ask.title')} lead={t('agents.ask.lead')}>
          {/*
            Named by a caption rather than marked with a coloured stripe down its edge. The console
            has one way of saying "this block is a specimen", and it is the head `Review` and the
            code samples already use: a rule, a label, then the thing itself.
          */}
          <figure className="panel">
            <figcaption className="stamp border-b border-line px-4 py-2.5 text-accent-text">
              {t('agents.ask.exampleLabel')}
            </figcaption>
            <blockquote className="px-4 py-3 text-sm">{t('agents.ask.example')}</blockquote>
          </figure>
          <Note>{t('agents.ask.note')}</Note>
        </Section>

        <Section title={t('agents.safe.title')} lead={t('agents.safe.lead')}>
          <ul className="space-y-2">
            <Bullet>{t('agents.safe.key')}</Bullet>
            <Bullet>{t('agents.safe.id')}</Bullet>
            <Bullet>{t('agents.safe.stale')}</Bullet>
          </ul>
        </Section>
      </div>
    </>
  );
}

/**
 * What the file will cost in the agent's context, to the nearest fifty.
 *
 * A file read on every task is a running cost, and the reader deciding whether to commit it is
 * entitled to know it. Four characters to the token is the usual rule for English prose, and rounding
 * says plainly that this is an order of magnitude rather than a measurement.
 */
function estimateTokens(file: string): number {
  return Math.round(file.length / 4 / 50) * 50;
}

/** A file the browser saves under the name it has to keep, since the agent looks for it by name. */
function download(name: string, content: string) {
  const url = URL.createObjectURL(new Blob([content], { type: 'text/markdown;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.click();
  URL.revokeObjectURL(url);
}
