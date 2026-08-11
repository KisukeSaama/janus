import type { ReactNode } from 'react';

import { useI18n } from '../i18n';
import { useMediaQuery, WIDE } from '../hooks/useMediaQuery';

export type Column<T> = {
  key: string;
  label: string;
  cell: (row: T) => ReactNode;
  /** Row heading on narrow screens. Exactly one column should set this. */
  primary?: boolean;
  /** Sits beside the heading on narrow screens instead of getting its own line. */
  badge?: boolean;
  align?: 'left' | 'right';
  /** Column that absorbs slack on wide screens. */
  grow?: boolean;
  nowrap?: boolean;
};

/**
 * One declaration, two structures. Wide screens get a real table; narrow screens get stacked
 * records, because a six-column table behind a horizontal scrollbar is not a mobile experience.
 *
 * The table owns the typography of its own cells. A column declares what it shows, never how loud
 * it is: the identity column is the one thing in the row set at full intensity, everything else is
 * secondary text. Left to the pages, that rule drifted — the same name-over-detail pair was painted
 * four times with four slightly different sets of classes, and a table where three columns shout at
 * the same volume has no row to read first.
 */
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  actions,
}: {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  actions?: (row: T) => ReactNode;
}) {
  const { t } = useI18n();
  const wide = useMediaQuery(WIDE);

  if (wide) {
    return (
      <div className="panel scroll-x">
        {/*
         * The table body is set one step down, in the secondary tone: hierarchy inside a row is
         * carried by weight and colour, not by size, so `RecordCell` raises the identity column
         * back up and nothing else has to be dimmed by hand.
         */}
        <table className="w-full border-collapse text-xs">
          <thead>
            <tr className="border-b border-line bg-sunk text-left">
              {columns.map((c) => (
                <th
                  key={c.key}
                  scope="col"
                  className={`stamp whitespace-nowrap px-4 py-3 text-text-2 ${c.grow ? 'w-full' : ''} ${
                    c.align === 'right' ? 'text-right' : ''
                  }`}
                >
                  {c.label}
                </th>
              ))}
              {actions && (
                /*
                 * Zero-width and nowrap: the column takes exactly what its controls measure and no
                 * more. Left to shrink against a `grow` column it collapsed to one button wide and
                 * stacked the rest of the row's actions on top of each other, four buttons tall.
                 */
                <th scope="col" className="w-0 whitespace-nowrap px-4 py-3">
                  <span className="sr-only">{t('common.actions')}</span>
                </th>
              )}
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {rows.map((row) => (
              <tr key={rowKey(row)} className="transition-colors hover:bg-sunk/50">
                {columns.map((c) => (
                  <td
                    key={c.key}
                    /*
                     * Top, not middle. A record whose name carries a second line under it used to
                     * float its state and its dates halfway down that pair, level with nothing;
                     * every value in the row now starts on the line the name starts on.
                     */
                    className={`px-4 py-3 align-top ${c.primary ? 'text-text' : 'text-text-2'} ${
                      c.align === 'right' ? 'text-right' : ''
                    } ${c.nowrap ? 'whitespace-nowrap' : ''}`}
                  >
                    {c.cell(row)}
                  </td>
                ))}
                {actions && (
                  <td className="w-0 whitespace-nowrap px-4 py-3 align-top">
                    {/* The row owns the arrangement, so no page can invent its own and wrap. */}
                    <div className="flex items-center justify-end gap-1">{actions(row)}</div>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }

  const primary = columns.find((c) => c.primary) ?? columns[0];
  const badges = columns.filter((c) => c.badge);
  const details = columns.filter((c) => c !== primary && !c.badge);

  return (
    <ul className="panel divide-y divide-line">
      {rows.map((row) => (
        <li key={rowKey(row)} className="px-4 py-4">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0 flex-1">{primary.cell(row)}</div>
            <div className="flex shrink-0 items-center gap-2">{badges.map((c) => c.cell(row))}</div>
          </div>

          {details.length > 0 && (
            <dl className="mt-3 grid grid-cols-[minmax(6rem,auto)_1fr] gap-x-4 gap-y-1.5 border-t border-line pt-3 text-xs">
              {details.map((c) => (
                <div key={c.key} className="contents">
                  <dt className="stamp self-center text-text-3">{c.label}</dt>
                  <dd className="min-w-0 break-words text-text-2">{c.cell(row)}</dd>
                </div>
              ))}
            </dl>
          )}

          {/* Stacked records have the width of the screen for their controls, so here wrapping is fine. */}
          {actions && <div className="mt-3 flex flex-wrap items-center justify-end gap-1">{actions(row)}</div>}
        </li>
      ))}
    </ul>
  );
}

/**
 * What the row is, and the one line that tells it apart from its neighbour.
 *
 * The pair is a component rather than a convention because it is the only full-intensity text in
 * the row, and because the second line is held open whether or not the record has one: a table
 * whose rows are 46px or 64px depending on whether somebody typed a description reads as ragged,
 * and the eye loses the column on the way across. Reserved on the table, collapsed on the stacked
 * layout, where a blank line is only a gap.
 */
export function RecordCell({ name, note, mono }: { name: ReactNode; note?: ReactNode; mono?: boolean }) {
  return (
    <>
      <p className="text-sm font-medium text-text">{name}</p>
      <p
        className={`mt-0.5 min-w-0 text-xs text-text-2 lg:min-h-[1.125rem] ${note ? '' : 'hidden lg:block'} ${
          mono ? 'data break-all' : 'break-words'
        }`}
      >
        {note}
      </p>
    </>
  );
}
