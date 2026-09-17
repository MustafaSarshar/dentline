import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { TreatmentType } from '../../api/types';
import { TreatmentScreen } from './TreatmentScreen';

const TREATMENTS: TreatmentType[] = [
  { id: 't1', code: 'CU', name: 'Check-up', durationMinutes: 30, priceNok: 890 },
  { id: 't2', code: 'RC', name: 'Root canal', durationMinutes: 90, priceNok: 4500 },
];

describe('the first booking step', () => {
  it('lists what the clinic offers, with the code, duration and price from the API', () => {
    render(<TreatmentScreen treatments={TREATMENTS} onPick={vi.fn()} />);

    expect(screen.getByText('Check-up')).toBeDefined();
    expect(screen.getByText('CU')).toBeDefined();
    expect(screen.getByText('30 min')).toBeDefined();
    expect(screen.getByText(/890\s*kr/)).toBeDefined();
    expect(screen.getByText(/4\s*500\s*kr/)).toBeDefined();
  });

  it('hands the chosen treatment to the flow', () => {
    const onPick = vi.fn();
    render(<TreatmentScreen treatments={TREATMENTS} onPick={onPick} />);

    fireEvent.click(screen.getByText('Root canal'));

    expect(onPick).toHaveBeenCalledWith(TREATMENTS[1]);
  });

  it('renders every treatment as its own button, so the list is keyboard reachable', () => {
    render(<TreatmentScreen treatments={TREATMENTS} onPick={vi.fn()} />);

    expect(screen.getAllByRole('button')).toHaveLength(TREATMENTS.length);
  });
});
