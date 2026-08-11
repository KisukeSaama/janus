import { useEffect, useRef, useState } from 'react';
import { Check, Settings2 } from 'lucide-react';

import { LOCALE_NAMES, LOCALES, useI18n, type Locale } from '../i18n';
import { useTheme, type Theme } from '../theme';

/** Appearance and language, the two things about the console that belong to the reader. */
export function SettingsMenu() {
  const { t, locale, setLocale } = useI18n();
  const { theme, setTheme } = useTheme();
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const themes: [Theme, string][] = [
    ['dark', t('common.themeDark')],
    ['light', t('common.themeLight')],
  ];

  return (
    <div className="relative" ref={wrapRef}>
      <button
        ref={triggerRef}
        className="btn btn-secondary aspect-square px-0"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={t('common.settings')}
        onClick={() => setOpen((o) => !o)}
      >
        <Settings2 size={16} strokeWidth={2} />
      </button>

      {open && (
        <div
          role="dialog"
          aria-label={t('common.settings')}
          className="absolute right-0 top-[calc(100%+0.5rem)] z-40 w-[15rem] rounded-panel border border-line bg-surface p-3 shadow-overlay [animation:fade-in_140ms_var(--ease-out-quint)]"
        >
          <p className="stamp mb-2 text-text-3">{t('common.appearance')}</p>
          <div className="grid grid-cols-2 gap-1">
            {themes.map(([id, label]) => (
              <button
                key={id}
                onClick={() => setTheme(id)}
                aria-pressed={theme === id}
                className={`btn btn-sm ${theme === id ? 'btn-primary' : 'btn-secondary'}`}
              >
                {label}
              </button>
            ))}
          </div>

          <p className="stamp mb-2 mt-4 text-text-3">{t('common.language')}</p>
          <ul>
            {LOCALES.map((id: Locale) => (
              <li key={id}>
                <button
                  onClick={() => setLocale(id)}
                  aria-pressed={locale === id}
                  className={`flex w-full items-center justify-between rounded-control px-2.5 py-2 text-sm transition-colors duration-150 ${
                    locale === id ? 'bg-sunk text-text' : 'text-text-2 hover:bg-sunk hover:text-text'
                  }`}
                >
                  {LOCALE_NAMES[id]}
                  {locale === id && <Check size={15} className="text-accent-text" />}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
