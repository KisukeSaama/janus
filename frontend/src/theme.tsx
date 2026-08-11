import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';

export type Theme = 'dark' | 'light';

const STORAGE_KEY = 'janus.theme';

/**
 * Dark is the product default rather than a system echo: this console sits beside a terminal and an
 * editor, and the orange accent was drawn against a charcoal ground. Light stays a first-class
 * setting for anyone reading in a bright room.
 */
export function readStoredTheme(): Theme {
  return localStorage.getItem(STORAGE_KEY) === 'light' ? 'light' : 'dark';
}

/**
 * The browser's own bar, painted the colour of the page under it.
 *
 * These are `--c-canvas` in each theme, written as sRGB because a `<meta>` tag cannot read a custom
 * property. They were a warm grey pair belonging to no palette in this console: `theme.js` set the
 * right colour before first paint and this replaced it with a brown one the moment React mounted,
 * so a phone drew its status bar in a colour that appears nowhere on the screen below it.
 */
const CHROME: Record<Theme, string> = { dark: '#0b0d10', light: '#f4f5f8' };

function apply(theme: Theme) {
  document.documentElement.dataset.theme = theme;
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', CHROME[theme]);
}

const Context = createContext<{ theme: Theme; setTheme: (theme: Theme) => void } | null>(null);

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<Theme>(() => readStoredTheme());

  useEffect(() => {
    apply(theme);
  }, [theme]);

  const setTheme = useCallback((next: Theme) => {
    localStorage.setItem(STORAGE_KEY, next);
    setThemeState(next);
  }, []);

  return <Context.Provider value={{ theme, setTheme }}>{children}</Context.Provider>;
}

export function useTheme() {
  const value = useContext(Context);
  if (!value) throw new Error('useTheme must be used inside ThemeProvider');
  return value;
}
