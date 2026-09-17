import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router-dom';
import { ApiError, api } from '../api/client';
import { useAvailability, useConfig, usePractitioners, useTreatmentTypes } from '../api/hooks';
import type { Appointment, Patient, Practitioner, PreferredWindow, Slot, TreatmentType } from '../api/types';
import { useToast } from '../components/Toast';
import { addDays, price, todayIn } from '../lib/format';
import { PhoneShell } from './PhoneShell';
import type { RescheduleIntent } from './PatientApp';
import { TreatmentScreen } from './screens/TreatmentScreen';
import { PractitionerScreen } from './screens/PractitionerScreen';
import { SlotScreen } from './screens/SlotScreen';
import { DetailsScreen, type FieldErrors } from './screens/DetailsScreen';
import { DoneScreen } from './screens/DoneScreen';
import { WaitlistScreen } from './screens/WaitlistScreen';
import { ErrorState, Skeleton } from './ui';

type Screen = 'treatment' | 'practitioner' | 'slots' | 'details' | 'done' | 'waitlist';

const STEP: Partial<Record<Screen, number>> = { treatment: 0, practitioner: 1, slots: 1, details: 2 };

/** Steps 1–3 of booking plus the waitlist join. State is a step machine with a history stack. */
export function BookingFlow() {
  const navigate = useNavigate();
  const location = useLocation();
  const say = useToast();
  const queryClient = useQueryClient();
  const reschedule = (location.state as { reschedule?: RescheduleIntent } | null)?.reschedule;

  const config = useConfig();
  const treatmentTypes = useTreatmentTypes();
  const practitioners = usePractitioners();

  const [screen, setScreen] = useState<Screen>(reschedule ? 'slots' : 'treatment');
  const [history, setHistory] = useState<Screen[]>([]);
  const [treatment, setTreatment] = useState<TreatmentType | null>(null);
  const [practitioner, setPractitioner] = useState<Practitioner | null>(null); // null = first available
  const [slot, setSlot] = useState<Slot | null>(null);
  const [patient, setPatient] = useState<Patient>({ name: '', phone: '', email: '' });
  const [errors, setErrors] = useState<FieldErrors>({});
  const [windows, setWindows] = useState<PreferredWindow[]>(['WEEKDAY_MORNINGS']);
  const [booked, setBooked] = useState<Appointment | null>(null);

  // Rescheduling: the treatment and practitioner are fixed by the existing appointment.
  useEffect(() => {
    if (!reschedule || !treatmentTypes.data || !practitioners.data) return;
    setTreatment(treatmentTypes.data.find((t) => t.id === reschedule.treatmentTypeId) ?? null);
    setPractitioner(practitioners.data.find((p) => p.id === reschedule.practitionerId) ?? null);
  }, [reschedule, treatmentTypes.data, practitioners.data]);

  const timezone = config.data?.timezone ?? 'Europe/Oslo';
  const today = useMemo(() => todayIn(timezone), [timezone]);
  const availability = useAvailability(screen === 'slots' || screen === 'details' ? treatment?.id : undefined, today, addDays(today, 6), practitioner?.id);

  const go = (next: Screen) => {
    setHistory((h) => [...h, screen]);
    setScreen(next);
  };
  const back = () => {
    if (history.length === 0) {
      if (reschedule) navigate(`/appointments/${reschedule.appointmentId}`);
      return;
    }
    setScreen(history[history.length - 1]);
    setHistory((h) => h.slice(0, -1));
  };
  const restart = () => {
    setScreen('treatment');
    setHistory([]);
    setTreatment(null);
    setPractitioner(null);
    setSlot(null);
    setBooked(null);
  };

  const book = useMutation({
    mutationFn: (body: Patient) =>
      api.book({ treatmentTypeId: treatment!.id, practitionerId: slot!.practitionerId, startTime: slot!.startTime, patient: body }),
    onSuccess: (appointment) => {
      setBooked(appointment);
      setScreen('done');
      setHistory([]);
      queryClient.invalidateQueries({ queryKey: ['availability'] });
      say('Appointment requested — confirmed');
    },
    onError: (error) => handleBookingError(error),
  });

  const move = useMutation({
    mutationFn: (s: Slot) => api.reschedule(reschedule!.appointmentId, s.startTime, s.practitionerId),
    onSuccess: (appointment) => {
      queryClient.invalidateQueries({ queryKey: ['appointment', appointment.id] });
      queryClient.invalidateQueries({ queryKey: ['availability'] });
      say('Moved — see you at the new time');
      navigate(`/appointments/${appointment.id}`, { state: { fromFlow: true } });
    },
    onError: (error) => handleBookingError(error),
  });

  const join = useMutation({
    mutationFn: (body: Patient) =>
      api.joinWaitlist({ treatmentTypeId: treatment!.id, practitionerId: practitioner?.id ?? null, preferredWindows: windows, patient: body }),
    onSuccess: (entry) => {
      say(`You are number ${entry.position ?? '–'} in the queue`);
      navigate(`/waitlist/${entry.id}`, { state: { fromFlow: true } });
    },
    onError: (error) => handleBookingError(error),
  });

  function handleBookingError(error: unknown) {
    if (error instanceof ApiError && error.status === 400 && error.errors.length) {
      const next: FieldErrors = {};
      for (const e of error.errors) next[e.field.replace(/^patient\./, '') as keyof FieldErrors] = e.message;
      setErrors(next);
      say('Check the highlighted fields');
      return;
    }
    if (error instanceof ApiError && error.status === 409) {
      queryClient.invalidateQueries({ queryKey: ['availability'] });
      say(error.code === 'SLOT_TAKEN' ? 'That time was just taken — pick another' : error.message);
      if (screen === 'details') back();
      return;
    }
    say(error instanceof Error ? error.message : 'Something went wrong');
  }

  const t = treatment ?? treatmentTypes.data?.[0];
  const pracLabel = practitioner ? practitioner.name : 'first available';
  const headers: Record<Screen, [string, string]> = {
    treatment: ['What do you need?', `${config.data?.clinic.name ?? 'Dentline Majorstuen'} · Oslo`],
    practitioner: [t?.name ?? '', t ? `${t.durationMinutes} min · ${price(t.priceNok)} kr` : ''],
    slots: [reschedule ? 'Pick a new time' : 'Pick a time', t ? `${t.name} with ${pracLabel}` : ''],
    details: ['Your details', 'Step 3 of 3'],
    done: ['Confirmed', `Booking ${booked?.reference ?? ''}`],
    waitlist: ['Join the waitlist', t?.name ?? ''],
  };
  const [title, sub] = headers[screen];

  const loading = config.isPending || treatmentTypes.isPending || practitioners.isPending;
  const failed = config.error ?? treatmentTypes.error ?? practitioners.error;

  return (
    <PhoneShell title={title} sub={sub} canBack={history.length > 0 || !!reschedule} onBack={back} step={STEP[screen]}>
      {failed ? (
        <ErrorState message={failed.message} onRetry={() => { config.refetch(); treatmentTypes.refetch(); practitioners.refetch(); }} />
      ) : loading ? (
        <Skeleton rows={5} />
      ) : screen === 'treatment' ? (
        <TreatmentScreen treatments={treatmentTypes.data!} onPick={(x) => { setTreatment(x); go('practitioner'); }} />
      ) : screen === 'practitioner' ? (
        <PractitionerScreen practitioners={practitioners.data!} onPick={(p) => { setPractitioner(p); go('slots'); }} />
      ) : screen === 'slots' && t ? (
        <SlotScreen
          practitionerName={practitioner?.name ?? null}
          today={today}
          availability={availability}
          onPick={(s) => {
            setSlot(s);
            if (reschedule) move.mutate(s);
            else go('details');
          }}
          onJoinWaitlist={() => go('waitlist')}
        />
      ) : screen === 'details' && t && slot ? (
        <DetailsScreen
          treatment={t}
          practitionerName={practitioners.data!.find((p) => p.id === slot.practitionerId)?.name ?? pracLabel}
          slot={slot}
          patient={patient}
          errors={errors}
          submitting={book.isPending}
          onChange={(next) => { setPatient(next); setErrors({}); }}
          onSubmit={(validationErrors) => {
            if (Object.keys(validationErrors).length) {
              setErrors(validationErrors);
              say('Check the highlighted fields');
              return;
            }
            book.mutate(patient);
          }}
        />
      ) : screen === 'done' && booked ? (
        <DoneScreen appointment={booked} onViewAppointment={() => navigate(`/appointments/${booked.id}`, { state: { fromFlow: true } })} />
      ) : screen === 'waitlist' && t ? (
        <WaitlistScreen
          treatmentName={t.name}
          practitionerLabel={pracLabel}
          windows={windows}
          onToggle={(w) => setWindows((ws) => (ws.includes(w) ? ws.filter((x) => x !== w) : [...ws, w]))}
          patient={patient}
          errors={errors}
          onChange={(next) => { setPatient(next); setErrors({}); }}
          submitting={join.isPending}
          onJoin={(validationErrors) => {
            if (Object.keys(validationErrors).length) {
              setErrors(validationErrors);
              say('Check the highlighted fields');
              return;
            }
            join.mutate(patient);
          }}
        />
      ) : (
        <ErrorState message="Let's start over." onRetry={restart} />
      )}
    </PhoneShell>
  );
}
