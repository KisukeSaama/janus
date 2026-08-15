// What Vite adds to the module graph beyond JavaScript: stylesheets, fonts, assets imported for
// their side effect alone. TypeScript 6 refuses a side-effect import it has no declaration for, so
// without this the three `import './styles.css'` lines in main.tsx fail to compile.
/// <reference types="vite/client" />
