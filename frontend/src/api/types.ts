// Wire types — mirror docs/02-api-contract.md. Instants are ISO-8601 strings with the clinic offset.

export type PractitionerTitle = 'DENTIST' | 'HYGIENIST';

export interface TreatmentType {
  id: string;
  code: string;
  name: string;
  durationMinutes: number;
  priceNok: number;
}

export interface WorkingHours {
  dayOfWeek: number; // 1 = Monday
  startTime: string; // "08:00"
  endTime: string;
}

export interface Practitioner {
  id: string;
  name: string;
  title: PractitionerTitle;
  workingHours: WorkingHours[];
}

export interface PractitionerRef {
  id: string;
  name: string;
  title: PractitionerTitle;
}

export interface Patient {
  name: string;
  phone: string;
  email: string;
}

export type AppointmentStatus = 'REQUESTED' | 'CONFIRMED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW';
export type AppointmentAction = 'confirm' | 'complete' | 'no-show' | 'cancel' | 'reschedule';

export interface HistoryEntry {
  at: string;
  type: string;
  description: string;
}

export interface Appointment {
  id: string;
  reference: string;
  status: AppointmentStatus;
  patient: Patient;
  practitioner: PractitionerRef;
  treatmentType: TreatmentType;
  startTime: string;
  endTime: string;
  allowedActions: AppointmentAction[];
  createdAt: string;
  updatedAt: string;
  history?: HistoryEntry[] | null;
}

export interface Slot {
  startTime: string;
  practitionerId: string;
}

export interface DayAvailability {
  date: string; // YYYY-MM-DD
  closed: boolean;
  slots: Slot[];
}

export interface Availability {
  slotStepMinutes: number;
  days: DayAvailability[];
}

export interface ClinicConfig {
  timezone: string;
  slotStepMinutes: number;
  offerHoldMinutes: number;
  clinic: { name: string; addressLine1: string; addressLine2: string };
}

export type PreferredWindow = 'WEEKDAY_MORNINGS' | 'WEEKDAY_AFTERNOONS' | 'AFTER_16' | 'ANY_THIS_WEEK' | 'FRIDAYS_ONLY';
export type WaitlistStatus = 'WAITING' | 'OFFERED' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED' | 'REMOVED';
export type SlotOfferStatus = 'PENDING' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED' | 'WITHDRAWN';

export interface SlotOffer {
  id: string;
  startTime: string;
  endTime: string;
  practitioner: PractitionerRef;
  expiresAt: string;
  status: SlotOfferStatus;
  respondedAt: string | null;
}

export interface WaitlistEntry {
  id: string;
  status: WaitlistStatus;
  patient: Patient;
  treatmentType: TreatmentType;
  practitionerId: string | null;
  preferredWindows: PreferredWindow[];
  position: number | null;
  queueSize: number;
  createdAt: string;
  offer: SlotOffer | null;
  appointmentId: string | null;
}

export interface OfferAccepted {
  entry: WaitlistEntry;
  appointment: Appointment;
}

export interface TimeRange {
  startTime: string;
  endTime: string;
}

export interface DaySchedule {
  date: string;
  clinicOpen: TimeRange;
  practitioners: Array<PractitionerRef & { workingHours: TimeRange[]; appointments: Appointment[] }>;
}

export interface WeekDay {
  date: string;
  appointments: Appointment[];
  bookedCount: number;
  noShowCount: number;
  openSlotCount: number;
}

export interface WeekSchedule {
  days: WeekDay[];
}

export interface RecallRow {
  patient: Patient;
  practitionerName: string;
  lastVisitDate: string;
  dueDate: string;
  recallSentAt: string | null;
}

export interface MetricsSummary {
  bookingsToday: { value: number; delta: number };
  cancellationsThisWeek: { value: number; delta: number; refilledFromWaitlist: number };
  noShowRate: { value: number; delta: number }; // fractions
  waitlistAvgWaitDays: { value: number; delta: number; queued: number };
}

export type NotificationType =
  | 'BOOKING_RECEIVED'
  | 'BOOKING_CONFIRMED'
  | 'BOOKING_RESCHEDULED'
  | 'BOOKING_CANCELLED'
  | 'REMINDER'
  | 'RECALL'
  | 'WAITLIST_OFFER'
  | 'WAITLIST_OFFER_EXPIRED';

export interface Notification {
  id: string;
  type: NotificationType;
  channels: Array<'SMS' | 'EMAIL'>;
  recipient: Patient;
  subject: string;
  body: string;
  appointmentId: string | null;
  waitlistEntryId: string | null;
  createdAt: string;
}

export interface FieldError {
  field: string;
  message: string;
}

export interface ApiProblem {
  type?: string;
  title: string;
  status: number;
  detail?: string;
  code?: string;
  errors?: FieldError[];
}
