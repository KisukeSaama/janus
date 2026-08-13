import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@fontsource-variable/instrument-sans/wght.css';
import '@fontsource-variable/spline-sans-mono/wght.css';
import App from './App';
import { I18nProvider } from './i18n';
import { ThemeProvider } from './theme';
import './styles.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ThemeProvider>
      <I18nProvider>
        <App />
      </I18nProvider>
    </ThemeProvider>
  </StrictMode>,
);
