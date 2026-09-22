import { useState } from 'react';

import { useMcpConnections, useMcpServer, useRevokeMcpConnection, type Identity, type McpConnection } from '../../api';
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

import { Bullet, Note, prose, Section, Steps } from './parts';

/**
 * Janus, handed to an AI assistant.
 *
 * The page beside this one gives a coding agent a file so it can call APIs through the gateway. This
 * one goes the other way: the assistant is let into the console itself, over MCP, to register the
 * APIs, services and subscriptions it would otherwise have to ask somebody to click through. It acts
 * as whoever approved it, with that person's role, and the journal writes its changes down under their
 * name — which is why the page ends on the list of assistants let in, and the way to put one out.
 *
 * What it cannot do is stated as plainly as what it can. The boundary this product exists to hold is
 * that secrets and keys are never shown to anything that could repeat them, and a language model is
 * exactly such a thing: it reads secret metadata, never a value, and never receives a service's key.
 */
export function McpPage({ identity }: { identity: Identity }) {
  const { t } = useI18n();
  const describe = useErrorMessage();
  const server = useMcpServer();

  // What a command prints while the address is still on its way, so the block keeps its shape.
  const url = server.data?.url ?? '…';
  const administrator = identity.role !== 'USER';

  return (
    <>
      <PageHead section={t('nav.reference')} title={t('mcp.title')} intro={t('mcp.lead')} />
      {server.isError && <Notice>{describe(server.error)}</Notice>}

      <div className="max-w-[72ch] space-y-10">
        <section className="panel px-4 py-4 sm:px-5">
          <CopyField label={t('mcp.serverLabel')} value={url} />
          <Note>{t('mcp.serverNote')}</Note>
        </section>

        <Section title={t('mcp.setup.title')} lead={t('mcp.setup.lead')}>
          <h3 className="mb-2.5 text-sm font-semibold">{t('mcp.setup.codeTitle')}</h3>
          <CopyField label={t('mcp.setup.codeLabel')} value={`claude mcp add --transport http janus ${url}`} block />
          <Note>{t('mcp.setup.codeNote')}</Note>

          <h3 className="mb-2.5 mt-7 text-sm font-semibold">{t('mcp.setup.desktopTitle')}</h3>
          <Steps items={[t('mcp.setup.desktop1'), t('mcp.setup.desktop2'), t('mcp.setup.desktop3')]} />

          <h3 className="mb-2.5 mt-7 text-sm font-semibold">{t('mcp.setup.otherTitle')}</h3>
          <p className="max-w-[72ch] text-sm text-text-2">{prose(t('mcp.setup.other'))}</p>
        </Section>

        <Section title={t('mcp.can.title')} lead={t('mcp.can.lead')}>
          <ul className="space-y-2">
            <Bullet>{administrator ? t('mcp.can.apisAdmin') : t('mcp.can.apisUser')}</Bullet>
            <Bullet>{t('mcp.can.services')}</Bullet>
            <Bullet>{t('mcp.can.grants')}</Bullet>
            <Bullet>{t('mcp.can.credentials')}</Bullet>
            <Bullet>{t('mcp.can.ping')}</Bullet>
            <Bullet>{t('mcp.can.file')}</Bullet>
          </ul>
        </Section>

        <Section title={t('mcp.never.title')} lead={t('mcp.never.lead')}>
          <ul className="space-y-2">
            <Bullet>{t('mcp.never.secrets')}</Bullet>
            <Bullet>{t('mcp.never.keys')}</Bullet>
            <Bullet>{t('mcp.never.created')}</Bullet>
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
