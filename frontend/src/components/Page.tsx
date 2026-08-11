import { type ReactNode } from 'react';
import { AlertTriangle, ArrowLeft } from 'lucide-react';

import { useMediaQuery, WIDE } from '../hooks/useMediaQuery';

/**
 * The same three lines at the top of every page: where you are, what this is, what it is for. The
 * fourth slot on the right holds whatever the page offers, which is a button on a collection and a
 * status on a record.
 *
 * Every row here has a floor height, and the section line is printed whether it names a section or
 * offers a way back. That is what keeps a title, a table, and a form landing on the same pixel from
 * one page to the next instead of stepping up and down as the copy changes length.
 *
 * Reading order on a phone is section, title, explanation, action. On a wide screen the action moves
 * up beside the title, where the eye already expects it.
 */
export function PageHead({
  section,
  back,
  title,
  intro,
  action,
}: {
  /** The nav group this page belongs to. Replaced by `back` on a record opened from a list. */
  section?: string;
  back?: { label: string; onClick: () => void };
  title: ReactNode;
  intro: string;
  action?: ReactNode;
}) {
  return (
    <div className="mb-7 border-b border-line pb-5">
      <div className="mb-2 flex h-5 items-center">
        {back ? (
          <button
            onClick={back.onClick}
            className="stamp -ml-1 inline-flex items-center gap-1.5 rounded-[3px] px-1 py-0.5 text-text-2 transition-colors duration-150 hover:text-text"
          >
            <ArrowLeft size={13} strokeWidth={2.5} />
            {back.label}
          </button>
        ) : (
          <span className="stamp text-text-3">{section}</span>
        )}
      </div>

      <div className="flex flex-col gap-x-8 gap-y-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <h1 className="text-xl font-semibold tracking-title">{title}</h1>
          <p className="mt-2 min-h-10 max-w-[68ch] text-sm text-text-2">{intro}</p>
        </div>
        {action && <div className="shrink-0">{action}</div>}
      </div>
    </div>
  );
}

export function SectionHead({ children, aside }: { children: ReactNode; aside?: ReactNode }) {
  return (
    <div className="mb-2.5 flex items-baseline justify-between gap-4">
      <h2 className="stamp text-text-2">{children}</h2>
      {aside}
    </div>
  );
}

/**
 * A titled band inside a page. Panels stack under it, so a long record reads as a sequence of
 * questions rather than one wall.
 */

/**
 * A titled band inside a page. Panels stack under it, so a long record reads as a sequence of
 * questions rather than one wall.
 */
/** A section of a detail page. Children are optional: some blocks are their own statement. */
export function Block({
  title,
  lead,
  aside,
  children,
}: {
  title: string;
  lead?: string;
  aside?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <section className="border-t border-line pt-6">
      <div className="mb-3.5 flex flex-wrap items-start justify-between gap-x-6 gap-y-2">
        <div>
          <h2 className="text-base font-semibold">{title}</h2>
          {lead && <p className="mt-1 max-w-[68ch] text-sm text-text-2">{lead}</p>}
        </div>
        {aside && <div className="shrink-0">{aside}</div>}
      </div>
      {children}
    </section>
  );
}

export function Notice({ children, tone = 'bad' }: { children: ReactNode; tone?: 'bad' | 'warn' }) {
  const skin = tone === 'bad' ? 'border-bad/40 bg-bad-wash text-bad' : 'border-warn/45 bg-warn-wash text-warn';
  return (
    <div role="alert" className={`mb-5 flex items-start gap-2.5 rounded-panel border px-3.5 py-3 text-sm ${skin}`}>
      <AlertTriangle size={16} strokeWidth={2} className="mt-0.5 shrink-0" />
      <span className="text-text">{children}</span>
    </div>
  );
}

export function Empty({ headline, hint, action }: { headline: string; hint: string; action?: ReactNode }) {
  return (
    <div className="panel px-6 py-12 text-center md:py-14">
      <p className="text-lg font-semibold tracking-title">{headline}</p>
      <p className="mx-auto mt-2 max-w-[48ch] text-sm text-text-2">{hint}</p>
      {action && <div className="mt-5 flex justify-center">{action}</div>}
    </div>
  );
}

/**
 * Rows at the height rows actually are: a name over the line that identifies it, then flat values.
 * A placeholder shorter than what replaces it makes the table grow under the reader's eyes, which
 * is the one thing a loading state is there to prevent.
 */
export function SkeletonRows({ rows = 5, cols = 4 }: { rows?: number; cols?: number }) {
  const wide = useMediaQuery(WIDE);
  return (
    <div className="panel divide-y divide-line" aria-hidden="true">
      {Array.from({ length: rows }, (_, r) => (
        <div key={r} className="flex gap-6 px-4 py-3.5">
          {Array.from({ length: wide ? cols : 2 }, (_, c) => (
            <div key={c} style={{ flex: c === 0 ? 3 : 1 }}>
              <div className="skeleton h-4" />
              {c === 0 && <div className="skeleton mt-2 h-3 w-2/3" />}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}
