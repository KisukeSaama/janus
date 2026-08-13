import { useMemo, useState } from 'react';
import { Search } from 'lucide-react';

import { useI18n } from '../../i18n';
import { API_PRESETS, searchPresets, type ApiPreset } from './presets';

/**
 * The first thing somebody sees when they add an API, and usually the last decision they have to
 * make about its contract.
 *
 * The alternative — an empty form with a destination field and a list of authentication strategies —
 * asks a reader to already know two things: what Spotify's token endpoint is, and which of eight
 * strategies it corresponds to. Both are written down in somebody's documentation, and going to find
 * them is how a reader leaves and comes back with a wrong answer pasted from a blog post.
 *
 * So the question here is the one they can answer without leaving: which API is it. Everything a
 * preset fills in stays editable on the next step, and anything not in the list is one link away.
 */
export function PresetPicker({
  onChoose,
  onSkip,
}: {
  onChoose: (preset: ApiPreset) => void;
  onSkip: () => void;
}) {
  const { t } = useI18n();
  const [query, setQuery] = useState('');
  const results = useMemo(() => searchPresets(query), [query]);

  return (
    <div className="space-y-4">
      <div className="relative">
        <Search
          size={15}
          strokeWidth={2.25}
          aria-hidden="true"
          className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-3"
        />
        <input
          type="search"
          autoFocus
          autoComplete="off"
          className="input w-full pl-9"
          placeholder={t('connect.presetSearch')}
          aria-label={t('connect.presetSearch')}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>

      {results.length === 0 ? (
        // Not an error state: most APIs in the world are not in this list, and the way forward is
        // the same one that was always available.
        <p className="rounded-panel border border-line bg-sunk px-3.5 py-3 text-sm text-text-2">
          {t('connect.presetNoMatch', { query })}
        </p>
      ) : (
        <ul className="grid gap-2 sm:grid-cols-2">
          {results.map((preset) => (
            <li key={preset.id}>
              <button
                type="button"
                onClick={() => onChoose(preset)}
                className="group h-full w-full rounded-panel border border-line bg-sunk px-3.5 py-3 text-left transition-colors hover:border-accent focus-visible:border-accent"
              >
                <span className="flex items-baseline gap-2">
                  <span className="font-medium">{preset.name}</span>
                  {preset.variant && <span className="stamp text-text-3">{preset.variant}</span>}
                </span>
                <span className="mt-1 block text-xs leading-5 text-text-2">{preset.summary}</span>
              </button>
            </li>
          ))}
        </ul>
      )}

      <button type="button" className="text-sm text-accent-text underline underline-offset-2" onClick={onSkip}>
        {t('connect.presetSkip')}
      </button>
      <p className="text-xs text-text-3">
        {t('connect.presetCount', { count: API_PRESETS.length })}
      </p>
    </div>
  );
}
