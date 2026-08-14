import { useState } from 'react';
import { Download } from 'lucide-react';

import { useAudit, useAuditExport, type Audit, type AuditFilter } from '../../api';
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
import { useI18n, type MessageKey } from '../../i18n';
import { useErrorMessage } from '../../lib/errors';

/** Shared with the recent-decisions band on the connections page, so the two cost one request. */
export const AUDIT_PAGE_SIZE = 50;

/** What one export carries, matching the ceiling the API enforces. */
const EXPORT_LIMIT = 10_000;

const OUTCOMES = ['ALL', 'SUCCESS', 'DENIED', 'THROTTLED', 'ERROR'];

/** How far back the quick choices reach. `CUSTOM` opens the two fields; `ALL` removes the bound. */
type Preset = 'ALL' | 'H1' | 'H24' | 'D7' | 'D30' | 'CUSTOM';
const PRESETS: { key: Preset; label: MessageKey }[] = [
  { key: 'ALL', label: 'audit.rangeAll' },
  { key: 'H1', label: 'audit.rangeHour' },
  { key: 'H24', label: 'audit.rangeDay' },
  { key: 'D7', label: 'audit.rangeWeek' },
  { key: 'D30', label: 'audit.rangeMonth' },
  { key: 'CUSTOM', label: 'audit.rangeCustom' },
];
const PRESET_HOURS: Partial<Record<Preset, number>> = { H1: 1, H24: 24, D7: 24 * 7, D30: 24 * 30 };

/**
 * The log is paged and filtered on the server. An audit trail that only ever shows the most recent
 * hundred events is not an audit trail; an investigation starts from a correlation identifier and
 * has to be able to walk backwards.
 *
 * <p>It narrows by time as well as by outcome, and the window it is narrowed to is what an export
 * carries: the file answers the same question the table on screen does.
 */
export function AuditLog() {
  const { t, tEnum, formatTimestamp, formatNumber } = useI18n();
  const describe = useErrorMessage();
  const [page, setPage] = useState(0);
  const [outcome, setOutcome] = useState('ALL');
  // The preset's lower bound is stamped when it is picked rather than derived on every render: a
  // bound that slides underneath the reader would renumber the pages they are walking through.
  const [range, setRange] = useState<{ preset: Preset; from?: string }>({ preset: 'ALL' });
  const [since, setSince] = useState('');
  const [until, setUntil] = useState('');

  const custom = range.preset === 'CUSTOM';
  const filter: AuditFilter = {
    outcome: outcome === 'ALL' ? undefined : outcome,
    from: custom ? instant(since) : range.from,
    to: custom ? instant(until) : undefined,
  };
  const narrowed = Boolean(filter.outcome || filter.from || filter.to);

  const events = useAudit(page, AUDIT_PAGE_SIZE, filter);
  const exporting = useAuditExport();
  const rows = events.data?.content ?? [];

  function choose(preset: Preset) {
    const hours = PRESET_HOURS[preset];
    setRange({ preset, from: hours ? new Date(Date.now() - hours * 3_600_000).toISOString() : undefined });
    setPage(0);
  }

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
              className={`stamp flex min-h-7 shrink-0 items-center justify-center rounded-[3px] px-2.5 transition-colors pointer-coarse:min-h-9 ${
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
      {exporting.isError && <Notice>{describe(exporting.error)}</Notice>}

      {/* Kept above the table and outside its loading states: an empty window is the one a reader
          most needs to widen, so the controls that widen it never leave with the rows. */}
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <select
          className="field w-auto"
          aria-label={t('audit.rangeLabel')}
          value={range.preset}
          onChange={(e) => choose(e.target.value as Preset)}
        >
          {PRESETS.map((preset) => (
            <option key={preset.key} value={preset.key}>
              {t(preset.label)}
            </option>
          ))}
        </select>

        {custom && (
          <>
            <label className="stamp flex items-center gap-2 text-text-2">
              {t('audit.rangeFrom')}
              <input
                type="datetime-local"
                className="field w-auto"
                value={since}
                onChange={(e) => {
                  setSince(e.target.value);
                  setPage(0);
                }}
              />
            </label>
            <label className="stamp flex items-center gap-2 text-text-2">
              {t('audit.rangeTo')}
              <input
                type="datetime-local"
                className="field w-auto"
                value={until}
                onChange={(e) => {
                  setUntil(e.target.value);
                  setPage(0);
                }}
              />
            </label>
          </>
        )}

        <div className="ml-auto flex items-center gap-3">
          {(events.data?.totalElements ?? 0) > EXPORT_LIMIT && (
            <p className="text-xs text-text-2">{t('audit.exportLimit', { limit: formatNumber(EXPORT_LIMIT) })}</p>
          )}
          <button
            type="button"
            className="btn btn-secondary"
            onClick={() => exporting.mutate(filter)}
            disabled={exporting.isPending || rows.length === 0}
          >
            <Download size={14} />
            {exporting.isPending ? t('audit.exporting') : t('audit.export')}
          </button>
        </div>
      </div>

      {events.isPending ? (
        <SkeletonRows rows={8} cols={6} />
      ) : rows.length === 0 ? (
        <Empty
          headline={
            filter.outcome
              ? t('audit.emptyFilteredTitle', { outcome: tEnum('outcome', outcome).toLowerCase() })
              : narrowed
                ? t('audit.emptyRangeTitle')
                : t('audit.emptyTitle')
          }
          hint={narrowed ? t('audit.emptyFilteredHint') : t('audit.emptyHint')}
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
              unit={t('pager.events')}
              // Ordered newest first, so the page before this one is the newer one: position labels
              // would point backwards in the list and forwards in time.
              previous={t('audit.newer')}
              next={t('audit.older')}
              busy={events.isFetching}
              onPage={setPage}
            />
          )}
        </>
      )}
    </Block>
  );
}

/** A `datetime-local` value, read in the reader's own zone, as the instant the API filters on. */
function instant(local: string): string | undefined {
  if (!local) return undefined;
  const at = new Date(local);
  return Number.isNaN(at.getTime()) ? undefined : at.toISOString();
}
