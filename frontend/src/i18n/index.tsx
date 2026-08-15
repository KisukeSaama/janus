import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { en, type Dictionary, type Messages } from './en';
import { fr } from './fr';

export const LOCALES = ['en', 'fr'] as const;
export type Locale = (typeof LOCALES)[number];

export const LOCALE_NAMES: Record<Locale, string> = { en: 'English', fr: 'Français' };

// No assertion here, deliberately. `fr as Dictionary` claimed a translation was made of the very
// string literals `en` is made of, which is untrue and is exactly the kind of statement that stops
// the compiler from reporting the next thing that is. `Messages` is what both tables actually are.
const DICTIONARIES: Record<Locale, Messages> = { en, fr };
const STORAGE_KEY = 'janus.locale';

type Paths<T> = {
  [K in keyof T & string]: T[K] extends string ? K : `${K}.${Paths<T[K]>}`;
}[keyof T & string];

export type MessageKey = Paths<Dictionary>;
type Params = Record<string, string | number>;

function read(dictionary: Messages, key: string): string | undefined {
  const value = key.split('.').reduce<unknown>((node, part) => {
    if (node && typeof node === 'object' && part in node) return (node as Record<string, unknown>)[part];
    return undefined;
  }, dictionary);
  return typeof value === 'string' ? value : undefined;
}

function fill(template: string, params?: Params): string {
  if (!params) return template;
  return template.replace(/\{(\w+)\}/g, (match, name: string) =>
    name in params ? String(params[name]) : match,
  );
}

function detect(): Locale {
  const stored = localStorage.getItem(STORAGE_KEY);
  if (stored && (LOCALES as readonly string[]).includes(stored)) return stored as Locale;
  const preferred = navigator.languages ?? [navigator.language];
  for (const tag of preferred) {
    const base = tag.slice(0, 2).toLowerCase();
    if ((LOCALES as readonly string[]).includes(base)) return base as Locale;
  }
  return 'en';
}

type I18n = {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  /** Look up a message, interpolating `{name}` placeholders. */
  t: (key: MessageKey, params?: Params) => string;
  /** Plural variant lookup: `key#one` / `key#other` chosen by the locale's rules. */
  tc: (key: string, count: number, params?: Params) => string;
  /** Backend enum label with a readable fallback for values this console does not know yet. */
  tEnum: (group: 'authType' | 'actor' | 'outcome' | 'action', value: string) => string;
  formatDate: (iso: string) => string;
  formatTimestamp: (iso: string) => string;
  formatTime: (iso: string) => string;
  formatNumber: (value: number) => string;
  /** "3 months ago" / "il y a 3 mois", picking the largest unit that still reads naturally. */
  formatAge: (iso: string) => string;
};

const Context = createContext<I18n | null>(null);

export function I18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocaleState] = useState<Locale>(() => detect());

  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);

  const setLocale = useCallback((next: Locale) => {
    localStorage.setItem(STORAGE_KEY, next);
    setLocaleState(next);
  }, []);

  const value = useMemo<I18n>(() => {
    const dictionary = DICTIONARIES[locale];
    const t = (key: MessageKey, params?: Params) =>
      fill(read(dictionary, key) ?? read(en, key) ?? key, params);

    const dateFormat = new Intl.DateTimeFormat(locale, { year: 'numeric', month: 'short', day: '2-digit' });
    const timestampFormat = new Intl.DateTimeFormat(locale, {
      month: 'short',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    });
    const timeFormat = new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit', hour12: false });
    const numberFormat = new Intl.NumberFormat(locale);
    const plural = new Intl.PluralRules(locale);
    const relative = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
    const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
      ['year', 365 * 86400],
      ['month', 30 * 86400],
      ['day', 86400],
      ['hour', 3600],
      ['minute', 60],
    ];

    return {
      locale,
      setLocale,
      t,
      tc: (key, count, params) => {
        const rule = plural.select(count);
        const message =
          read(dictionary, `${key}#${rule}`) ??
          read(dictionary, `${key}#other`) ??
          read(en, `${key}#other`) ??
          key;
        return fill(message, { count, ...params });
      },
      tEnum: (group, value) =>
        read(dictionary, `${group}.${value}`) ??
        read(en, `${group}.${value}`) ??
        value.replaceAll('_', ' ').toLowerCase(),
      formatDate: (iso) => dateFormat.format(new Date(iso)),
      formatTimestamp: (iso) => timestampFormat.format(new Date(iso)),
      formatTime: (iso) => timeFormat.format(new Date(iso)),
      formatNumber: (n) => numberFormat.format(n),
      formatAge: (iso) => {
        const seconds = (Date.now() - new Date(iso).getTime()) / 1000;
        for (const [unit, size] of UNITS) {
          if (seconds >= size) return relative.format(-Math.floor(seconds / size), unit);
        }
        return relative.format(0, 'minute');
      },
    };
  }, [locale, setLocale]);

  return <Context.Provider value={value}>{children}</Context.Provider>;
}

export function useI18n(): I18n {
  const value = useContext(Context);
  if (!value) throw new Error('useI18n must be used inside I18nProvider');
  return value;
}
