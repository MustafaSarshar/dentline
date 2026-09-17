import { useEffect, useRef } from 'react';

const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

/**
 * Only the topmost dialog reacts to keys, so a confirm dialog opened from the drawer does not
 * close both when Escape is pressed.
 */
const openDialogs: symbol[] = [];

/**
 * Makes an element behave like a real dialog: focus moves inside on open, Tab cycles within it,
 * Escape closes it when the dialog has a way out, and focus returns where it came from.
 * The prototypes did not implement this; the design hand-off asked for it in the real build.
 *
 * Pass no [onClose] for a dialog that requires a choice (the waitlist offer), which should keep
 * the focus trap but not be dismissible.
 */
export function useDialog<T extends HTMLElement>(onClose?: () => void) {
  const ref = useRef<T>(null);
  // Kept in a ref so a new inline callback each render does not re-run the effect and steal focus.
  const closeRef = useRef(onClose);
  closeRef.current = onClose;

  useEffect(() => {
    const container = ref.current;
    if (!container) return;

    const id = Symbol('dialog');
    openDialogs.push(id);
    const previouslyFocused = document.activeElement as HTMLElement | null;

    // Disabled controls are already excluded by the selector, and these dialogs never render
    // focusable elements they mean to hide, so what the selector finds is what can be reached.
    const focusable = () => Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE));

    focusable()[0]?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (openDialogs[openDialogs.length - 1] !== id) return;

      if (event.key === 'Escape' && closeRef.current) {
        event.preventDefault();
        event.stopPropagation();
        closeRef.current();
        return;
      }
      if (event.key !== 'Tab') return;

      const items = focusable();
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement;

      if (event.shiftKey && (active === first || !container.contains(active))) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener('keydown', onKeyDown, true);
    return () => {
      document.removeEventListener('keydown', onKeyDown, true);
      openDialogs.splice(openDialogs.indexOf(id), 1);
      previouslyFocused?.focus?.();
    };
  }, []);

  return ref;
}
