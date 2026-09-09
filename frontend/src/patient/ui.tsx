import type { CSSProperties, ReactNode } from 'react';

// Small building blocks shared by the patient screens. Styles are the design's, verbatim.

export const label = (extra?: CSSProperties): CSSProperties => ({
  fontSize: 11,
  fontWeight: 600,
  letterSpacing: '.08em',
  textTransform: 'uppercase',
  color: '#65757F',
  ...extra,
});

export const card: CSSProperties = { background: '#FFF', border: '1px solid #E3E9ED', borderRadius: 12, padding: 16 };

export function PrimaryButton({ children, onClick, disabled, style }: { children: ReactNode; onClick?: () => void; disabled?: boolean; style?: CSSProperties }) {
  return (
    <button className={disabled ? undefined : 'p-primary'} onClick={onClick} disabled={disabled} style={{ width: '100%', padding: 15, border: 'none', borderRadius: 12, background: disabled ? '#B7C3CA' : '#0E7C7B', color: '#FFF', fontSize: 15, fontWeight: 600, cursor: disabled ? 'not-allowed' : 'pointer', ...style }}>
      {children}
    </button>
  );
}

export function SecondaryButton({ children, onClick, style }: { children: ReactNode; onClick?: () => void; style?: CSSProperties }) {
  return (
    <button className="p-secondary" onClick={onClick} style={{ width: '100%', padding: 14, border: '1px solid #D3DBE0', borderRadius: 12, background: '#FFF', color: '#1E2A32', fontSize: 14, fontWeight: 600, cursor: 'pointer', ...style }}>
      {children}
    </button>
  );
}

export function OutlineTealButton({ children, onClick, style }: { children: ReactNode; onClick?: () => void; style?: CSSProperties }) {
  return (
    <button className="p-outline-teal" onClick={onClick} style={{ width: '100%', padding: 14, border: '1px solid #0E7C7B', borderRadius: 12, background: '#FFF', color: '#0E7C7B', fontSize: 14, fontWeight: 600, cursor: 'pointer', ...style }}>
      {children}
    </button>
  );
}

export function SummaryRows({ rows }: { rows: Array<[string, string]> }) {
  return (
    <>
      {rows.map(([k, v]) => (
        <div key={k} style={{ display: 'flex', justifyContent: 'space-between', gap: 14, padding: '7px 0', fontSize: 13.5 }}>
          <span style={{ color: '#65757F' }}>{k}</span>
          <span style={{ fontWeight: 600, textAlign: 'right' }}>{v}</span>
        </div>
      ))}
    </>
  );
}

export function Avatar({ text, size = 44, fontSize = 14 }: { text: string; size?: number; fontSize?: number }) {
  return (
    <span style={{ width: size, height: size, flex: 'none', borderRadius: '50%', background: '#D6F2F0', display: 'grid', placeItems: 'center', fontSize, fontWeight: 700, color: '#0E7C7B' }}>{text}</span>
  );
}

/** Centred "something went wrong" state used when a query fails. */
export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div style={{ ...card, textAlign: 'center', padding: '26px 14px 18px' }}>
      <div style={{ fontSize: 15.5, fontWeight: 600 }}>Something went wrong</div>
      <p style={{ margin: '7px 0 14px', fontSize: 13, lineHeight: 1.5, color: '#4A5A64' }}>{message}</p>
      {onRetry && <SecondaryButton onClick={onRetry}>Try again</SecondaryButton>}
    </div>
  );
}

export function Skeleton({ rows = 3 }: { rows?: number }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }} aria-busy="true" aria-label="Loading">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} style={{ height: 66, borderRadius: 12, background: '#E3E9ED', animation: 'dl-shimmer 1.3s infinite' }} />
      ))}
    </div>
  );
}
