import type { Appointment } from '../../api/types';
import { useToast } from '../../components/Toast';
import { whenLabel } from '../../lib/format';
import { OutlineTealButton, SecondaryButton, SummaryRows, card } from '../ui';

export function DoneScreen({ appointment, onViewAppointment }: { appointment: Appointment; onViewAppointment: () => void }) {
  const say = useToast();
  const firstName = appointment.patient.name.split(' ')[0];
  return (
    <div style={{ animation: 'dl-up .3s ease', textAlign: 'center', paddingTop: 18 }}>
      <div style={{ width: 76, height: 76, margin: '0 auto 18px', borderRadius: '50%', background: '#D6F2F0', display: 'grid', placeItems: 'center', fontSize: 30, color: '#0E7C7B', animation: 'dl-pop .5s cubic-bezier(.2,.8,.3,1) .06s both' }}>✓</div>
      <div style={{ fontSize: 20, fontWeight: 700, letterSpacing: '-.02em' }}>You are booked in</div>
      <p style={{ margin: '8px 0 20px', fontSize: 13.5, lineHeight: 1.55, color: '#4A5A64' }}>
        See you soon, {firstName}. A confirmation is on its way to {appointment.patient.email}.
      </p>
      <div style={{ ...card, textAlign: 'left' }}>
        <SummaryRows rows={[['Treatment', appointment.treatmentType.name], ['Practitioner', appointment.practitioner.name], ['When', whenLabel(appointment.startTime)], ['Duration', `${appointment.treatmentType.durationMinutes} min`]]} />
      </div>
      <OutlineTealButton style={{ marginTop: 14 }} onClick={() => { downloadIcs(appointment); say('Added to your calendar'); }}>Add to calendar</OutlineTealButton>
      <SecondaryButton style={{ marginTop: 9 }} onClick={onViewAppointment}>View my appointment</SecondaryButton>
      <div style={{ display: 'flex', gap: 9, alignItems: 'flex-start', marginTop: 18, padding: '13px 14px', background: '#D6F2F0', borderRadius: 12, textAlign: 'left' }}>
        <span style={{ fontSize: 14, lineHeight: 1.3 }}>🔔</span>
        <span style={{ fontSize: 12.5, lineHeight: 1.5, color: '#12545A' }}>You'll receive a reminder the day before.</span>
      </div>
    </div>
  );
}

/** A minimal iCalendar file built client-side; the instants already carry the clinic offset. */
function downloadIcs(a: Appointment) {
  const stamp = (iso: string) => new Date(iso).toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
  const ics = [
    'BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//Dentline//Booking//EN', 'BEGIN:VEVENT',
    `UID:${a.id}@dentline`, `DTSTAMP:${stamp(a.createdAt)}`, `DTSTART:${stamp(a.startTime)}`, `DTEND:${stamp(a.endTime)}`,
    `SUMMARY:${a.treatmentType.name} at Dentline`, `DESCRIPTION:${a.treatmentType.name} with ${a.practitioner.name}. Reference ${a.reference}.`,
    'END:VEVENT', 'END:VCALENDAR',
  ].join('\r\n');
  const url = URL.createObjectURL(new Blob([ics], { type: 'text/calendar' }));
  const link = Object.assign(document.createElement('a'), { href: url, download: `dentline-${a.reference}.ics` });
  link.click();
  URL.revokeObjectURL(url);
}
