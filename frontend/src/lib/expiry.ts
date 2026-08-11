/**
 * How close a stored secret is to the date recorded for it, decided on the client.
 *
 * The backend raises an announcement once per stage and never again; this is the other half of the
 * same question, the one a table has to answer on every render: what state is this key in right now.
 * The thresholds mirror `janus.notifications.expiry` — a deployment that widens them changes when
 * the mail goes out, not what the console calls a key it is looking at.
 */

import type { Credential, ExpiryStage } from '../api';

export const NOTICE_DAYS = 30;
export const WARNING_DAYS = 7;

const DAY = 86_400_000;

/** The stage a deadline has reached, or null while it is further off than the notice window. */
export function stageOf(expiresAt: string | undefined, now = Date.now()): ExpiryStage | null {
  if (!expiresAt) return null;
  const due = new Date(expiresAt).getTime();
  if (Number.isNaN(due)) return null;
  if (due <= now) return 'EXPIRED';
  const remaining = due - now;
  if (remaining <= WARNING_DAYS * DAY) return 'WARNING';
  if (remaining <= NOTICE_DAYS * DAY) return 'NOTICE';
  return null;
}

/**
 * Whole days left, rounded away from zero, negative once the date has passed. Rounding down would
 * tell an operator a key expires today on the morning of the day before.
 */
export function daysRemaining(expiresAt: string, now = Date.now()): number {
  const remaining = new Date(expiresAt).getTime() - now;
  return Math.sign(remaining) * Math.ceil(Math.abs(remaining) / DAY);
}

/**
 * The deadlines close enough to be work, soonest first, with the ones already passed at the top.
 *
 * A secret somebody switched off is not an announcement: whatever date was recorded for it, nothing
 * presents it any more. Everything further off than the notice window is left out entirely — the
 * dashboard states what is coming, and a key due in nine months is not.
 */
export function upcoming(credentials: Credential[], now = Date.now()): Credential[] {
  return credentials
    .filter((credential) => credential.enabled && stageOf(credential.expiresAt, now))
    .sort((a, b) => new Date(a.expiresAt as string).getTime() - new Date(b.expiresAt as string).getTime());
}

/** The tone a stage is drawn in. Severity is never hue alone; the callers pair it with a word. */
export const STAGE_TONE: Record<ExpiryStage, 'bad' | 'warn' | 'info'> = {
  EXPIRED: 'bad',
  WARNING: 'warn',
  NOTICE: 'info',
};

/**
 * The instant a calendar day ends where the operator is standing.
 *
 * "Expires on the 30th" means the key still works on the 30th, so the deadline is the end of that
 * day rather than its start. Read back through {@link toDateInput} it yields the same day it was
 * typed, which is the only property that matters here.
 */
export function fromDateInput(value: string): string | null {
  if (!value) return null;
  const [year, month, day] = value.split('-').map(Number);
  if (!year || !month || !day) return null;
  return new Date(year, month - 1, day, 23, 59, 59, 999).toISOString();
}

/** The `yyyy-mm-dd` a date input needs, in the reader's own zone. */
export function toDateInput(iso: string | undefined): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}
