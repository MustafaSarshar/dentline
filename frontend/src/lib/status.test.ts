import { describe, expect, it } from 'vitest';
import type { AppointmentStatus } from '../api/types';
import { STATUS_ORDER, styleOf } from './status';

const ALL: AppointmentStatus[] = ['REQUESTED', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'NO_SHOW'];

describe('status palette', () => {
  it('maps every API status the backend can return', () => {
    for (const status of ALL) expect(styleOf(status)).toBeDefined();
  });

  it('uses the design tokens, and dashes only the requested border', () => {
    expect(styleOf('REQUESTED')).toMatchObject({ label: 'Requested', bg: '#FFF8EC', border: '#D99A2B', borderStyle: 'dashed' });
    expect(styleOf('CONFIRMED')).toMatchObject({ label: 'Confirmed', bg: '#D6F2F0', border: '#0E7C7B', borderStyle: 'solid' });
    expect(styleOf('NO_SHOW')).toMatchObject({ label: 'No-show', bg: '#FCEDEC', border: '#B3261E' });
  });

  it('pairs every badge with a text label, so colour is never the only signal', () => {
    for (const status of ALL) expect(styleOf(status).label).not.toBe('');
  });

  it('lists the legend in the order the design shows it', () => {
    expect([...STATUS_ORDER]).toEqual(['requested', 'confirmed', 'completed', 'cancelled', 'no_show']);
  });
});
