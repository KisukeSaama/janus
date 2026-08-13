import { useI18n } from '../i18n';

export function Pager({
  page,
  totalPages,
  totalElements,
  busy,
  onPage,
}: {
  page: number;
  totalPages: number;
  totalElements: number;
  busy: boolean;
  onPage: (page: number) => void;
}) {
  const { t, formatNumber } = useI18n();
  if (totalPages <= 1) return null;
  return (
    <nav
      aria-label={t('audit.pagination')}
      className="mt-4 flex flex-wrap items-center justify-between gap-3 text-xs"
    >
      <p className="text-text-2" aria-live="polite">
        {t('audit.pageStatus', {
          page: page + 1,
          total: totalPages,
          count: formatNumber(totalElements),
        })}
      </p>
      <div className="flex gap-2">
        <button className="btn btn-sm btn-secondary" disabled={busy || page === 0} onClick={() => onPage(page - 1)}>
          {t('audit.newer')}
        </button>
        <button
          className="btn btn-sm btn-secondary"
          disabled={busy || page >= totalPages - 1}
          onClick={() => onPage(page + 1)}
        >
          {t('audit.older')}
        </button>
      </div>
    </nav>
  );
}
