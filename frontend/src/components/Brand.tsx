/** The mark and the wordmark, the only two places Janus draws itself. */

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

/**
 * Set tight rather than spaced out. Wide letterspacing on a name is a lobby sign; this is a tool,
 * and it says its name once at the top of the window.
 */
export function Wordmark({ subtitle, accent = true }: { subtitle?: string; accent?: boolean }) {
  return (
    <span className="flex items-center gap-2.5">
      <Mark accent={accent} />
      <span className="text-[0.9375rem] font-bold uppercase tracking-[0.055em]">Janus</span>
      {subtitle && (
        <span className="stamp hidden border-l border-current pl-2.5 text-text-3 sm:inline">{subtitle}</span>
      )}
    </span>
  );
}

/* ── Marks and status ──────────────────────────────────────────────────── */

/**
 * PROD is a filled orange stamp, DEV an outlined neutral one. Fill and weight carry the difference
 * as well as hue, and the word is always spelled out.
 */
