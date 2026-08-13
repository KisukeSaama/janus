import { describe, expect, it } from 'vitest';
import type { Credential } from '../api';
import { daysRemaining, fromDateInput, NOTICE_DAYS, stageOf, toDateInput, upcoming, WARNING_DAYS } from './expiry';

const DAY = 86_400_000;
const NOW = new Date('2026-06-15T12:00:00Z').getTime();
const inDays = (days: number) => new Date(NOW + days * DAY).toISOString();

const credential = (over: Partial<Credential>): Credential =>
  ({ id: 'cred-1', name: 'spotify key', enabled: true, ...over }) as Credential;

describe('stageOf', () => {
  it('says nothing about a deadline further off than the notice window', () => {
    expect(stageOf(inDays(NOTICE_DAYS + 1), NOW)).toBeNull();
  });

  it('raises a notice once the deadline is inside the notice window', () => {
    expect(stageOf(inDays(NOTICE_DAYS - 1), NOW)).toBe('NOTICE');
  });

  it('raises a warning once the deadline is inside the warning window', () => {
    expect(stageOf(inDays(WARNING_DAYS - 1), NOW)).toBe('WARNING');
  });

  it('reports a deadline that has passed as expired', () => {
    expect(stageOf(inDays(-1), NOW)).toBe('EXPIRED');
  });

  // The moment itself belongs to the stage that has just begun, so a key is never briefly in none.
  it('places each boundary in the stricter of the two stages', () => {
    expect(stageOf(new Date(NOW).toISOString(), NOW)).toBe('EXPIRED');
    expect(stageOf(inDays(WARNING_DAYS), NOW)).toBe('WARNING');
    expect(stageOf(inDays(NOTICE_DAYS), NOW)).toBe('NOTICE');
  });

  // A key with no deadline is not a key that never expires being alarmed about.
  it('says nothing about a secret with no recorded deadline', () => {
    expect(stageOf(undefined, NOW)).toBeNull();
    expect(stageOf('', NOW)).toBeNull();
  });

  it('says nothing rather than throwing when the stored date is unreadable', () => {
    expect(stageOf('not-a-date', NOW)).toBeNull();
  });
});

describe('daysRemaining', () => {
  // Rounding down would announce "expires today" on the morning of the day before.
  it('rounds a part-day away from zero rather than down', () => {
    expect(daysRemaining(new Date(NOW + 1.2 * DAY).toISOString(), NOW)).toBe(2);
    expect(daysRemaining(new Date(NOW + 0.1 * DAY).toISOString(), NOW)).toBe(1);
  });

  it('counts a passed deadline as negative', () => {
    expect(daysRemaining(new Date(NOW - 1.2 * DAY).toISOString(), NOW)).toBe(-2);
  });

  it('counts a whole number of days exactly', () => {
    expect(daysRemaining(inDays(30), NOW)).toBe(30);
  });
});

describe('upcoming', () => {
  it('keeps only the deadlines close enough to be work', () => {
    const due = upcoming(
      [
        credential({ id: 'far', expiresAt: inDays(NOTICE_DAYS + 1) }),
        credential({ id: 'near', expiresAt: inDays(3) }),
        credential({ id: 'undated' }),
      ],
      NOW,
    );

    expect(due.map((c) => c.id)).toEqual(['near']);
  });

  /** What has already lapsed is the most urgent thing on the page, so it reads first. */
  it('orders by deadline, the passed ones at the top', () => {
    const due = upcoming(
      [
        credential({ id: 'later', expiresAt: inDays(20) }),
        credential({ id: 'lapsed', expiresAt: inDays(-2) }),
        credential({ id: 'soon', expiresAt: inDays(2) }),
      ],
      NOW,
    );

    expect(due.map((c) => c.id)).toEqual(['lapsed', 'soon', 'later']);
  });

  /** A secret somebody switched off presents nothing, whatever date was recorded for it. */
  it('says nothing about a secret that is already disabled', () => {
    expect(upcoming([credential({ enabled: false, expiresAt: inDays(1) })], NOW)).toEqual([]);
  });
});

describe('date inputs', () => {
  // The only property that matters: what an operator typed is what they read back.
  it('reads back the same day it was given', () => {
    for (const typed of ['2026-06-15', '2026-01-01', '2026-12-31', '2028-02-29']) {
      expect(toDateInput(fromDateInput(typed) ?? undefined)).toBe(typed);
    }
  });

  // "Expires on the 30th" means the key still works during the 30th.
  it('places a deadline at the end of the day it names', () => {
    const stored = new Date(fromDateInput('2026-06-15') as string);
    expect(stored.getHours()).toBe(23);
    expect(stored.getMinutes()).toBe(59);
  });

  it('treats an empty or unusable entry as no deadline at all', () => {
    expect(fromDateInput('')).toBeNull();
    expect(fromDateInput('not-a-date')).toBeNull();
    expect(toDateInput(undefined)).toBe('');
    expect(toDateInput('not-a-date')).toBe('');
  });
});
