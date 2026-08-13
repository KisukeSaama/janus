import { useEffect, useState } from 'react';

/** Copy, then say so for a moment. The confirmation is spoken, not only coloured. */
export function useCopy(): [boolean, (value: string) => Promise<void>] {
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 2000);
    return () => window.clearTimeout(timer);
  }, [copied]);
  return [
    copied,
    async (value: string) => {
      try {
        await navigator.clipboard.writeText(value);
        setCopied(true);
      } catch {
        // A refused clipboard leaves the value on screen, selectable by hand.
      }
    },
  ];
}
