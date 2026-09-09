import { useEffect, useState } from 'react';
import { useDaySchedule } from '../api/hooks';
import { hoursLabel, initials, minutesOf, nowMinutesIn, timeOf, titleLabel } from '../lib/format';
import { AppointmentBlock, PanelMessage, panel } from './blocks';
import type { StatusOverrides } from './StaffApp';

const PX_PER_MINUTE = 1;

interface Props {
  date: string;
  timezone: string;
  overrides: StatusOverrides;
  onOpen: (id: string) => void;
}

/** One column per practitioner, 1 px per minute, hatched outside working hours, a live "now" line. */
export function TodayView({ date, timezone, overrides, onOpen }: Props) {
  const schedule = useDaySchedule(date);
  const [nowMin, setNowMin] = useState(() => nowMinutesIn(timezone));
  useEffect(() => {
    const t = window.setInterval(() => setNowMin(nowMinutesIn(timezone)), 30_000);
    return () => window.clearInterval(t);
  }, [timezone]);

  if (schedule.error) return <PanelMessage>Could not load today's schedule: {schedule.error.message}</PanelMessage>;
  const s = schedule.data;
  if (!s) return <div style={{ ...panel, height: 660, background: '#E3E9ED', animation: 'dl-shimmer 1.3s infinite' }} aria-busy="true" />;

  const dayStart = minutesOf(s.clinicOpen.startTime);
  const dayEnd = minutesOf(s.clinicOpen.endTime);
  const px = PX_PER_MINUTE;
  const gridHeight = (dayEnd - dayStart) * px;
  const hours = Array.from({ length: Math.floor((dayEnd - dayStart) / 60) + 1 }, (_, i) => dayStart + i * 60);
  const columns = `64px repeat(${s.practitioners.length},minmax(0,1fr))`;
  const showNow = nowMin >= dayStart && nowMin <= dayEnd;

  return (
    <div className="s-calendar-scroll">
      <div style={panel}>
        <div style={{ display: 'grid', gridTemplateColumns: columns, borderBottom: '1px solid #E3E9ED' }}>
          <div style={{ padding: '12px 10px' }} />
          {s.practitioners.map((p) => (
            <div key={p.id} style={{ padding: '12px 14px', borderLeft: '1px solid #E3E9ED', display: 'flex', alignItems: 'center', gap: 10 }}>
              <span style={{ width: 32, height: 32, flex: 'none', borderRadius: '50%', background: '#D6F2F0', display: 'grid', placeItems: 'center', fontSize: 11.5, fontWeight: 700, color: '#0E7C7B' }}>{initials(p.name)}</span>
              <span style={{ minWidth: 0 }}>
                <span style={{ display: 'block', fontSize: 13, fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{p.name}</span>
                <span style={{ display: 'block', fontSize: 11, color: '#65757F' }}>
                  {titleLabel(p.title)} · {p.workingHours.length ? hoursLabel(p.workingHours.map((w) => ({ dayOfWeek: 1, ...w }))) : 'Off today'}
                </span>
              </span>
            </div>
          ))}
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: columns, position: 'relative' }}>
          <div style={{ position: 'relative', height: gridHeight }}>
            {hours.map((h) => (
              <div key={h} style={{ position: 'absolute', top: (h - dayStart) * px, right: 9, fontSize: 10.5, color: '#8A98A1', transform: 'translateY(-6px)', fontVariantNumeric: 'tabular-nums' }}>
                {String(Math.floor(h / 60)).padStart(2, '0')}:00
              </div>
            ))}
          </div>
          {s.practitioners.map((p) => {
            // Everything outside the working windows is hatched: before, between (lunch) and after.
            const edges = [dayStart, ...p.workingHours.flatMap((w) => [minutesOf(w.startTime), minutesOf(w.endTime)]), dayEnd];
            const gaps: Array<[number, number]> = [];
            for (let i = 0; i < edges.length; i += 2) if (edges[i + 1] > edges[i]) gaps.push([edges[i], edges[i + 1]]);
            return (
              <div key={p.id} data-appt="1" style={{ position: 'relative', height: gridHeight, borderLeft: '1px solid #E3E9ED' }}>
                {hours.map((h) => (
                  <div key={h} style={{ position: 'absolute', left: 0, right: 0, top: (h - dayStart) * px, height: 1, background: '#F0F4F6' }} />
                ))}
                {gaps.map(([from, to]) => (
                  <div key={from} style={{ position: 'absolute', left: 0, right: 0, top: (from - dayStart) * px, height: (to - from) * px, background: 'repeating-linear-gradient(135deg,#F7F9FA,#F7F9FA 6px,#FFF 6px,#FFF 12px)' }} />
                ))}
                {showNow && <div style={{ position: 'absolute', left: 0, right: 0, top: (nowMin - dayStart) * px, height: 2, background: '#0E7C7B', opacity: 0.55 }} />}
                {p.appointments.map((a) => {
                  const start = minutesOf(timeOf(a.startTime));
                  const duration = minutesOf(timeOf(a.endTime)) - start;
                  return (
                    <AppointmentBlock
                      key={a.id}
                      appointment={a}
                      overrides={overrides}
                      onOpen={onOpen}
                      style={{ position: 'absolute', left: 7, right: 7, top: (start - dayStart) * px, height: Math.max(duration * px - 4, 34) }}
                    />
                  );
                })}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
