import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError, api } from '../api/client';
import { useStaffWaitlist } from '../api/hooks';
import type { WaitlistEntry } from '../api/types';
import { useToast } from '../components/Toast';
import { WINDOW_LABEL, daysSince, mmss, secondsUntil, timeOf } from '../lib/format';
import { PanelMessage, panel } from './blocks';

const COLUMNS = '56px minmax(0,1.4fr) minmax(0,1fr) minmax(0,1.2fr) minmax(0,1.3fr) 120px';

interface Badge {
  text: string;
  bg: string;
  fg: string;
  border: string;
  pulse: boolean;
  action: string;
  actionBorder: string;
  actionColor: string;
}

function badgeFor(w: WaitlistEntry, now: number): Badge {
  const at = w.offer?.respondedAt ? timeOf(w.offer.respondedAt) : '';
  switch (w.status) {
    case 'OFFERED': {
      const left = w.offer ? Math.max(0, Math.round((Date.parse(w.offer.expiresAt) - now) / 1000)) : 0;
      return { text: `Offer sent — expires in ${mmss(left)}`, bg: '#FFF8EC', fg: '#7A5310', border: '#D99A2B', pulse: true, action: 'Withdraw offer', actionBorder: '#D99A2B', actionColor: '#7A5310' };
    }
    case 'DECLINED':
      return { text: `Declined ${at}`, bg: '#FCEDEC', fg: '#8F1E18', border: '#E7CFCD', pulse: false, action: 'Send offer', actionBorder: '#0E7C7B', actionColor: '#0E7C7B' };
    case 'ACCEPTED':
      return { text: `Accepted ${at}`, bg: '#D6F2F0', fg: '#0B5654', border: '#B6E4E1', pulse: false, action: 'Open booking', actionBorder: '#0E7C7B', actionColor: '#0E7C7B' };
    case 'EXPIRED':
      return { text: `Expired ${at}`, bg: '#F2F6F8', fg: '#65757F', border: '#E3E9ED', pulse: false, action: 'Send offer', actionBorder: '#0E7C7B', actionColor: '#0E7C7B' };
    default:
      return { text: 'Waiting', bg: '#F2F6F8', fg: '#65757F', border: '#E3E9ED', pulse: false, action: 'Send offer', actionBorder: '#0E7C7B', actionColor: '#0E7C7B' };
  }
}

const waitingLabel = (iso: string) => {
  const d = daysSince(iso);
  return d === 0 ? 'since today' : d === 1 ? '1 day' : `${d} days`;
};

/** The queue table with live offer countdowns (polled every 15 s, ticking locally every second). */
export function WaitlistView({ onOpenBooking }: { onOpenBooking: (appointmentId: string) => void }) {
  const say = useToast();
  const queryClient = useQueryClient();
  const list = useStaffWaitlist();
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, []);

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['staff-waitlist'] });
    queryClient.invalidateQueries({ queryKey: ['metrics'] });
  };
  const send = useMutation({
    mutationFn: (id: string) => api.sendOffer(id),
    onSuccess: (e) => { refresh(); say(`Offer sent to ${e.patient.name} — held 15 min`); },
    onError: (error) => { refresh(); say(error instanceof ApiError && error.code === 'NO_MATCHING_SLOT' ? 'No free slot matches this patient yet' : error.message); },
  });
  const withdraw = useMutation({
    mutationFn: (id: string) => api.withdrawOffer(id),
    onSuccess: (e) => { refresh(); say(`Offer withdrawn for ${e.patient.name}`); },
    onError: (error) => { refresh(); say(error.message); },
  });

  if (list.error) return <PanelMessage>Could not load the waitlist: {list.error.message}</PanelMessage>;
  if (!list.data) return <div style={{ ...panel, height: 360, background: '#E3E9ED', animation: 'dl-shimmer 1.3s infinite' }} aria-busy="true" />;

  const secondsLeft = (w: WaitlistEntry) => (w.offer ? secondsUntil(w.offer.expiresAt) : 0);

  return (
    <div style={panel}>
      <div style={{ display: 'grid', gridTemplateColumns: COLUMNS, gap: 12, padding: '11px 18px', borderBottom: '1px solid #E3E9ED', fontSize: 10.5, fontWeight: 600, letterSpacing: '.07em', textTransform: 'uppercase', color: '#8A98A1' }}>
        <span>Pos</span><span>Patient</span><span>Treatment</span><span>Time window</span><span>Offer status</span><span />
      </div>
      {list.data.length === 0 && <div style={{ padding: '28px 18px', textAlign: 'center', fontSize: 12.5, color: '#8A98A1' }}>Nobody is waiting right now.</div>}
      {list.data.map((w) => {
        const b = badgeFor(w, now);
        const busy = send.isPending || withdraw.isPending;
        const act = () => {
          if (w.status === 'OFFERED') withdraw.mutate(w.id);
          else if (w.status === 'ACCEPTED') { if (w.appointmentId) onOpenBooking(w.appointmentId); else say(`Opening booking for ${w.patient.name}`); }
          else send.mutate(w.id);
        };
        return (
          <div key={w.id} style={{ display: 'grid', gridTemplateColumns: COLUMNS, gap: 12, padding: '14px 18px', borderBottom: '1px solid #F0F4F6', alignItems: 'center' }}>
            <span style={{ fontSize: 14, fontWeight: 700, color: '#0E7C7B', fontVariantNumeric: 'tabular-nums' }}>{w.position ?? '–'}</span>
            <span style={{ minWidth: 0 }}>
              <span style={{ display: 'block', fontSize: 13.5, fontWeight: 600 }}>{w.patient.name}</span>
              <span style={{ display: 'block', fontSize: 11.5, color: '#65757F' }}>{w.patient.phone} · waiting {waitingLabel(w.createdAt)}</span>
            </span>
            <span style={{ fontSize: 13 }}>{w.treatmentType.name}</span>
            <span style={{ fontSize: 12.5, color: '#4A5A64' }}>{w.preferredWindows.map((x) => WINDOW_LABEL[x]).join(', ')}</span>
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7, padding: '5px 10px', borderRadius: 999, background: b.bg, color: b.fg, border: `1px solid ${b.border}`, fontSize: 11.5, fontWeight: 600, justifySelf: 'start', fontVariantNumeric: 'tabular-nums' }}>
              <span style={{ width: 6, height: 6, borderRadius: '50%', background: b.fg, animation: b.pulse && secondsLeft(w) > 0 ? 'dl-pulse-amber 1.8s ease-out infinite' : 'none' }} />
              {b.text}
            </span>
            <button className="s-btn" onClick={act} disabled={busy} style={{ padding: '8px 11px', border: `1px solid ${b.actionBorder}`, borderRadius: 9, background: '#FFF', color: b.actionColor, fontSize: 12, fontWeight: 600, cursor: 'pointer', justifySelf: 'start' }}>
              {b.action}
            </button>
          </div>
        );
      })}
    </div>
  );
}
