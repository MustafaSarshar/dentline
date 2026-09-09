import { useEffect, useMemo, useState } from 'react';
import type { UseQueryResult } from '@tanstack/react-query';
import type { Availability, Slot } from '../../api/types';
import { addDays, minutesOf, parseDate, shortDate, timeOf } from '../../lib/format';
import { ErrorState, label } from '../ui';

const DOW = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

interface Props {
  practitionerLabel: string;
  today: string;
  availability: UseQueryResult<Availability>;
  onPick: (slot: Slot) => void;
  onJoinWaitlist: () => void;
}

/** Day strip + slot grid. Availability is one query for seven days; switching days is instant. */
export function SlotScreen({ practitionerLabel, today, availability, onPick, onJoinWaitlist }: Props) {
  const days = availability.data?.days ?? [];
  const [selected, setSelected] = useState<string>(today);

  // Land on the first day with an opening; the design's "Fully booked" card covers the rest.
  useEffect(() => {
    if (!availability.data) return;
    const firstOpen = availability.data.days.find((d) => d.slots.length > 0);
    setSelected(firstOpen?.date ?? today);
  }, [availability.data, today]);

  const day = days.find((d) => d.date === selected);
  const groups = useMemo(() => {
    const slots = day?.slots ?? [];
    return [
      { label: 'Morning', slots: slots.filter((s) => minutesOf(timeOf(s.startTime)) < 720) },
      { label: 'Afternoon', slots: slots.filter((s) => minutesOf(timeOf(s.startTime)) >= 720) },
    ].filter((g) => g.slots.length > 0);
  }, [day]);

  const nextOpenDay = () => {
    const next = days.find((d) => d.date !== selected && d.slots.length > 0);
    if (next) setSelected(next.date);
  };

  const loading = availability.isPending || availability.isFetching;
  const strip = days.length ? days : Array.from({ length: 7 }, (_, i) => ({ date: addDays(today, i), closed: false, slots: [] as Slot[] }));

  return (
    <div>
      <div style={{ display: 'flex', gap: 8, overflowX: 'auto', padding: '4px 0 12px' }}>
        {strip.map((d) => {
          const closed = d.closed;
          const full = !closed && d.slots.length === 0;
          const sel = d.date === selected;
          const note = closed ? 'Closed' : full ? 'Full' : `${d.slots.length} free`;
          return (
            <button
              key={d.date}
              onClick={() => !closed && setSelected(d.date)}
              disabled={closed}
              style={{ flex: 'none', width: 58, padding: '9px 0', borderRadius: 12, border: `1px solid ${sel ? '#0E7C7B' : '#E3E9ED'}`, background: sel ? '#0E7C7B' : closed ? '#F2F6F8' : '#FFF', color: sel ? '#FFF' : closed ? '#8A98A1' : '#1E2A32', cursor: closed ? 'not-allowed' : 'pointer', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 3 }}
            >
              <span style={{ fontSize: 11, fontWeight: 500, opacity: 0.8 }}>{DOW[parseDate(d.date).getDay()]}</span>
              <span style={{ fontSize: 16, fontWeight: 600 }}>{parseDate(d.date).getDate()}</span>
              <span style={{ fontSize: 9.5, fontWeight: 600, letterSpacing: '.02em' }}>{loading && !days.length ? '' : note}</span>
            </button>
          );
        })}
      </div>

      {availability.error ? (
        <ErrorState message={availability.error.message} onRetry={() => availability.refetch()} />
      ) : loading ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }} aria-busy="true">
          <div style={{ height: 12, width: 96, borderRadius: 6, background: '#E3E9ED', animation: 'dl-shimmer 1.3s infinite' }} />
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 9 }}>
            {Array.from({ length: 9 }, (_, i) => (
              <div key={i} style={{ height: 42, borderRadius: 12, background: '#E3E9ED', animation: 'dl-shimmer 1.3s infinite' }} />
            ))}
          </div>
        </div>
      ) : groups.length > 0 ? (
        <div style={{ animation: 'dl-fade .25s ease' }}>
          {groups.map((g) => (
            <div key={g.label} style={{ marginBottom: 20 }}>
              <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 9 }}>
                <span style={label()}>{g.label}</span>
                <span style={{ fontSize: 11.5, color: '#8A98A1' }}>{g.slots.length} open</span>
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 9 }}>
                {g.slots.map((s) => (
                  <button key={s.startTime} className="p-slot" onClick={() => onPick(s)} style={{ padding: '12px 0', borderRadius: 12, border: '1px solid #CFE7E6', background: '#FFF', color: '#0E7C7B', fontSize: 14, fontWeight: 600, cursor: 'pointer' }}>
                    {timeOf(s.startTime)}
                  </button>
                ))}
              </div>
            </div>
          ))}
          <p style={{ margin: 0, fontSize: 11.5, lineHeight: 1.5, color: '#8A98A1' }}>
            Openings are calculated live from {practitionerLabel}'s working hours minus booked appointments.
          </p>
        </div>
      ) : (
        <div style={{ animation: 'dl-fade .25s ease', textAlign: 'center', padding: '26px 14px 10px', background: '#FFF', border: '1px solid #E3E9ED', borderRadius: 12 }}>
          <div style={{ width: 64, height: 64, margin: '0 auto 16px', borderRadius: '50%', background: '#D6F2F0', display: 'grid', placeItems: 'center' }}>
            <span style={{ width: 26, height: 26, borderRadius: '8px 8px 13px 13px', background: '#0E7C7B', display: 'block' }} />
          </div>
          <div style={{ fontSize: 15.5, fontWeight: 600 }}>This day is fully booked</div>
          <p style={{ margin: '7px 0 18px', fontSize: 13, lineHeight: 1.5, color: '#4A5A64' }}>
            Nothing left on {shortDate(selected)} for {practitionerLabel}. Join the waitlist and we will offer you the first cancellation that fits.
          </p>
          <button className="p-primary" onClick={onJoinWaitlist} style={{ width: '100%', padding: 14, border: 'none', borderRadius: 12, background: '#0E7C7B', color: '#FFF', fontSize: 14.5, fontWeight: 600, cursor: 'pointer' }}>
            Join waitlist
          </button>
          <button className="p-secondary" onClick={nextOpenDay} style={{ width: '100%', marginTop: 9, padding: 13, border: '1px solid #D3DBE0', borderRadius: 12, background: '#FFF', color: '#1E2A32', fontSize: 13.5, fontWeight: 600, cursor: 'pointer' }}>
            See next open day
          </button>
        </div>
      )}
    </div>
  );
}
