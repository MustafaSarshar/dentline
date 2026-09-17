import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useDialog } from './useDialog';

function Dialog({ onClose }: { onClose?: () => void }) {
  const ref = useDialog<HTMLDivElement>(onClose);
  return (
    <div ref={ref} role="dialog">
      <button>first</button>
      <button>middle</button>
      <button>last</button>
    </div>
  );
}

describe('useDialog', () => {
  it('moves focus into the dialog when it opens', () => {
    render(<Dialog />);

    expect(document.activeElement).toBe(screen.getByText('first'));
  });

  it('closes on Escape when the dialog has a way out', () => {
    const onClose = vi.fn();
    render(<Dialog onClose={onClose} />);

    fireEvent.keyDown(screen.getByText('first'), { key: 'Escape' });

    expect(onClose).toHaveBeenCalledOnce();
  });

  it('ignores Escape for a dialog that requires a choice, like the waitlist offer', () => {
    render(<Dialog />);

    // Nothing to assert but the absence of a crash: with no onClose there is no way out.
    expect(() => fireEvent.keyDown(screen.getByText('first'), { key: 'Escape' })).not.toThrow();
  });

  it('keeps Tab inside the dialog', () => {
    render(<Dialog />);
    const first = screen.getByText('first');
    const last = screen.getByText('last');

    last.focus();
    fireEvent.keyDown(last, { key: 'Tab' });
    expect(document.activeElement).toBe(first);

    fireEvent.keyDown(first, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(last);
  });

  it('leaves Tab alone in the middle of the dialog, so the browser handles it', () => {
    render(<Dialog />);
    const middle = screen.getByText('middle');

    middle.focus();
    fireEvent.keyDown(middle, { key: 'Tab' });

    expect(document.activeElement).toBe(middle);
  });

  it('only the topmost dialog answers Escape', () => {
    const closeOuter = vi.fn();
    const closeInner = vi.fn();
    const { rerender } = render(<Dialog onClose={closeOuter} />);
    rerender(
      <>
        <Dialog onClose={closeOuter} />
        <Dialog onClose={closeInner} />
      </>,
    );

    fireEvent.keyDown(document.body, { key: 'Escape' });

    expect(closeInner).toHaveBeenCalledOnce();
    expect(closeOuter).not.toHaveBeenCalled();
  });

  it('gives focus back to whatever opened it', () => {
    render(<button>opener</button>);
    const opener = screen.getByText('opener');
    opener.focus();

    const dialog = render(<Dialog />);
    expect(document.activeElement).toBe(screen.getByText('first'));

    dialog.unmount();
    expect(document.activeElement).toBe(opener);
  });
});
