import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { useConfig, useRecalls } from '../api/hooks';
import { useToast } from '../components/Toast';
import { dateTimeLabel, dateWithYear, daysBetween, shortDate, todayIn } from '../lib/format';
import { PanelMessage, panel } from './blocks';

const COLUMNS = 'minmax(0,1.4fr) minmax(0,1fr) minmax(0,1fr) minmax(0,1.2fr) 130px';

/** Six-month check-up recalls: due dates are computed server-side from the last completed check-up. */
export function RecallsView() {
  const say = useToast();
  const queryClient = useQueryClient();
  const config = useConfig();
  const recalls = useRecalls();
  const today = todayIn(config.data?.timezone ?? 'Europe/Oslo');

  const send = useMutation({
    mutationFn: ({ email }: { email: string; resend: boolean }) => api.sendRecall(email),
    onSuccess: (row, { resend }) => {
      queryClient.invalidateQueries({ queryKey: ['recalls'] });
      say(`Recall ${resend ? 'resent' : 'sent'} to ${row.patient.name}`);
    },
    onError: (error) => say(error.message),
  });

  if (recalls.error) return <PanelMessage>Could not load recalls: {recalls.error.message}</PanelMessage>;
  if (!recalls.data) return <div style={{ ...panel, height: 360, background: '#E3E9ED', animation: 'dl-shimmer 1.3s infinite' }} aria-busy="true" />;

  return (
    <div style={panel}>
      <div style={{ display: 'grid', gridTemplateColumns: COLUMNS, gap: 12, padding: '11px 18px', borderBottom: '1px solid #E3E9ED', fontSize: 10.5, fontWeight: 600, letterSpacing: '.07em', textTransform: 'uppercase', color: '#8A98A1' }}>
        <span>Patient</span><span>Last visit</span><span>Due</span><span>Recall sent</span><span />
      </div>
      {recalls.data.length === 0 && <div style={{ padding: '28px 18px', textAlign: 'center', fontSize: 12.5, color: '#8A98A1' }}>Nobody is due for a check-up in the next month.</div>}
      {recalls.data.map((r) => {
        const overdueDays = daysBetween(r.dueDate, today);
        const overdue = overdueDays > 0;
        const done = !!r.recallSentAt;
        return (
          <div key={r.patient.email} style={{ display: 'grid', gridTemplateColumns: COLUMNS, gap: 12, padding: '14px 18px', borderBottom: '1px solid #F0F4F6', alignItems: 'center' }}>
            <span style={{ minWidth: 0 }}>
              <span style={{ display: 'block', fontSize: 13.5, fontWeight: 600 }}>{r.patient.name}</span>
              <span style={{ display: 'block', fontSize: 11.5, color: '#65757F' }}>{r.practitionerName}</span>
            </span>
            <span style={{ fontSize: 12.5, color: '#4A5A64' }}>{dateWithYear(r.lastVisitDate)}</span>
            <span style={{ fontSize: 12.5, fontWeight: 600, color: overdue ? '#B3261E' : '#1E2A32' }}>
              {overdue ? `Overdue ${overdueDays} day${overdueDays === 1 ? '' : 's'}` : `Due ${shortDate(r.dueDate).slice(4)}`}
            </span>
            <span style={{ fontSize: 12.5, color: '#4A5A64', fontVariantNumeric: 'tabular-nums' }}>{r.recallSentAt ? dateTimeLabel(r.recallSentAt) : '—'}</span>
            <button className="s-btn" onClick={() => send.mutate({ email: r.patient.email, resend: done })} disabled={send.isPending} style={{ padding: '8px 11px', border: '1px solid #0E7C7B', borderRadius: 9, background: '#FFF', color: '#0E7C7B', fontSize: 12, fontWeight: 600, cursor: 'pointer', justifySelf: 'start' }}>
              {done ? 'Resend' : 'Send recall'}
            </button>
          </div>
        );
      })}
    </div>
  );
}
