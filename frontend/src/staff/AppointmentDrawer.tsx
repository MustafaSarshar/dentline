import { useState, type Dispatch, type SetStateAction } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError, api } from '../api/client';
import { useAppointment, useNotifications } from '../api/hooks';
import type { Appointment, AppointmentAction, AppointmentStatus, Notification } from '../api/types';
import { useToast } from '../components/Toast';
import { historyStamp, price, timeOf } from '../lib/format';
import { styleOf } from '../lib/status';
import type { StatusOverrides } from './StaffApp';

interface Props {
  appointmentId: string;
  overrides: StatusOverrides;
  setOverrides: Dispatch<SetStateAction<StatusOverrides>>;
  onClose: () => void;
}

interface ActionSpec {
  action: AppointmentAction;
  label: string;
  target: AppointmentStatus;
  tone: 'primary' | 'plain' | 'danger';
  outcome: string;
  confirm?: (a: Appointment) => [string, string, string];
}

const ACTIONS: ActionSpec[] = [
  { action: 'confirm', label: 'Confirm', target: 'CONFIRMED', tone: 'primary', outcome: 'confirmed' },
  { action: 'complete', label: 'Complete', target: 'COMPLETED', tone: 'plain', outcome: 'completed' },
  {
    action: 'no-show', label: 'Mark no-show', target: 'NO_SHOW', tone: 'danger', outcome: 'marked as no-show',
    confirm: (a) => ['Mark as no-show?', `${a.patient.name} will be flagged as a no-show and counted in the no-show rate. The slot stays used for today.`, 'Mark no-show'],
  },
  {
    action: 'cancel', label: 'Cancel', target: 'CANCELLED', tone: 'danger', outcome: 'cancelled, slot released to waitlist',
    confirm: (a) => ['Cancel this appointment?', `The ${timeOf(a.startTime)} slot will be released and offered to the waitlist immediately. ${a.patient.name} gets a cancellation notice.`, 'Cancel appointment'],
  },
];

const NOTIFICATION_LINE: Partial<Record<Notification['type'], string>> = {
  BOOKING_RECEIVED: 'Booking request received — email sent',
  BOOKING_CONFIRMED: 'Confirmation sent by email',
  BOOKING_RESCHEDULED: 'Reschedule notice sent by email',
  BOOKING_CANCELLED: 'Cancellation notice sent by email',
  REMINDER: 'Reminder sent by SMS and email',
};

/** Detail drawer with the four status actions (optimistic, rolled back on 409) and a merged history. */
export function AppointmentDrawer({ appointmentId, overrides, setOverrides, onClose }: Props) {
  const say = useToast();
  const queryClient = useQueryClient();
  const query = useAppointment(appointmentId);
  const notifications = useNotifications(appointmentId);
  const [confirm, setConfirm] = useState<{ title: string; body: string; cta: string; run: () => void } | null>(null);

  const transition = useMutation({
    mutationFn: ({ a, spec }: { a: Appointment; spec: ActionSpec }) => {
      switch (spec.action) {
        case 'confirm': return api.confirm(a.id);
        case 'complete': return api.complete(a.id);
        case 'no-show': return api.noShow(a.id);
        case 'cancel': return api.cancel(a.id, 'STAFF');
        default: throw new Error('unsupported');
      }
    },
    onMutate: ({ a, spec }) => setOverrides((o) => ({ ...o, [a.id]: spec.target })),
    onSuccess: (updated, { a, spec }) => {
      setOverrides((o) => { const { [a.id]: _dropped, ...rest } = o; return rest; });
      queryClient.setQueryData(['appointment', a.id], updated);
      queryClient.invalidateQueries({ queryKey: ['appointment', a.id] });
      queryClient.invalidateQueries({ queryKey: ['schedule'] });
      queryClient.invalidateQueries({ queryKey: ['metrics'] });
      queryClient.invalidateQueries({ queryKey: ['notifications', a.id] });
      if (spec.action === 'cancel') queryClient.invalidateQueries({ queryKey: ['staff-waitlist'] });
      say(`${a.patient.name} — ${spec.outcome}`);
    },
    onError: (error, { a }) => {
      setOverrides((o) => { const { [a.id]: _dropped, ...rest } = o; return rest; });
      queryClient.invalidateQueries({ queryKey: ['appointment', a.id] });
      queryClient.invalidateQueries({ queryKey: ['schedule'] });
      say(error instanceof ApiError && error.status === 409 ? `${a.patient.name} — ${error.message.toLowerCase()}` : error.message);
    },
  });

  const a = query.data;
  const status = a ? overrides[a.id] ?? a.status : undefined;
  const st = status ? styleOf(status) : null;

  const timeline = a
    ? [
        ...(a.history ?? []).map((h) => ({ at: h.at, what: h.description })),
        ...(notifications.data ?? []).flatMap((n) => (NOTIFICATION_LINE[n.type] ? [{ at: n.createdAt, what: NOTIFICATION_LINE[n.type]! }] : [])),
      ].sort((x, y) => Date.parse(x.at) - Date.parse(y.at))
    : [];

  return (
    <>
      <div style={{ position: 'fixed', inset: 0, background: 'rgba(30,42,50,.32)', display: 'flex', justifyContent: 'flex-end', animation: 'dl-fade .18s ease', zIndex: 20 }}>
        <button onClick={onClose} aria-label="Close details" style={{ flex: 1, border: 'none', background: 'transparent', cursor: 'default' }} />
        <div role="dialog" aria-modal="true" aria-label="Appointment details" style={{ width: 392, maxWidth: '92vw', background: '#FFF', height: '100%', boxSizing: 'border-box', padding: 24, overflowY: 'auto', animation: 'dl-in .24s ease', boxShadow: '-14px 0 40px rgba(30,42,50,.18)', display: 'flex', flexDirection: 'column', gap: 18 }}>
          {query.error ? (
            <div style={{ fontSize: 13, color: '#4A5A64' }}>Could not load this appointment: {query.error.message}</div>
          ) : !a || !st || !status ? (
            <div style={{ height: 200, borderRadius: 12, background: '#E3E9ED', animation: 'dl-shimmer 1.3s infinite' }} aria-busy="true" />
          ) : (
            <>
              <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12 }}>
                <div>
                  <div style={{ fontSize: 11, fontWeight: 600, letterSpacing: '.08em', textTransform: 'uppercase', color: '#8A98A1' }}>Appointment {a.reference}</div>
                  <div style={{ fontSize: 19, fontWeight: 700, letterSpacing: '-.02em', marginTop: 5 }}>{a.patient.name}</div>
                  <div style={{ fontSize: 13, color: '#65757F', marginTop: 3 }}>{a.patient.phone} · {a.patient.email}</div>
                </div>
                <button className="s-close" onClick={onClose} aria-label="Close" style={{ width: 32, height: 32, flex: 'none', borderRadius: 9, border: '1px solid #D3DBE0', background: '#FFF', color: '#1E2A32', fontSize: 15, cursor: 'pointer' }}>×</button>
              </div>

              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7, padding: '6px 11px', borderRadius: 999, background: st.bg, color: st.fg, border: `1px solid ${st.border}`, fontSize: 12, fontWeight: 600, alignSelf: 'flex-start' }}>
                <span style={{ width: 6, height: 6, borderRadius: '50%', background: st.fg }} />
                {st.label}
              </span>

              <div style={{ background: '#FAFBFC', border: '1px solid #E3E9ED', borderRadius: 12, padding: 15 }}>
                {([
                  ['Treatment', a.treatmentType.name],
                  ['Practitioner', a.practitioner.name],
                  ['Time', `${timeOf(a.startTime)}–${timeOf(a.endTime)}`],
                  ['Duration', `${a.treatmentType.durationMinutes} min`],
                  ['Price', `${price(a.treatmentType.priceNok)} kr`],
                ] as Array<[string, string]>).map(([k, v]) => (
                  <div key={k} style={{ display: 'flex', justifyContent: 'space-between', gap: 14, padding: '6px 0', fontSize: 13 }}>
                    <span style={{ color: '#65757F' }}>{k}</span><span style={{ fontWeight: 600, textAlign: 'right' }}>{v}</span>
                  </div>
                ))}
              </div>

              <div>
                <div style={{ fontSize: 11, fontWeight: 600, letterSpacing: '.08em', textTransform: 'uppercase', color: '#8A98A1', marginBottom: 9 }}>Status actions</div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                  {ACTIONS.map((spec) => {
                    const allowed = a.allowedActions.includes(spec.action) && status === a.status && !transition.isPending;
                    const bg = spec.tone === 'primary' ? '#0E7C7B' : '#FFF';
                    const fg = spec.tone === 'primary' ? '#FFF' : spec.tone === 'danger' ? '#B3261E' : '#1E2A32';
                    const border = spec.tone === 'primary' ? '#0E7C7B' : spec.tone === 'danger' ? '#E7CFCD' : '#D3DBE0';
                    const run = () => {
                      if (!allowed) return;
                      if (spec.confirm) {
                        const [title, body, cta] = spec.confirm(a);
                        setConfirm({ title, body, cta, run: () => { setConfirm(null); transition.mutate({ a, spec }); } });
                      } else {
                        transition.mutate({ a, spec });
                      }
                    };
                    return (
                      <button key={spec.action} className="s-action" onClick={run} disabled={!allowed} style={{ padding: 12, border: `1px solid ${border}`, borderRadius: 10, background: bg, color: fg, fontSize: 13, fontWeight: 600, cursor: allowed ? 'pointer' : 'not-allowed', opacity: allowed ? 1 : 0.45 }}>
                        {spec.label}
                      </button>
                    );
                  })}
                </div>
              </div>

              <div style={{ marginTop: 'auto', paddingTop: 14, borderTop: '1px solid #E3E9ED' }}>
                <div style={{ fontSize: 11, fontWeight: 600, letterSpacing: '.08em', textTransform: 'uppercase', color: '#8A98A1', marginBottom: 9 }}>History</div>
                {timeline.map((h, i) => (
                  <div key={i} style={{ display: 'flex', gap: 10, padding: '5px 0', fontSize: 12, color: '#4A5A64' }}>
                    <span style={{ color: '#8A98A1', fontVariantNumeric: 'tabular-nums', flex: 'none' }}>{historyStamp(h.at)}</span>
                    <span>{h.what}</span>
                  </div>
                ))}
                {notifications.error && <div style={{ fontSize: 11.5, color: '#8A98A1', paddingTop: 5 }}>Notification history unavailable.</div>}
              </div>
            </>
          )}
        </div>
      </div>

      {confirm && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(30,42,50,.42)', display: 'grid', placeItems: 'center', padding: 24, animation: 'dl-fade .18s ease', zIndex: 30 }}>
          <div role="dialog" aria-modal="true" aria-label="Confirm action" style={{ width: 400, maxWidth: '92vw', background: '#FFF', borderRadius: 14, padding: 22, animation: 'dl-up-s .22s ease', boxShadow: '0 20px 50px rgba(30,42,50,.24)' }}>
            <div style={{ fontSize: 17, fontWeight: 700, letterSpacing: '-.01em' }}>{confirm.title}</div>
            <p style={{ margin: '9px 0 18px', fontSize: 13, lineHeight: 1.55, color: '#4A5A64' }}>{confirm.body}</p>
            <div style={{ display: 'flex', gap: 9, justifyContent: 'flex-end' }}>
              <button className="s-secondary" onClick={() => setConfirm(null)} style={{ padding: '11px 16px', border: '1px solid #D3DBE0', borderRadius: 10, background: '#FFF', color: '#1E2A32', fontSize: 13, fontWeight: 600, cursor: 'pointer' }}>Keep as is</button>
              <button className="s-cta" onClick={confirm.run} style={{ padding: '11px 16px', border: 'none', borderRadius: 10, background: '#B3261E', color: '#FFF', fontSize: 13, fontWeight: 600, cursor: 'pointer' }}>{confirm.cta}</button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
