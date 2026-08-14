import { useState } from 'react';

/** One page of rows, matching the size the catalogue asks the backend for. */
export const PAGE_SIZE = 20;

/**
 * Search and paging over a list already in hand.
 *
 * <p>The catalogue asks the backend to narrow and to page, because nobody holds a catalogue of a
 * thousand APIs in memory. Applications and accounts arrive whole and stay whole — a deployment has
 * tens of each — so the same two controls are answered here, where the answer costs one pass over an
 * array and cannot go stale between the typing and the rows.
 *
 * <p>Matching is deliberately not memoised: over a list this size the filter is cheaper than the
 * bookkeeping, and a caller may close over anything it likes without stabilising a callback.
 */
export function useListView<T>(
  rows: T[],
  matches: (row: T, needle: string) => boolean,
  size = PAGE_SIZE,
) {
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);

  const needle = query.trim().toLowerCase();
  const matched = needle ? rows.filter((row) => matches(row, needle)) : rows;
  const totalPages = Math.max(1, Math.ceil(matched.length / size));
  // The list shrinks under the reader — a row deleted, a search narrowed — and a page past the end
  // shows nothing at all. Clamped rather than reset: the Pager only ever moves from what it was
  // handed, so the stored page follows on the next click.
  const current = Math.min(page, totalPages - 1);

  return {
    query,
    /** Typing narrows the list, so the reader is taken back to its first page. */
    search: (next: string) => {
      setQuery(next);
      setPage(0);
    },
    page: current,
    setPage,
    rows: matched.slice(current * size, current * size + size),
    totalPages,
    totalElements: matched.length,
    /** Whether rows are missing because of the search rather than because there are none. */
    searching: needle.length > 0,
  };
}
