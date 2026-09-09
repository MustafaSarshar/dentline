import type { ReactNode } from 'react';
import { useToastMessage } from '../components/Toast';

interface Props {
  title: string;
  sub: string;
  canBack: boolean;
  onBack: () => void;
  /** 0–2 lights the progress dots; undefined hides them. */
  step?: number;
  children: ReactNode;
  /** Sheets and dialogs are rendered inside the phone frame. */
  overlay?: ReactNode;
}

/** The 390px phone frame with status bar, header and toast, exactly as in the design. */
export function PhoneShell({ title, sub, canBack, onBack, step, children, overlay }: Props) {
  const toast = useToastMessage();
  return (
    <div className="patient-app" style={{ minHeight: '100vh', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 18, padding: '28px 16px 56px', boxSizing: 'border-box' }}>
      <div style={{ width: 390, maxWidth: '100%', background: '#FAFBFC', borderRadius: 28, boxShadow: '0 24px 60px rgba(30,42,50,.16),0 2px 6px rgba(30,42,50,.06)', overflow: 'hidden', position: 'relative', minHeight: 800, display: 'flex', flexDirection: 'column' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '14px 20px 6px', fontSize: 11, fontWeight: 600, color: '#65757F' }}>
          <span>09:41</span>
          <span style={{ letterSpacing: '.14em' }}>DENTLINE</span>
          <span>100%</span>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '6px 18px 12px', minHeight: 44 }}>
          {canBack && (
            <button className="p-back" onClick={onBack} aria-label="Go back" style={{ width: 36, height: 36, flex: 'none', borderRadius: 10, border: '1px solid #D3DBE0', background: '#FFF', color: '#1E2A32', fontSize: 16, lineHeight: 1, cursor: 'pointer', display: 'grid', placeItems: 'center' }}>
              ←
            </button>
          )}
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 15, fontWeight: 600, letterSpacing: '-.01em' }}>{title}</div>
            <div style={{ fontSize: 12, color: '#65757F', marginTop: 1 }}>{sub}</div>
          </div>
          {step !== undefined && (
            <div style={{ display: 'flex', gap: 5, flex: 'none' }} role="progressbar" aria-label="Booking progress" aria-valuenow={step + 1} aria-valuemin={1} aria-valuemax={3}>
              {[0, 1, 2].map((i) => (
                <span key={i} style={{ width: 7, height: 7, borderRadius: '50%', background: i <= step ? '#0E7C7B' : '#D3DBE0' }} />
              ))}
            </div>
          )}
        </div>

        <div style={{ flex: 1, padding: '4px 18px 28px', overflow: 'hidden' }}>{children}</div>

        {overlay}

        {toast && (
          <div role="status" style={{ position: 'absolute', left: 16, right: 16, bottom: 18, background: '#1E2A32', color: '#FFF', padding: '13px 15px', borderRadius: 12, fontSize: 13, fontWeight: 500, display: 'flex', gap: 10, alignItems: 'center', animation: 'dl-up .25s ease', boxShadow: '0 10px 26px rgba(30,42,50,.28)' }}>
            <span style={{ color: '#8FE3DF' }}>✓</span>
            {toast}
          </div>
        )}
      </div>
    </div>
  );
}
