import type { PractitionerTitle, PreferredWindow, WorkingHours } from '../api/types';

// The API renders every instant in the clinic's zone, so the wall-clock part of the string is
// exactly what the UI should show. That keeps the frontend free of time-zone arithmetic.

const DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const DOW_LONG = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const MON_LONG = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

/** "2026-09-08" → local Date at midnight (only used for weekday/month lookups). */
export function parseDate(date: string): Date {
  const [y, m, d] = date.split('-').map(Number);
  return new Date(y, m - 1, d);
}

export const timeOf = (iso: string): string => iso.slice(11, 16);
export const dateOf = (iso: string): string => iso.slice(0, 10);
export const minutesOf = (hhmm: string): number => Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3, 5));

/** "Tue 8 Sep" */
export function shortDate(date: string): string {
  const d = parseDate(date);
  return `${DOW[d.getDay()]} ${d.getDate()} ${MON[d.getMonth()]}`;
}

/** "Tuesday 8 September" */
export function longDate(date: string): string {
  const d = parseDate(date);
  return `${DOW_LONG[d.getDay()]} ${d.getDate()} ${MON_LONG[d.getMonth()]}`;
}

/** "12 Mar 2026" */
export function dateWithYear(date: string): string {
  const d = parseDate(date);
  return `${d.getDate()} ${MON[d.getMonth()]} ${d.getFullYear()}`;
}

/** "Tue 8 Sep · 08:00" */
export const whenLabel = (iso: string): string => `${shortDate(dateOf(iso))} · ${timeOf(iso)}`;

/** "2 Sep 2026, 06:00" */
export const dateTimeLabel = (iso: string): string => `${dateWithYear(dateOf(iso))}, ${timeOf(iso)}`;

/** "2 Sep 14:12" */
export function historyStamp(iso: string): string {
  const d = parseDate(dateOf(iso));
  return `${d.getDate()} ${MON[d.getMonth()]} ${timeOf(iso)}`;
}

/** 1190 → "1 190" (non-breaking thin space, as in the design). */
export const price = (nok: number): string => Math.round(nok).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ' ');

export const initials = (name: string): string =>
  name
    .replace(/^Dr\.\s*/, '')
    .split(/\s+/)
    .map((w) => w[0])
    .join('')
    .slice(0, 2)
    .toUpperCase();

export const titleLabel = (title: PractitionerTitle): string => (title === 'DENTIST' ? 'Dentist' : 'Hygienist');

/** "08:00–15:30" — earliest start to latest end across the week. */
export function hoursLabel(workingHours: WorkingHours[]): string {
  if (workingHours.length === 0) return '';
  const start = workingHours.map((w) => w.startTime).sort()[0];
  const end = workingHours.map((w) => w.endTime).sort().at(-1);
  return `${start}–${end}`;
}

export const WINDOW_LABEL: Record<PreferredWindow, string> = {
  WEEKDAY_MORNINGS: 'Weekday mornings',
  WEEKDAY_AFTERNOONS: 'Weekday afternoons',
  AFTER_16: 'After 16:00',
  ANY_THIS_WEEK: 'Anything this week',
  FRIDAYS_ONLY: 'Fridays only',
};

export const WINDOWS: PreferredWindow[] = ['WEEKDAY_MORNINGS', 'WEEKDAY_AFTERNOONS', 'AFTER_16', 'ANY_THIS_WEEK', 'FRIDAYS_ONLY'];

/** Today's date in the clinic zone as YYYY-MM-DD. */
export function todayIn(timeZone: string): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
}

/** Current wall-clock minutes since midnight in the clinic zone. */
export function nowMinutesIn(timeZone: string): number {
  const hhmm = new Intl.DateTimeFormat('en-GB', { timeZone, hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date());
  return minutesOf(hhmm);
}

export function addDays(date: string, days: number): string {
  const d = parseDate(date);
  d.setDate(d.getDate() + days);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** Monday of the ISO week containing `date`. */
export function mondayOf(date: string): string {
  const d = parseDate(date);
  const shift = (d.getDay() + 6) % 7;
  return addDays(date, -shift);
}

export function isoWeek(date: string): number {
  const d = parseDate(date);
  const target = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()));
  const dayNum = target.getUTCDay() || 7;
  target.setUTCDate(target.getUTCDate() + 4 - dayNum);
  const yearStart = new Date(Date.UTC(target.getUTCFullYear(), 0, 1));
  return Math.ceil(((target.getTime() - yearStart.getTime()) / 86_400_000 + 1) / 7);
}

/** Whole days between an ISO instant and now. */
export const daysSince = (iso: string): number => Math.max(0, Math.floor((Date.now() - Date.parse(iso)) / 86_400_000));

export const daysBetween = (from: string, to: string): number => Math.round((parseDate(to).getTime() - parseDate(from).getTime()) / 86_400_000);

/** 754 → "12:34" */
export const mmss = (seconds: number): string => `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;

export const secondsUntil = (iso: string): number => Math.max(0, Math.round((Date.parse(iso) - Date.now()) / 1000));

/** "+2" / "−1" with the typographic minus the design uses. */
export const signed = (n: number, digits = 0): string => (n < 0 ? '−' : '+') + Math.abs(n).toFixed(digits);
