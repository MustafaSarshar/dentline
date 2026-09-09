import { useQuery } from '@tanstack/react-query';
import { api } from './client';

// Query keys are plain arrays so mutations can invalidate by prefix, e.g. ['schedule'].

export const useConfig = () => useQuery({ queryKey: ['config'], queryFn: api.config, staleTime: Infinity });
export const useTreatmentTypes = () => useQuery({ queryKey: ['treatment-types'], queryFn: api.treatmentTypes, staleTime: Infinity });
export const usePractitioners = () => useQuery({ queryKey: ['practitioners'], queryFn: api.practitioners, staleTime: Infinity });

export const useAvailability = (treatmentTypeId: string | undefined, from: string, to: string, practitionerId?: string | null) =>
  useQuery({
    queryKey: ['availability', treatmentTypeId, from, to, practitionerId ?? 'any'],
    queryFn: () => api.availability(treatmentTypeId!, from, to, practitionerId),
    enabled: !!treatmentTypeId,
    staleTime: 15_000,
  });

export const useAppointment = (id: string | undefined) =>
  useQuery({ queryKey: ['appointment', id], queryFn: () => api.appointment(id!), enabled: !!id });

/** Polled while the patient sits on the queue screen: the offer arrives through here. */
export const useWaitlistEntry = (id: string | undefined, pollMs = 5_000) =>
  useQuery({ queryKey: ['waitlist', id], queryFn: () => api.waitlistEntry(id!), enabled: !!id, refetchInterval: pollMs });

export const useDaySchedule = (date: string) =>
  useQuery({ queryKey: ['schedule', 'day', date], queryFn: () => api.daySchedule(date), refetchInterval: 30_000 });

export const useWeekSchedule = (from: string, to: string) =>
  useQuery({ queryKey: ['schedule', 'week', from, to], queryFn: () => api.weekSchedule(from, to), refetchInterval: 60_000 });

export const useStaffWaitlist = () =>
  useQuery({ queryKey: ['staff-waitlist'], queryFn: api.staffWaitlist, refetchInterval: 15_000 });

export const useRecalls = () => useQuery({ queryKey: ['recalls'], queryFn: api.recalls });

export const useMetrics = () => useQuery({ queryKey: ['metrics'], queryFn: api.metrics, refetchInterval: 60_000 });

export const useNotifications = (appointmentId: string | undefined) =>
  useQuery({ queryKey: ['notifications', appointmentId], queryFn: () => api.notifications(appointmentId!), enabled: !!appointmentId });
