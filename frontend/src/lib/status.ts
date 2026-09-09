import type { AppointmentStatus } from '../api/types';

export interface StatusStyle {
  label: string;
  bg: string;
  border: string;
  fg: string;
  borderStyle: 'solid' | 'dashed';
}

/** The status palette from the design, keyed by the lower-case form the prototype used. */
export const STATUS: Record<Lowercase<AppointmentStatus>, StatusStyle> = {
  requested: { label: 'Requested', bg: '#FFF8EC', border: '#D99A2B', fg: '#7A5310', borderStyle: 'dashed' },
  confirmed: { label: 'Confirmed', bg: '#D6F2F0', border: '#0E7C7B', fg: '#0B5654', borderStyle: 'solid' },
  completed: { label: 'Completed', bg: '#E8F0E8', border: '#7E9E82', fg: '#3E5641', borderStyle: 'solid' },
  cancelled: { label: 'Cancelled', bg: '#F2F6F8', border: '#C3CDD3', fg: '#65757F', borderStyle: 'solid' },
  no_show: { label: 'No-show', bg: '#FCEDEC', border: '#B3261E', fg: '#8F1E18', borderStyle: 'solid' },
};

export const STATUS_ORDER = ['requested', 'confirmed', 'completed', 'cancelled', 'no_show'] as const;

export const styleOf = (status: AppointmentStatus): StatusStyle => STATUS[status.toLowerCase() as Lowercase<AppointmentStatus>];
