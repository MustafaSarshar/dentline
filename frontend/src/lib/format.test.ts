import { describe, expect, it } from 'vitest';
import {
  addDays,
  dateTimeLabel,
  dateWithYear,
  daysBetween,
  historyStamp,
  hoursLabel,
  initials,
  isoWeek,
  longDate,
  minutesOf,
  mmss,
  mondayOf,
  price,
  shortDate,
  signed,
  timeOf,
  whenLabel,
} from './format';

/** The API renders instants in the clinic's zone, so the frontend reads the string as written. */
const ISO = '2026-09-08T08:00:00+02:00';

describe('reading API timestamps', () => {
  it('takes the wall clock straight off the string, whatever the browser zone is', () => {
    expect(timeOf(ISO)).toBe('08:00');
    expect(whenLabel(ISO)).toBe('Tue 8 Sep · 08:00');
    expect(dateTimeLabel('2026-09-02T06:00:00+02:00')).toBe('2 Sep 2026, 06:00');
    expect(historyStamp('2026-09-02T14:12:00+02:00')).toBe('2 Sep 14:12');
  });

  it('formats dates the way the design writes them', () => {
    expect(shortDate('2026-09-08')).toBe('Tue 8 Sep');
    expect(longDate('2026-09-08')).toBe('Tuesday 8 September');
    expect(dateWithYear('2026-03-12')).toBe('12 Mar 2026');
  });
});

describe('prices', () => {
  it('groups thousands with the narrow no-break space the design uses', () => {
    expect(price(890)).toBe('890');
    expect(price(1190)).toBe('1 190');
    expect(price(4500)).toBe('4 500');
  });
});

describe('practitioners', () => {
  it('builds initials without the title', () => {
    expect(initials('Dr. Astrid Nordvik')).toBe('AN');
    expect(initials('Mari Lund')).toBe('ML');
    expect(initials('Dr. Henrik Sæther')).toBe('HS');
  });

  it('spans the week from the earliest start to the latest end', () => {
    const hours = [
      { dayOfWeek: 1, startTime: '08:00', endTime: '11:30' },
      { dayOfWeek: 1, startTime: '12:15', endTime: '15:30' },
      { dayOfWeek: 2, startTime: '10:00', endTime: '18:00' },
    ];
    expect(hoursLabel(hours)).toBe('08:00–18:00');
    expect(hoursLabel([])).toBe('');
  });
});

describe('calendar arithmetic', () => {
  it('walks days across a month boundary', () => {
    expect(addDays('2026-09-08', 3)).toBe('2026-09-11');
    expect(addDays('2026-09-30', 1)).toBe('2026-10-01');
    expect(addDays('2026-09-01', -1)).toBe('2026-08-31');
  });

  it('finds the Monday of a week, including from a Sunday', () => {
    expect(mondayOf('2026-09-08')).toBe('2026-09-07'); // Tuesday
    expect(mondayOf('2026-09-07')).toBe('2026-09-07'); // Monday itself
    expect(mondayOf('2026-09-13')).toBe('2026-09-07'); // Sunday belongs to the week before
  });

  it('numbers ISO weeks', () => {
    expect(isoWeek('2026-09-08')).toBe(37);
    expect(isoWeek('2026-01-01')).toBe(1);
  });

  it('counts whole days between dates', () => {
    expect(daysBetween('2026-09-08', '2026-09-12')).toBe(4);
    expect(daysBetween('2026-09-12', '2026-09-08')).toBe(-4);
  });

  it('reads clock strings as minutes', () => {
    expect(minutesOf('08:00')).toBe(480);
    expect(minutesOf('12:15')).toBe(735);
  });
});

describe('countdowns and deltas', () => {
  it('renders m:ss the way the offer sheet does', () => {
    expect(mmss(754)).toBe('12:34');
    expect(mmss(59)).toBe('0:59');
    expect(mmss(0)).toBe('0:00');
  });

  it('signs deltas with the typographic minus', () => {
    expect(signed(2)).toBe('+2');
    expect(signed(-1)).toBe('−1');
    expect(signed(0.6, 1)).toBe('+0.6');
  });
});
