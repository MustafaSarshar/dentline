import type { CSSProperties } from 'react';
import type { Appointment } from '../api/types';
import { timeOf } from '../lib/format';
import { styleOf } from '../lib/status';
import type { StatusOverrides } from './StaffApp';

interface Props {
  appointment: Appointment;
  overrides: StatusOverrides;
  onOpen: (id: string) => void;
  style?: CSSProperties;
  /** The day calendar's block height in px, which decides how much text fits. */
  height?: number;
  /** The week overview's fixed-size variant. */
  compact?: boolean;
}

/**
 * The status-coloured appointment block used by the day calendar and the week overview.
 *
 * On the day calendar a block is as tall as the appointment is long (1 px per minute, floor 34),
 * so a 30-minute check-up cannot fit the three lines the design draws. Rather than clip the text
 * mid-glyph, short blocks drop to two lines and then to a single "08:00 Patient" line. The full
 * detail stays reachable through the tooltip, the label for screen readers, and the drawer.
 */
export function AppointmentBlock({ appointment, overrides, onOpen, style, height, compact }: Props) {
  const status = overrides[appointment.id] ?? appointment.status;
  const st = styleOf(status);
  const start = timeOf(appointment.startTime);
  const range = `${start}–${timeOf(appointment.endTime)}`;
  const strike = status === 'CANCELLED' ? 'line-through' : 'none';
  const treatment = appointment.treatmentType.name;
  const description = `${appointment.patient.name}, ${treatment} at ${start}, ${st.label}`;

  const lines = compact || height === undefined ? 3 : height >= 60 ? 3 : height >= 44 ? 2 : 1;
  const ellipsis: CSSProperties = { whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' };
  const padding = lines === 1 ? '5px 9px' : compact ? '8px 9px' : '7px 9px';

  return (
    <button
      className={compact ? 's-week-block' : 's-block'}
      onClick={() => onOpen(appointment.id)}
      aria-label={description}
      title={description}
      style={{ textAlign: 'left', padding, borderRadius: compact ? 9 : 10, border: `1.5px ${st.borderStyle} ${st.border}`, background: st.bg, color: st.fg, cursor: 'pointer', overflow: 'hidden', display: 'flex', flexDirection: 'column', gap: 2, boxSizing: 'border-box', ...style }}
    >
      {lines === 1 ? (
        <span style={{ fontSize: 11, fontWeight: 600, ...ellipsis }}>
          <span style={{ opacity: 0.85, fontVariantNumeric: 'tabular-nums' }}>{start}</span>{' '}
          <span style={{ textDecoration: strike }}>{appointment.patient.name}</span>
        </span>
      ) : (
        <>
          <span style={{ fontSize: 11, fontWeight: 600, opacity: 0.85, fontVariantNumeric: 'tabular-nums' }}>{range}</span>
          <span style={{ fontSize: compact ? 12 : 12.5, fontWeight: 600, textDecoration: strike, ...ellipsis }}>{appointment.patient.name}</span>
          {lines === 3 && (
            <span style={{ fontSize: compact ? 10.5 : 11, opacity: 0.8, ...ellipsis }}>
              {compact ? `${treatment} · ${appointment.treatmentType.durationMinutes} min` : treatment}
            </span>
          )}
        </>
      )}
    </button>
  );
}

export const panel: CSSProperties = { background: '#FFF', border: '1px solid #E3E9ED', borderRadius: 12, overflow: 'hidden' };

export function PanelMessage({ children }: { children: React.ReactNode }) {
  return <div style={{ ...panel, padding: '28px 18px', textAlign: 'center', fontSize: 12.5, color: '#8A98A1' }}>{children}</div>;
}
