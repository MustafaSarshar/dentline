import type { CSSProperties } from 'react';
import type { Appointment } from '../api/types';
import { timeOf } from '../lib/format';
import { styleOf } from '../lib/status';
import type { StatusOverrides } from './StaffApp';

/** The status-coloured appointment block used by the day calendar and the week overview. */
export function AppointmentBlock({ appointment, overrides, onOpen, style, compact }: { appointment: Appointment; overrides: StatusOverrides; onOpen: (id: string) => void; style?: CSSProperties; compact?: boolean }) {
  const status = overrides[appointment.id] ?? appointment.status;
  const st = styleOf(status);
  const time = `${timeOf(appointment.startTime)}–${timeOf(appointment.endTime)}`;
  const strike = status === 'CANCELLED' ? 'line-through' : 'none';
  return (
    <button
      className={compact ? 's-week-block' : 's-block'}
      onClick={() => onOpen(appointment.id)}
      aria-label={`${appointment.patient.name}, ${appointment.treatmentType.name} at ${timeOf(appointment.startTime)}, ${st.label}`}
      style={{ textAlign: 'left', padding: compact ? '8px 9px' : '7px 9px', borderRadius: compact ? 9 : 10, border: `1.5px ${st.borderStyle} ${st.border}`, background: st.bg, color: st.fg, cursor: 'pointer', overflow: 'hidden', display: 'flex', flexDirection: 'column', gap: 2, boxSizing: 'border-box', ...style }}
    >
      <span style={{ fontSize: 11, fontWeight: 600, opacity: 0.85, fontVariantNumeric: 'tabular-nums' }}>{time}</span>
      <span style={{ fontSize: compact ? 12 : 12.5, fontWeight: 600, textDecoration: strike, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{appointment.patient.name}</span>
      <span style={{ fontSize: compact ? 10.5 : 11, opacity: 0.8, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
        {compact ? `${appointment.treatmentType.name} · ${appointment.treatmentType.durationMinutes} min` : appointment.treatmentType.name}
      </span>
    </button>
  );
}

export const panel: CSSProperties = { background: '#FFF', border: '1px solid #E3E9ED', borderRadius: 12, overflow: 'hidden' };

export function PanelMessage({ children }: { children: React.ReactNode }) {
  return <div style={{ ...panel, padding: '28px 18px', textAlign: 'center', fontSize: 12.5, color: '#8A98A1' }}>{children}</div>;
}
