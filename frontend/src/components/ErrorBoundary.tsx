import { Component, type ErrorInfo, type ReactNode } from 'react';

interface State {
  error: Error | null;
}

/**
 * Catches render errors so an unexpected failure shows a readable message instead of a blank page.
 * Data-fetching errors are handled per screen by TanStack Query; this is the last resort.
 */
export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Unhandled UI error', error, info.componentStack);
  }

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;

    return (
      <div style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', padding: 24, background: '#EEF1F3', fontFamily: 'Inter, system-ui, sans-serif', color: '#1E2A32' }}>
        <div style={{ maxWidth: 420, background: '#FFF', border: '1px solid #E3E9ED', borderRadius: 12, padding: 24, textAlign: 'center', boxShadow: '0 1px 2px rgba(30,42,50,.05)' }}>
          <div style={{ width: 56, height: 56, margin: '0 auto 16px', borderRadius: '50%', background: '#FCEDEC', display: 'grid', placeItems: 'center', fontSize: 22, color: '#B3261E' }}>!</div>
          <div style={{ fontSize: 17, fontWeight: 700, letterSpacing: '-.01em' }}>Something went wrong</div>
          <p style={{ margin: '9px 0 18px', fontSize: 13, lineHeight: 1.55, color: '#4A5A64' }}>
            The page could not be displayed. Reloading usually helps. If it keeps happening, call the clinic on 22 00 00 00.
          </p>
          <button
            onClick={() => window.location.reload()}
            style={{ width: '100%', padding: 14, border: 'none', borderRadius: 12, background: '#0E7C7B', color: '#FFF', fontSize: 14, fontWeight: 600, cursor: 'pointer' }}
          >
            Reload
          </button>
          <p style={{ margin: '14px 0 0', fontSize: 11.5, color: '#8A98A1', wordBreak: 'break-word' }}>{error.message}</p>
        </div>
      </div>
    );
  }
}
