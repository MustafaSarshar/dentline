import { useMetrics } from '../api/hooks';
import { signed } from '../lib/format';

const TEAL = '#0E7C7B';
const RED = '#B3261E';

/** The four stat cards, fed by GET /api/staff/metrics/summary. */
export function MetricsStrip() {
  const metrics = useMetrics();
  const m = metrics.data;

  const cards = m
    ? [
        { label: 'Bookings today', value: String(m.bookingsToday.value), delta: signed(m.bookingsToday.delta), good: m.bookingsToday.delta >= 0, note: 'vs. same day last week' },
        { label: 'Cancellations this week', value: String(m.cancellationsThisWeek.value), delta: signed(m.cancellationsThisWeek.delta), good: m.cancellationsThisWeek.delta <= 0, note: `${m.cancellationsThisWeek.refilledFromWaitlist} refilled from waitlist` },
        { label: 'No-show rate', value: `${(m.noShowRate.value * 100).toFixed(1)}%`, delta: signed(m.noShowRate.delta * 100, 1), good: m.noShowRate.delta <= 0, note: 'rolling 30 days' },
        { label: 'Avg. waitlist wait', value: `${m.waitlistAvgWaitDays.value.toFixed(1)} d`, delta: signed(m.waitlistAvgWaitDays.delta, 1), good: m.waitlistAvgWaitDays.delta <= 0, note: `across ${m.waitlistAvgWaitDays.queued} queued patients` },
      ]
    : [
        { label: 'Bookings today', value: '–', delta: '', good: true, note: metrics.error ? 'unavailable' : 'loading…' },
        { label: 'Cancellations this week', value: '–', delta: '', good: true, note: metrics.error ? 'unavailable' : 'loading…' },
        { label: 'No-show rate', value: '–', delta: '', good: true, note: 'rolling 30 days' },
        { label: 'Avg. waitlist wait', value: '–', delta: '', good: true, note: metrics.error ? 'unavailable' : 'loading…' },
      ];

  return (
    <div data-stagger-s="1" className="s-metrics" style={{ display: 'grid', gridTemplateColumns: 'repeat(4,minmax(0,1fr))', gap: 12, padding: '18px 28px 4px' }}>
      {cards.map((c) => (
        <div key={c.label} className="s-metric" style={{ background: '#FFF', border: '1px solid #E3E9ED', borderRadius: 12, padding: '14px 16px', transition: 'box-shadow .2s ease,transform .18s ease' }}>
          <div style={{ fontSize: 11, fontWeight: 600, letterSpacing: '.07em', textTransform: 'uppercase', color: '#8A98A1' }}>{c.label}</div>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginTop: 7 }}>
            <span style={{ fontSize: 25, fontWeight: 700, letterSpacing: '-.02em' }}>{c.value}</span>
            <span style={{ fontSize: 11.5, fontWeight: 600, color: c.good ? TEAL : RED }}>{c.delta}</span>
          </div>
          <div style={{ fontSize: 11.5, color: '#65757F', marginTop: 2 }}>{c.note}</div>
        </div>
      ))}
    </div>
  );
}
