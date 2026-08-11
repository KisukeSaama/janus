import { useState } from 'react';

import { useAudit, type Audit } from '../../api';
import {
  Block,
  DataTable,
  Empty,
  Notice,
  Outcome,
  Pager,
  RecordCell,
  SkeletonRows,
  type Column,
} from '../../components';
import { useI18n } from '../../i18n';
import { useErrorMessage } from '../../lib/errors';

/** Shared with the recent-decisions band on the connections page, so the two cost one request. */
export const AUDIT_PAGE_SIZE = 50;

const OUTCOMES = ['ALL', 'SUCCESS', 'DENIED', 'THROTTLED', 'ERROR'];

/**
 * The log is paged and filtered on the server. An audit trail that only ever shows the most recent
 * hundred events is not an audit trail; an investigation starts from a correlation identifier and
 * has to be able to walk backwards.
 */
export function AuditLog() {
  const { t, tEnum, formatTimestamp } = useI18n();
  const describe = useErrorMessage();
  const [page, setPage] = useState(0);
  const [outcome, setOutcome] = useState('ALL');

  const events = useAudit(page, AUDIT_PAGE_SIZE, outcome === 'ALL' ? undefined : outcome);
  const rows = events.data?.content ?? [];

  const columns: Column<Audit>[] = [
    {
      key: 'time',
      label: t('audit.colTime'),
      nowrap: true,
      cell: (r) => <span className="data">{formatTimestamp(r.occurredAt)}</span>,
    },
    {
      key: 'action',
      label: t('audit.colAction'),
      primary: true,
      cell: (r) => (
        <RecordCell
          name={tEnum('action', r.action)}
          note={
            r.detail && (
              <span className="block max-w-[36ch] truncate" title={r.detail}>
                {r.detail}
              </span>
            )
          }
        />
      ),
    },
    { key: 'actor', label: t('audit.colActor'), nowrap: true, cell: (r) => tEnum('actor', r.actorType) },
    {
      key: 'outcome',
      label: t('audit.colOutcome'),
      badge: true,
      nowrap: true,
      cell: (r) => <Outcome value={r.outcome} />,
    },
    {
      key: 'request',
      label: t('audit.colRequest'),
      grow: true,
      cell: (r) =>
        r.requestMethod ? (
          <span className="data break-all">
            <span className="font-semibold text-text">{r.requestMethod}</span> {r.requestPath}
            {r.statusCode ? <span className="ml-2">{r.statusCode}</span> : null}
          </span>
        ) : (
          <span className="text-text-3">{t('common.notAvailable')}</span>
        ),
    },
    {
      key: 'correlation',
      label: t('audit.colCorrelation'),
      nowrap: true,
      cell: (r) => (
        <span className="data text-2xs" title={r.correlationId}>
          {r.correlationId.slice(0, 12)}
        </span>
      ),
    },
  ];

  return (
    <Block
      title={t('audit.title')}
      lead={t('audit.intro')}
      aside={
        <div
          className="scroll-x flex items-center gap-1 rounded-control border border-line bg-surface p-1"
          role="group"
          aria-label={t('audit.filter')}
        >
          {OUTCOMES.map((o) => (
            <button
              key={o}
              onClick={() => {
                setOutcome(o);
                setPage(0);
              }}
              aria-pressed={outcome === o}
              className={`stamp flex min-h-7 shrink-0 items-center justify-center rounded-[3px] px-2.5 transition-colors duration-150 pointer-coarse:min-h-9 ${
                outcome === o ? 'bg-accent text-on-accent' : 'text-text-2 hover:bg-sunk hover:text-text'
              }`}
            >
              {o === 'ALL' ? t('audit.filterAll') : tEnum('outcome', o)}
            </button>
          ))}
        </div>
      }
    >
      {events.isError && <Notice>{describe(events.error)}</Notice>}

      {events.isPending ? (
        <SkeletonRows rows={8} cols={5} />
      ) : rows.length === 0 ? (
        <Empty
          headline={
            outcome === 'ALL'
              ? t('audit.emptyTitle')
              : t('audit.emptyFilteredTitle', { outcome: tEnum('outcome', outcome).toLowerCase() })
          }
          hint={outcome === 'ALL' ? t('audit.emptyHint') : t('audit.emptyFilteredHint')}
        />
      ) : (
        <>
          {/* Kept mounted while the next page is in flight: the table dimming beats it disappearing. */}
          <div aria-busy={events.isFetching}>
            <DataTable columns={columns} rows={rows} rowKey={(r) => r.id} />
          </div>
          {events.data && (
            <Pager
              page={events.data.page}
              totalPages={events.data.totalPages}
              totalElements={events.data.totalElements}
              busy={events.isFetching}
              onPage={setPage}
            />
          )}
        </>
      )}
    </Block>
  );
}
