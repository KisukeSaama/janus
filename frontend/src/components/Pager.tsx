import { useI18n } from '../i18n';

/**
 * Where the reader is in a list, and the two ways out of it.
 *
 * <p>The unit is the caller's word, because `Page 2 of 4 · 63` says nothing and `63 events` on a page
 * of applications says something false. The direction labels default to position, and a list ordered
 * by time overrides them: on the audit log, the page before this one is the newer one, and a button
 * reading `Previous` there points backwards in the list and forwards in time.
 */
export function Pager({
  page,
  totalPages,
  totalElements,
  unit,
  previous,
  next,
  busy = false,
  onPage,
}: {
  page: number;
  totalPages: number;
  totalElements: number;
  /** Plural noun for what is being paged: `applications`, `accounts`, `events`. */
  unit: string;
  previous?: string;
  next?: string;
  /** Set while the next page is in flight, which only a list paged by the backend ever is. */
  busy?: boolean;
  onPage: (page: number) => void;
}) {
  const { t, formatNumber } = useI18n();
  if (totalPages <= 1) return null;
  return (
    <nav
      aria-label={t('pager.label')}
      className="mt-4 flex flex-wrap items-center justify-between gap-3 text-xs"
    >
      <p className="text-text-2" aria-live="polite">
        {t('pager.status', {
          page: page + 1,
          total: totalPages,
          count: formatNumber(totalElements),
          unit,
        })}
      </p>
      <div className="flex gap-2">
        <button className="btn btn-sm btn-secondary" disabled={busy || page === 0} onClick={() => onPage(page - 1)}>
          {previous ?? t('pager.previous')}
        </button>
        <button
          className="btn btn-sm btn-secondary"
          disabled={busy || page >= totalPages - 1}
          onClick={() => onPage(page + 1)}
        >
          {next ?? t('pager.next')}
        </button>
      </div>
    </nav>
  );
}
