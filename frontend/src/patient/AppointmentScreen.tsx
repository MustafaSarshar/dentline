import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { ApiError, api } from '../api/client';
import { useAppointment, useConfig } from '../api/hooks';
import { useToast } from '../components/Toast';
import { initials, whenLabel } from '../lib/format';
import { styleOf } from '../lib/status';
import { PhoneShell } from './PhoneShell';
import type { RescheduleIntent } from './PatientApp';
import { Avatar, ErrorState, OutlineTealButton, Skeleton, card, label } from './ui';

/** "My appointment", opened from the reminder link. */
export function AppointmentScreen() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const say = useToast();
  const queryClient = useQueryClient();
  const config = useConfig();
  const appointment = useAppointment(id);
  const [cancelOpen, setCancelOpen] = useState(false);

  const cancel = useMutation({
    mutationFn: () => api.cancel(id!, 'PATIENT'),
    onSuccess: (updated) => {
      queryClient.setQueryData(['appointment', id], updated);
      queryClient.invalidateQueries({ queryKey: ['availability'] });
      setCancelOpen(false);
      say('Cancelled — slot released to waitlist');
    },
    onError: (error) => {
      setCancelOpen(false);
      queryClient.invalidateQueries({ queryKey: ['appointment', id] });
      say(error instanceof ApiError ? error.message : 'Something went wrong');
    },
  });

  const a = appointment.data;
  const badge = a ? styleOf(a.status) : null;
  const active = !!a && (a.allowedActions.includes('cancel') || a.allowedActions.includes('reschedule'));
  const fromFlow = !!(location.state as { fromFlow?: boolean } | null)?.fromFlow;

  const reschedule = () => {
    if (!a) return;
    const intent: RescheduleIntent = { appointmentId: a.id, reference: a.reference, treatmentTypeId: a.treatmentType.id, practitionerId: a.practitioner.id };
    say('Pick a new time — old slot stays held');
    navigate('/', { state: { reschedule: intent } });
  };

  return (
    <PhoneShell
      title="My appointment"
      sub="Shared via your reminder link"
      canBack={fromFlow}
      onBack={() => navigate('/')}
      overlay={cancelOpen && a && (
        <div style={{ position: 'absolute', inset: 0, background: 'rgba(30,42,50,.42)', display: 'grid', placeItems: 'center', padding: 22, animation: 'dl-fade .2s ease' }} role="dialog" aria-modal="true" aria-label="Confirm cancellation">
          <div style={{ background: '#FFF', borderRadius: 16, padding: 20, animation: 'dl-up .24s ease' }}>
            <div style={{ fontSize: 16.5, fontWeight: 700, letterSpacing: '-.01em' }}>Cancel this appointment?</div>
            <p style={{ margin: '9px 0 18px', fontSize: 13, lineHeight: 1.55, color: '#4A5A64' }}>
              Your {whenLabel(a.startTime)} slot will be released to the waitlist right away, and may be taken within minutes. This cannot be undone.
            </p>
            <div style={{ display: 'flex', gap: 9 }}>
              <button className="p-secondary" onClick={() => setCancelOpen(false)} style={{ flex: 1, padding: 13, border: '1px solid #D3DBE0', borderRadius: 12, background: '#FFF', color: '#1E2A32', fontSize: 13.5, fontWeight: 600, cursor: 'pointer' }}>Keep it</button>
              <button className="p-danger" onClick={() => cancel.mutate()} disabled={cancel.isPending} style={{ flex: 1, padding: 13, border: 'none', borderRadius: 12, background: '#B3261E', color: '#FFF', fontSize: 13.5, fontWeight: 600, cursor: 'pointer' }}>Yes, cancel</button>
            </div>
          </div>
        </div>
      )}
    >
      {appointment.error ? (
        <ErrorState message={appointment.error instanceof ApiError && appointment.error.status === 404 ? 'We could not find this appointment.' : appointment.error.message} onRetry={() => appointment.refetch()} />
      ) : !a || !badge ? (
        <Skeleton rows={2} />
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={{ ...card, padding: 18 }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, marginBottom: 14 }}>
              <span style={label()}>Appointment {a.reference}</span>
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '5px 10px', borderRadius: 999, background: badge.bg, color: badge.fg, fontSize: 11.5, fontWeight: 600, border: `1px solid ${badge.border}` }}>
                <span style={{ width: 6, height: 6, borderRadius: '50%', background: badge.fg }} />
                {badge.label}
              </span>
            </div>
            <div style={{ fontSize: 19, fontWeight: 700, letterSpacing: '-.02em' }}>{whenLabel(a.startTime)}</div>
            <div style={{ fontSize: 13.5, color: '#4A5A64', marginTop: 4 }}>{a.treatmentType.name} · {a.treatmentType.durationMinutes} min · {a.practitioner.name}</div>
            <div style={{ height: 1, background: '#E3E9ED', margin: '16px 0' }} />
            <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
              <Avatar text={initials(a.practitioner.name)} size={42} fontSize={13} />
              <span style={{ fontSize: 12.5, lineHeight: 1.5, color: '#4A5A64' }}>
                {config.data?.clinic.name ?? 'Dentline Majorstuen'}
                <br />
                {config.data ? `${config.data.clinic.addressLine1}, ${config.data.clinic.addressLine2}` : 'Kirkeveien 64B, 0364 Oslo'}
              </span>
            </div>
          </div>
          {active && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 9 }}>
              {a.allowedActions.includes('reschedule') && <OutlineTealButton onClick={reschedule}>Reschedule</OutlineTealButton>}
              {a.allowedActions.includes('cancel') && (
                <button className="p-danger-outline" onClick={() => setCancelOpen(true)} style={{ width: '100%', padding: 14, border: '1px solid #E7CFCD', borderRadius: 12, background: '#FFF', color: '#B3261E', fontSize: 14, fontWeight: 600, cursor: 'pointer' }}>Cancel appointment</button>
              )}
            </div>
          )}
          {a.status === 'CANCELLED' && (
            <div style={{ background: '#F2F6F8', border: '1px solid #E3E9ED', borderRadius: 12, padding: 15, fontSize: 12.5, lineHeight: 1.55, color: '#4A5A64' }}>
              This appointment is cancelled and the slot has been released to the waitlist. You can book a new time whenever suits you.
              <button className="p-primary" onClick={() => navigate('/')} style={{ width: '100%', marginTop: 12, padding: 13, border: 'none', borderRadius: 12, background: '#0E7C7B', color: '#FFF', fontSize: 13.5, fontWeight: 600, cursor: 'pointer' }}>Book a new appointment</button>
            </div>
          )}
        </div>
      )}
    </PhoneShell>
  );
}
