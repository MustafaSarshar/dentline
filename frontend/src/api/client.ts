import type {
  ApiProblem,
  Appointment,
  Availability,
  ClinicConfig,
  DaySchedule,
  FieldError,
  MetricsSummary,
  Notification,
  OfferAccepted,
  Patient,
  Practitioner,
  PreferredWindow,
  RecallRow,
  TreatmentType,
  WaitlistEntry,
  WeekSchedule,
} from './types';

const BOOKING_API = (import.meta.env.VITE_BOOKING_API as string | undefined) ?? 'http://localhost:8081';
const NOTIFICATION_API = (import.meta.env.VITE_NOTIFICATION_API as string | undefined) ?? 'http://localhost:8082';

/** An RFC 7807 problem from either service. `code` is set on 409s, `errors` on 400 validation failures. */
export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly errors: FieldError[];

  constructor(problem: ApiProblem) {
    super(problem.detail ?? problem.title);
    this.name = 'ApiError';
    this.status = problem.status;
    this.code = problem.code;
    this.errors = problem.errors ?? [];
  }
}

async function request<T>(base: string, path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(base + path, {
    ...init,
    headers: { Accept: 'application/json', ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...(init.headers ?? {}) },
  });
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  const body = text ? JSON.parse(text) : undefined;
  if (!response.ok) throw new ApiError(body ?? { title: response.statusText, status: response.status });
  return body as T;
}

const get = <T>(path: string) => request<T>(BOOKING_API, path);
const post = <T>(path: string, body?: unknown) =>
  request<T>(BOOKING_API, path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) });
const del = (path: string) => request<void>(BOOKING_API, path, { method: 'DELETE' });

export interface BookRequest {
  treatmentTypeId: string;
  practitionerId: string;
  startTime: string;
  patient: Patient;
}

export interface JoinWaitlistRequest {
  treatmentTypeId: string;
  practitionerId?: string | null;
  preferredWindows: PreferredWindow[];
  patient: Patient;
}

/** The one typed client for both services. */
export const api = {
  config: () => get<ClinicConfig>('/api/config'),
  treatmentTypes: () => get<TreatmentType[]>('/api/treatment-types'),
  practitioners: () => get<Practitioner[]>('/api/practitioners'),
  availability: (treatmentTypeId: string, from: string, to: string, practitionerId?: string | null) =>
    get<Availability>(
      `/api/availability?treatmentTypeId=${treatmentTypeId}&from=${from}&to=${to}` + (practitionerId ? `&practitionerId=${practitionerId}` : ''),
    ),

  book: (body: BookRequest) => post<Appointment>('/api/appointments', body),
  appointment: (id: string) => get<Appointment>(`/api/appointments/${id}`),
  cancel: (id: string, by: 'PATIENT' | 'STAFF') => post<Appointment>(`/api/appointments/${id}/cancel`, { by }),
  reschedule: (id: string, startTime: string, practitionerId?: string) =>
    post<Appointment>(`/api/appointments/${id}/reschedule`, { startTime, practitionerId }),
  confirm: (id: string) => post<Appointment>(`/api/appointments/${id}/confirm`),
  complete: (id: string) => post<Appointment>(`/api/appointments/${id}/complete`),
  noShow: (id: string) => post<Appointment>(`/api/appointments/${id}/no-show`),

  joinWaitlist: (body: JoinWaitlistRequest) => post<WaitlistEntry>('/api/waitlist', body),
  waitlistEntry: (id: string) => get<WaitlistEntry>(`/api/waitlist/${id}`),
  leaveWaitlist: (id: string) => del(`/api/waitlist/${id}`),
  acceptOffer: (id: string) => post<OfferAccepted>(`/api/waitlist/${id}/offer/accept`),
  declineOffer: (id: string) => post<WaitlistEntry>(`/api/waitlist/${id}/offer/decline`),

  daySchedule: (date: string) => get<DaySchedule>(`/api/staff/schedule?date=${date}`),
  weekSchedule: (from: string, to: string) => get<WeekSchedule>(`/api/staff/schedule?from=${from}&to=${to}`),
  staffWaitlist: () => get<WaitlistEntry[]>('/api/staff/waitlist'),
  sendOffer: (id: string) => post<WaitlistEntry>(`/api/staff/waitlist/${id}/offer`),
  withdrawOffer: (id: string) => post<WaitlistEntry>(`/api/staff/waitlist/${id}/offer/withdraw`),
  recalls: () => get<RecallRow[]>('/api/staff/recalls'),
  sendRecall: (email: string) => post<RecallRow>('/api/staff/recalls/send', { email }),
  metrics: () => get<MetricsSummary>('/api/staff/metrics/summary'),

  notifications: (appointmentId: string) => request<Notification[]>(NOTIFICATION_API, `/api/notifications?appointmentId=${appointmentId}`),
};
