import { Fragment, type ReactNode } from 'react';

/**
 * The pieces the two reference pages are both built from: a numbered sequence, a bullet, a remark,
 * and the rule that sets machine data apart from the sentence around it.
 *
 * Prose and machine data are two textures in this console, and the reference pages are where the two
 * are most often in the same sentence. Keeping the rule in one place is what stops a path on one page
 * from looking like ordinary words on the other.
 */

/**
 * Sets the parts of a sentence the gateway will match literally — a path, a header, a status — in the
 * monospace, marked in the dictionary with backticks.
 */
export function prose(text: string): ReactNode {
  const parts = text.split(/`([^`]+)`/g);
  if (parts.length === 1) return text;
  return parts.map((part, index) =>
    index % 2 === 1 ? (
      <code key={index} className="data rounded-[3px] border border-line bg-sunk px-1 py-px text-[0.9375em]">
        {part}
      </code>
    ) : (
      <Fragment key={index}>{part}</Fragment>
    ),
  );
}

export function Steps({ items }: { items: string[] }) {
  return (
    <ol className="panel divide-y divide-line">
      {items.map((item, index) => (
        <li key={item} className="flex items-baseline gap-3 px-4 py-3">
          <span className="data stamp shrink-0 text-accent-text">{index + 1}</span>
          <span className="text-sm">{prose(item)}</span>
        </li>
      ))}
    </ol>
  );
}

export function Bullet({ children }: { children: string }) {
  return (
    <li className="flex gap-2.5 text-sm text-text-2">
      <span aria-hidden="true" className="mt-[0.4375rem] h-1.5 w-1.5 shrink-0 bg-line-strong" />
      <span className="max-w-[72ch]">{prose(children)}</span>
    </li>
  );
}

export function Note({ children }: { children: string }) {
  return <p className="mt-3.5 max-w-[72ch] text-sm text-text-2">{prose(children)}</p>;
}
