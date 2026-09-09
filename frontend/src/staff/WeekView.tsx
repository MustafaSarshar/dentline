import { useWeekSchedule } from '../api/hooks';
import { addDays, shortDate } from '../lib/format';
import { AppointmentBlock, PanelMessage, panel } from './blocks';
import type { StatusOverrides } from './StaffApp';

interface Props {
  monday: string;
  today: string;
  overrides: StatusOverrides;
  onOpen: (id: string) => void;
}

/** Mon–Fri columns; read + click only, no drag. */
export function WeekView({ monday, today, overrides, onOpen }: Props) {
  const friday = addDays(monday, 4);
  const week = useWeekSchedule(monday, friday);

  if (week.error) return <PanelMessage>Could not load the week: {week.error.message}</PanelMessage>;
  if (!week.data) return <div style={{ ...panel, height: 420, background: '#E3E9ED', animation: 'dl-shimmer 1.3s infinite' }} aria-busy="true" />;

  return (
    <div style={panel}>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(5,minmax(0,1fr))' }}>
        {week.data.days.map((d) => {
          const isToday = d.date === today;
          const sub = isToday
            ? `Today · ${d.bookedCount} booked`
            : d.openSlotCount === 0
              ? 'Fully booked'
              : `${d.bookedCount} booked` + (d.noShowCount > 0 ? ` · ${d.noShowCount} no-show` : '') + (d.date > today ? ' · openings' : '');
          return (
            <div key={d.date} style={{ borderLeft: '1px solid #E3E9ED', minWidth: 0 }}>
              <div style={{ padding: '12px 14px', borderBottom: '1px solid #E3E9ED', background: isToday ? '#D6F2F0' : '#FFF' }}>
                <div style={{ fontSize: 12.5, fontWeight: 600 }}>{shortDate(d.date)}</div>
                <div style={{ fontSize: 11, color: '#65757F', marginTop: 2 }}>{sub}</div>
              </div>
              <div style={{ padding: 9, display: 'flex', flexDirection: 'column', gap: 7, minHeight: 340 }}>
                {d.appointments.map((a) => (
                  <AppointmentBlock key={a.id} appointment={a} overrides={overrides} onOpen={onOpen} compact />
                ))}
                {d.appointments.length === 0 && (
                  <div style={{ padding: '16px 6px', textAlign: 'center', fontSize: 11.5, color: '#8A98A1', lineHeight: 1.5 }}>Nothing booked yet — a quiet day.</div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
