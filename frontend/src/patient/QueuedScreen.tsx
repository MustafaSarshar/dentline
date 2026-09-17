import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { ApiError, api } from '../api/client';
import { useConfig, useWaitlistEntry } from '../api/hooks';
import type { SlotOffer } from '../api/types';
import { useToast } from '../components/Toast';
import { useDialog } from '../components/useDialog';
import { WINDOW_LABEL, mmss, secondsUntil, whenLabel } from '../lib/format';
import { PhoneShell } from './PhoneShell';
import { ErrorState, SecondaryButton, Skeleton, card, label } from './ui';

/** "You are on the waitlist": position, windows, and the offer sheet when one arrives (polled every 5 s). */
export function QueuedScreen() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const say = useToast();
  const queryClient = useQueryClient();
  const config = useConfig();
  const entry = useWaitlistEntry(id);
  const [dismissedOfferId, setDismissedOfferId] = useState<string | null>(null);

  const holdMinutes = config.data?.offerHoldMinutes ?? 15;
  const e = entry.data;
  const offer = e?.status === 'OFFERED' && e.offer?.status === 'PENDING' && e.offer.id !== dismissedOfferId ? e.offer : null;

  // An accepted entry (e.g. after a reload) belongs on the appointment screen.
  useEffect(() => {
    if (e?.status === 'ACCEPTED' && e.appointmentId) navigate(`/appointments/${e.appointmentId}`, { replace: true });
  }, [e, navigate]);

  const accept = useMutation({
    mutationFn: () => api.acceptOffer(id!),
    onSuccess: ({ appointment }) => {
      queryClient.invalidateQueries({ queryKey: ['waitlist', id] });
      say('Slot accepted — you are booked in');
      navigate(`/appointments/${appointment.id}`);
    },
    onError: (error) => {
      queryClient.invalidateQueries({ queryKey: ['waitlist', id] });
      say(error instanceof ApiError && error.code === 'OFFER_NOT_ACTIVE' ? 'That offer has expired' : error.message);
    },
  });
  const decline = useMutation({
    mutationFn: () => api.declineOffer(id!),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['waitlist', id] });
      say('Offer declined — you keep your place');
    },
    onError: (error) => say(error.message),
  });
  const leave = useMutation({
    mutationFn: () => api.leaveWaitlist(id!),
    onSuccess: () => {
      say('Removed from the waitlist');
      navigate('/');
    },
    onError: (error) => say(error.message),
  });

  const fromFlow = !!(location.state as { fromFlow?: boolean } | null)?.fromFlow;

  return (
    <PhoneShell
      title="You are on the waitlist"
      sub={e?.treatmentType.name ?? ''}
      canBack={fromFlow}
      onBack={() => navigate('/')}
      overlay={offer && (
        <OfferSheet
          offer={offer}
          treatmentName={e!.treatmentType.name}
          holdMinutes={holdMinutes}
          busy={accept.isPending || decline.isPending}
          onAccept={() => accept.mutate()}
          onDecline={() => decline.mutate()}
          onExpired={() => { setDismissedOfferId(offer.id); queryClient.invalidateQueries({ queryKey: ['waitlist', id] }); }}
        />
      )}
    >
      {entry.error ? (
        <ErrorState message={entry.error instanceof ApiError && entry.error.status === 404 ? 'We could not find this waitlist entry.' : entry.error.message} onRetry={() => entry.refetch()} />
      ) : !e ? (
        <Skeleton rows={2} />
      ) : e.status === 'REMOVED' ? (
        <div style={{ ...card, background: '#F2F6F8', fontSize: 12.5, lineHeight: 1.55, color: '#4A5A64', padding: 15 }}>
          You have left the waitlist. You can book a new time whenever suits you.
          <button className="p-primary" onClick={() => navigate('/')} style={{ width: '100%', marginTop: 12, padding: 13, border: 'none', borderRadius: 12, background: '#0E7C7B', color: '#FFF', fontSize: 13.5, fontWeight: 600, cursor: 'pointer' }}>Book a new appointment</button>
        </div>
      ) : (
        <div style={{ animation: 'dl-up .3s ease' }}>
          <div style={{ ...card, padding: '22px 18px', textAlign: 'center' }}>
            <div style={label()}>Your place in the queue</div>
            <div style={{ fontSize: 48, fontWeight: 700, color: '#0E7C7B', letterSpacing: '-.03em', lineHeight: 1.1, margin: '6px 0 2px' }}>{e.position ?? '–'}</div>
            <div style={{ fontSize: 13, color: '#4A5A64' }}>of {e.queueSize} waiting for {e.treatmentType.name}</div>
            <div style={{ height: 1, background: '#E3E9ED', margin: '17px 0' }} />
            <div style={{ fontSize: 12.5, lineHeight: 1.5, color: '#4A5A64', textAlign: 'left' }}>
              Windows: <strong style={{ fontWeight: 600 }}>{e.preferredWindows.map((w) => WINDOW_LABEL[w]).join(', ') || 'none yet'}</strong>
              <br />
              Typical wait at position {e.position ?? '–'} is 4–6 days.
            </div>
          </div>
          <p style={{ margin: '16px 2px 0', fontSize: 12.5, lineHeight: 1.55, color: '#8A98A1' }}>
            We will text and email you when a slot is offered. Offers are held for {holdMinutes} minutes before passing to the next person.
          </p>
          <SecondaryButton style={{ marginTop: 16, padding: 13, fontSize: 13.5 }} onClick={() => leave.mutate()}>Leave waitlist</SecondaryButton>
        </div>
      )}
    </PhoneShell>
  );
}

interface SheetProps {
  offer: SlotOffer;
  treatmentName: string;
  holdMinutes: number;
  busy: boolean;
  onAccept: () => void;
  onDecline: () => void;
  onExpired: () => void;
}

/** The bottom sheet with the live countdown. The clock is display-only and derived from the server's expiresAt. */
function OfferSheet({ offer, treatmentName, holdMinutes, busy, onAccept, onDecline, onExpired }: SheetProps) {
  const [left, setLeft] = useState(() => secondsUntil(offer.expiresAt));
  const total = holdMinutes * 60;
  // No Escape here: the sheet asks for a decision, so it is an alertdialog with no way out but
  // Accept, Decline or the countdown running out. The focus trap still applies.
  const ref = useDialog<HTMLDivElement>();

  useEffect(() => {
    const tick = window.setInterval(() => {
      const remaining = secondsUntil(offer.expiresAt);
      setLeft(remaining);
      if (remaining <= 0) {
        window.clearInterval(tick);
        onExpired();
      }
    }, 1000);
    return () => window.clearInterval(tick);
  }, [offer.expiresAt, onExpired]);

  return (
    <div style={{ position: 'absolute', inset: 0, background: 'rgba(30,42,50,.42)', display: 'flex', alignItems: 'flex-end', padding: 16, animation: 'dl-fade .2s ease' }}>
      <div ref={ref} role="alertdialog" aria-modal="true" aria-label="Slot offer" style={{ width: '100%', background: '#FFF', borderRadius: 18, padding: 20, animation: 'dl-up .28s ease', boxShadow: '0 -8px 30px rgba(30,42,50,.2)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 9, marginBottom: 12 }}>
          <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#0E7C7B', animation: 'dl-pulse 1.8s ease-out infinite' }} />
          <span style={label({ color: '#0E7C7B' })}>A slot just opened</span>
        </div>
        <div style={{ fontSize: 18, fontWeight: 700, letterSpacing: '-.02em' }}>{whenLabel(offer.startTime)}</div>
        <div style={{ fontSize: 13, color: '#4A5A64', marginTop: 4 }}>{treatmentName} with {offer.practitioner.name}</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, margin: '16px 0', padding: '13px 14px', background: '#D6F2F0', borderRadius: 12 }}>
          <span style={{ fontSize: 22, fontWeight: 700, color: '#0E7C7B', fontVariantNumeric: 'tabular-nums', letterSpacing: '-.02em' }}>{mmss(left)}</span>
          <span style={{ fontSize: 12, lineHeight: 1.45, color: '#12545A' }}>This slot is held for you for {holdMinutes} minutes.</span>
        </div>
        <div style={{ height: 4, borderRadius: 999, background: '#E3E9ED', overflow: 'hidden', marginBottom: 16 }}>
          <div style={{ height: '100%', width: `${Math.min(100, Math.round((left / total) * 100))}%`, background: '#0E7C7B', borderRadius: 999, transition: 'width 1s linear' }} />
        </div>
        <div style={{ display: 'flex', gap: 9 }}>
          <button className="p-secondary" onClick={onDecline} disabled={busy} style={{ flex: 1, padding: 14, border: '1px solid #D3DBE0', borderRadius: 12, background: '#FFF', color: '#1E2A32', fontSize: 14, fontWeight: 600, cursor: 'pointer' }}>Decline</button>
          <button className="p-primary" onClick={onAccept} disabled={busy} style={{ flex: 1.4, padding: 14, border: 'none', borderRadius: 12, background: '#0E7C7B', color: '#FFF', fontSize: 14, fontWeight: 600, cursor: 'pointer' }}>Accept slot</button>
        </div>
      </div>
    </div>
  );
}
