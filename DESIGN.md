# Design System

## Theme

A bright operations room in daytime: a pure white working canvas, cool neutral structure, and a measured violet-blue control color used only where action or selection requires it. Restrained product color strategy.

## Color

All authored colors use OKLCH. Primary is `oklch(0.50 0.14 294)`; accent is a deep cyan-blue `oklch(0.45 0.12 235)`. Canvas is pure white, surfaces use a low-chroma violet neutral, and body ink is near-black with a subtle violet cast. Success, warning, and danger always pair color with text or an icon.

## Typography

Inter Variable with system-ui fallback. Compact fixed sizing: 14px body, 12px metadata, 20–28px page titles. Use tabular numerals for counts and timestamps.

## Components

Controls have 8px radii; panels and tables use 12px. Primary actions are solid violet with white text. Secondary actions are neutral outlined. Tables are the default for operational collections. Status pills remain compact and always include a textual label. Loading uses skeleton rows; empty states explain the next action.

## Layout

A fixed desktop sidebar collapses into a top bar on narrow screens. Content width is generous for tables, with a compact header and consistent 24px desktop / 16px mobile gutters. Forms use a single readable column, with related short fields paired only when space permits.

## Motion

150–200ms state transitions with an exponential ease-out. Motion communicates navigation, disclosure, or completion only. Reduced-motion disables transforms and shortens transitions.
