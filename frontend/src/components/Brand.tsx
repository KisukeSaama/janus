/** The mark and the wordmark, the only two places Janus draws itself. */

import { useI18n } from '../i18n';

/**
 * Two uprights of unequal weight: a threshold, half open.
 *
 * The second post starts lower than the first, so the pair reads as a step rather than a pause
 * button. The asymmetry is what survives at 16px, where an even gap would not.
 */
export function Mark({ size = 16, accent = false }: { size?: number; accent?: boolean }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" aria-hidden="true" fill="currentColor">
      <rect x="2" y="1" width="4" height="14" className={accent ? 'fill-accent' : undefined} />
      <rect x="9" y="4" width="4" height="11" opacity=".45" />
    </svg>
  );
}

/**
 * Set tight rather than spaced out. Wide letterspacing on a name is a lobby sign; this is a tool,
 * and it says its name once at the top of the window.
 */
export function Wordmark({
  subtitle,
  accent = true,
  compactOnSmall = false,
}: {
  subtitle?: string;
  accent?: boolean;
  compactOnSmall?: boolean;
}) {
  return (
    <span className="flex items-center gap-2.5">
      <Mark accent={accent} />
      <span
        className={`text-[0.9375rem] font-bold uppercase tracking-[0.055em] ${
          compactOnSmall ? 'hidden min-[24rem]:inline' : ''
        }`}
      >
        Janus
      </span>
      {subtitle && (
        <span className="stamp hidden border-l border-current pl-2.5 text-text-3 sm:inline">{subtitle}</span>
      )}
    </span>
  );
}

/**
 * The wordmark doing what a wordmark in the top left corner has meant since there were windows: the
 * way back to the start.
 *
 * A button rather than a link, because the console's home is reached through the same navigator as
 * every other destination, and because this one is sometimes intercepted: a screen holding an
 * unsaved entry answers the click with a question first. It carries a label of its own, since
 * "Janus" names the product and not the place the click leads.
 */
export function HomeLink({
  subtitle,
  onNavigate,
  compactOnSmall = false,
}: {
  subtitle?: string;
  onNavigate: () => void;
  compactOnSmall?: boolean;
}) {
  const { t } = useI18n();
  return (
    <button
      type="button"
      className="rounded-[3px] transition-opacity hover:opacity-70"
      aria-label={t('nav.home')}
      onClick={onNavigate}
    >
      <Wordmark subtitle={subtitle} compactOnSmall={compactOnSmall} />
    </button>
  );
}
