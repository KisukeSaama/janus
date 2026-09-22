import { useMemo, useState } from 'react';
import { Download } from 'lucide-react';

import {
  useAgentFile,
  useApplications,
  useMcpConnections,
  useMcpServer,
  useRevokeMcpConnection,
  type Identity,
  type McpConnection,
} from '../../api';
import {
  Block,
  ConfirmAction,
  CopyField,
  DataTable,
  Empty,
  Notice,
  PageHead,
  RecordCell,
  SkeletonRows,
  type Column,
} from '../../components';
import { useI18n } from '../../i18n';
import { useErrorMessage } from '../../lib/errors';

import { AGENT_FILE_NAME } from './agentFile';
import { Bullet, Note, prose, Section, Steps } from './parts';

/**
 * AI coding: the two ways a model meets Janus, on one page because a reader should not have to
 * guess which of two pages is theirs.
 *
 * They go in opposite directions. The file is for the agent working in a repository: it is told
 * that calls go through a gateway, with two headers, and that nobody there holds Spotify's secret.
 * The assistant over MCP works in the console instead: it registers the APIs, services and grants
 * the file then lists. The page opens by saying exactly that, side by side, and names the one tool
 * where the two meet — `get_janus_md`, the same file as the download, written by the same server.
 *
 * What neither ever carries is said once, at the end, for both: it is one boundary, and two lists
 * of it on two pages were the same promise worded twice.
 */
export function AgentsPage({ identity }: { identity: Identity }) {
  const { t } = useI18n();

  return (
    <>
      <PageHead section={t('nav.reference')} title={t('agents.title')} intro={t('agents.lead')} />

      <div className="max-w-[72ch] space-y-10">
        <Split />
        <FileHalf />
        <AssistantHalf administrator={identity.role !== 'USER'} />

        <Section title={t('agents.never.title')} lead={t('agents.never.lead')}>
          <ul className="space-y-2">
            <Bullet>{t('agents.never.key')}</Bullet>
            <Bullet>{t('agents.never.id')}</Bullet>
            <Bullet>{t('agents.never.secrets')}</Bullet>
            <Bullet>{t('agents.never.keys')}</Bullet>
            <Bullet>{t('agents.never.stale')}</Bullet>
          </ul>
        </Section>
      </div>

      <div className="mt-10">
        <Connections />
      </div>
    </>
  );
}

/**
 * The nuance, before anything else: where each one works, and what it does there. Each half is a
 * link to its part of the page, so the reader who knows which one they came for goes straight to it.
 */
function Split() {
  const { t } = useI18n();
  const halves = [
    { href: '#file', where: t('agents.split.fileWhere'), name: AGENT_FILE_NAME, text: t('agents.split.file') },
    { href: '#assistant', where: t('agents.split.mcpWhere'), name: 'MCP', text: t('agents.split.mcp') },
  ];

  return (
    <section className="panel">
      <div className="grid divide-y divide-line sm:grid-cols-2 sm:divide-x sm:divide-y-0">
        {halves.map((half) => (
          <a key={half.href} href={half.href} className="group block px-4 py-4 hover:bg-sunk sm:px-5">
            <p className="stamp text-text-2">{half.where}</p>
            <p className="data mt-1.5 text-sm font-medium text-accent-text group-hover:underline">{half.name}</p>
            <p className="mt-1.5 text-sm text-text-2">{half.text}</p>
          </a>
        ))}
      </div>
      <p className="border-t border-line px-4 py-3 text-sm text-text-2 sm:px-5">{prose(t('agents.split.bridge'))}</p>
    </section>
  );
}

/**
 * The file, written for one calling service, because that is what a repository is: one application
 * id, and the APIs that service is allowed to reach. Choosing the service is therefore the only
 * question this half asks. The content is the server's to write — the same one an assistant fetches
 * for itself over MCP, so the two can never disagree.
 *
 * It is offered as a download rather than a block to copy: it is a file in the end, longer than
 * anything worth reading on a screen, and a `JANUS.md` that arrives named and complete is one step.
 */
function FileHalf() {
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
      <Section id="file" title={t('agents.file.title')} lead={t('agents.file.lead')}>
        {agentFile.isError && <Notice>{describe(agentFile.error)}</Notice>}

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
        <div className="panel mt-3 px-4 py-4 sm:px-5">
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
        </div>
      </Section>

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
    </>
  );
}

/**
 * The assistant let into the console itself, over MCP, to register what it would otherwise ask
 * somebody to click through. It acts as whoever approved it, with that person's role, which is why
 * this page ends on the list of assistants let in, and the way to put one out.
 */
function AssistantHalf({ administrator }: { administrator: boolean }) {
  const { t } = useI18n();
  const describe = useErrorMessage();
  const server = useMcpServer();

  // What a command prints while the address is still on its way, so the block keeps its shape.
  const url = server.data?.url ?? '…';

  return (
    <Section id="assistant" title={t('mcp.title')} lead={t('mcp.lead')}>
      {server.isError && <Notice>{describe(server.error)}</Notice>}

      <div className="panel px-4 py-4 sm:px-5">
        <CopyField label={t('mcp.serverLabel')} value={url} />
        <Note>{t('mcp.serverNote')}</Note>
      </div>

      <h3 className="mb-2.5 mt-7 text-sm font-semibold">{t('mcp.setup.codeTitle')}</h3>
      <CopyField label={t('mcp.setup.codeLabel')} value={`claude mcp add --transport http janus ${url}`} block />
      <Note>{t('mcp.setup.codeNote')}</Note>

      <h3 className="mb-2.5 mt-7 text-sm font-semibold">{t('mcp.setup.desktopTitle')}</h3>
      <Steps items={[t('mcp.setup.desktop1'), t('mcp.setup.desktop2'), t('mcp.setup.desktop3')]} />

      <h3 className="mb-2.5 mt-7 text-sm font-semibold">{t('mcp.setup.otherTitle')}</h3>
      <p className="max-w-[72ch] text-sm text-text-2">{prose(t('mcp.setup.other'))}</p>

      <h3 className="mb-2.5 mt-7 text-sm font-semibold">{t('mcp.can.title')}</h3>
      <ul className="space-y-2">
        <Bullet>{administrator ? t('mcp.can.apisAdmin') : t('mcp.can.apisUser')}</Bullet>
        <Bullet>{t('mcp.can.services')}</Bullet>
        <Bullet>{t('mcp.can.grants')}</Bullet>
        <Bullet>{t('mcp.can.credentials')}</Bullet>
        <Bullet>{t('mcp.can.ping')}</Bullet>
        <Bullet>{t('mcp.can.file')}</Bullet>
      </ul>
    </Section>
  );
}

/**
 * The assistants this account has let in, and the one thing to do with each. A row prints one verb:
 * there is nothing to edit about a delegation, only whether it still stands.
 */
function Connections() {
  const { t, formatDate, formatAge } = useI18n();
  const describe = useErrorMessage();
  const connections = useMcpConnections();
  const revoke = useRevokeMcpConnection();
  const [error, setError] = useState('');

  const rows = [...(connections.data ?? [])].sort((a, b) => b.createdAt.localeCompare(a.createdAt));

  const columns: Column<McpConnection>[] = [
    {
      key: 'client',
      label: t('mcp.colClient'),
      primary: true,
      grow: true,
      // The name is the client's own claim, so it is quoted here as it is on the consent screen.
      cell: (r) => <RecordCell name={`“${r.clientName}”`} note={t('mcp.authorizedOn', { date: formatDate(r.createdAt) })} />,
    },
    {
      key: 'used',
      label: t('mcp.colLastUsed'),
      nowrap: true,
      cell: (r) => (r.lastUsedAt ? formatAge(r.lastUsedAt) : t('mcp.neverUsed')),
    },
    {
      key: 'expires',
      label: t('mcp.colExpires'),
      nowrap: true,
      cell: (r) => formatDate(r.expiresAt),
    },
  ];

  return (
    <Block title={t('mcp.connections.title')} lead={t('mcp.connections.lead')}>
      {error && <Notice>{error}</Notice>}
      {connections.isError && <Notice>{describe(connections.error)}</Notice>}
      {connections.isPending ? (
        <SkeletonRows rows={2} cols={3} />
      ) : rows.length === 0 ? (
        <Empty headline={t('mcp.connections.emptyTitle')} hint={t('mcp.connections.emptyHint')} />
      ) : (
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(r) => r.id}
          actions={(r) => (
            <ConfirmAction
              trigger={t('mcp.revoke')}
              title={t('mcp.revokeTitle', { name: r.clientName })}
              confirm={t('mcp.revokeConfirm')}
              pending={t('mcp.revoking')}
              description={t('mcp.revokeConsequence')}
              destructive
              onConfirm={async () => {
                setError('');
                try {
                  await revoke.mutateAsync(r.id);
                } catch (x) {
                  setError(describe(x));
                }
              }}
            />
          )}
        />
      )}
    </Block>
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
