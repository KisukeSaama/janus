import { useEffect, useRef, type RefObject } from 'react';

/**
 * Closes a panel anchored to the control that opened it.
 *
 * A press anywhere outside dismisses it, and Escape dismisses it and hands focus back, so the
 * keyboard never ends up somewhere the reader cannot see. The two menus in the top bar each carried
 * their own copy of this, which is how one of them came to listen for `mousedown` and the row menus
 * for `pointerdown`: on a phone the first fires an event later than the tap that caused it.
 *
 * The callback is held in a ref, because every caller passes an inline closure and rebinding two
 * document listeners on every render is not what an open menu should cost.
 */
export function useDismiss(
  open: boolean,
  region: RefObject<HTMLElement | null>,
  trigger: RefObject<HTMLElement | null>,
  onClose: () => void,
) {
  const close = useRef(onClose);
  close.current = onClose;

  useEffect(() => {
    if (!open) return;

    const outside = (event: PointerEvent) => {
      const target = event.target as Node;
      // The trigger is exempt in its own right, not only by being inside the panel: a portalled
      // panel is not a descendant of the control that opened it, and closing on the press that the
      // trigger is about to read as a toggle would shut the menu and open it again.
      if (!region.current?.contains(target) && !trigger.current?.contains(target)) close.current();
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      close.current();
      trigger.current?.focus();
    };

    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [open, region, trigger]);
}
