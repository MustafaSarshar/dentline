# API contract (proposal, Step 0)

Derived from [01-frontend-mapping.md](01-frontend-mapping.md). This is the contract the
frontend data layer is written against and the backend implements. Changes after sign-off
go through this file first.

## Conventions

- **Base URLs:** booking-service `http://localhost:8081`, notification-service
  `http://localhost:8082`. All paths below are prefixed `/api`.
- **Auth:** none (out of scope). Endpoints under `/api/staff/**` and the appointment
  transitions other than `cancel` are the ones a future staff role would guard.
- **Ids:** UUID v4. Appointment `reference` is the human id (`DL-4821`).
- **Time:** instants are ISO-8601 with offset in the clinic zone (`2026-09-08T08:00:00+02:00`).
  Dates are `YYYY-MM-DD`. Clock-of-day values (working hours) are `HH:mm`. The clinic zone
  is fixed server-side to `Europe/Oslo` and returned in `GET /api/config`.
- **Enums:** `UPPER_SNAKE`.
- **Money:** `priceNok` integer kroner (DB `NUMERIC(10,2)`).
- **Errors:** RFC 7807 `application/problem+json`:
  ```json
  { "type": "about:blank", "title": "Validation failed", "status": 400,
    "detail": "2 fields are invalid",
    "errors": [ { "field": "patient.phone", "message": "Enter a phone number we can text" } ] }
  ```
  - `400` validation (field list) or malformed query.
  - `404` unknown id.
  - `409` conflict, with `code` ∈ `SLOT_TAKEN`, `OUTSIDE_WORKING_HOURS`, `IN_THE_PAST`,
    `ILLEGAL_TRANSITION`, `OFFER_NOT_ACTIVE`, `OFFER_ALREADY_PENDING`, `NO_MATCHING_SLOT`.
- **CORS:** `http://localhost:5173` (Vite dev) allowed on both services.

## Shared shapes

```ts
type Patient = { name: string; phone: string; email: string }

type TreatmentType = { id: string; code: string; name: string; durationMinutes: number; priceNok: number }
// seed: CU Check-up 30/890 · CL Cleaning 45/1190 · FI Filling 60/1750 · RC Root canal 90/4500 · WH Whitening 60/3200

type WorkingHours = { dayOfWeek: 1|2|3|4|5|6|7; startTime: "HH:mm"; endTime: "HH:mm" }   // 1 = Monday
type Practitioner = { id: string; name: string; title: "DENTIST"|"HYGIENIST"; workingHours: WorkingHours[] }

type AppointmentStatus = "REQUESTED"|"CONFIRMED"|"COMPLETED"|"CANCELLED"|"NO_SHOW"
type AppointmentAction = "confirm"|"complete"|"no-show"|"cancel"|"reschedule"

type Appointment = {
  id: string; reference: string; status: AppointmentStatus;
  patient: Patient;
  practitioner: { id: string; name: string; title: "DENTIST"|"HYGIENIST" };
  treatmentType: TreatmentType;
  startTime: string; endTime: string;            // endTime = startTime + durationMinutes, validated on write
  allowedActions: AppointmentAction[];           // from the state machine; drawer disables the rest
  createdAt: string; updatedAt: string;
  history?: HistoryEntry[];                       // only on GET /appointments/{id}
}
type HistoryEntry = { at: string; type: "BOOKED"|"REMINDER_SCHEDULED"|"CONFIRMED"|"RESCHEDULED"|"CANCELLED"|"COMPLETED"|"NO_SHOW"; description: string }
// e.g. "Requested online by Kjersti", "Reminder scheduled for the day before", "Confirmed by front desk"

type PreferredWindow = "WEEKDAY_MORNINGS"|"WEEKDAY_AFTERNOONS"|"AFTER_16"|"ANY_THIS_WEEK"|"FRIDAYS_ONLY"
type WaitlistStatus = "WAITING"|"OFFERED"|"ACCEPTED"|"DECLINED"|"EXPIRED"|"REMOVED"
// queue-eligible (can receive the next offer, keeps position): WAITING, DECLINED, EXPIRED

type SlotOffer = {
  id: string; startTime: string; endTime: string;
  practitioner: { id: string; name: string };
  expiresAt: string;                              // createdAt + offerHoldMinutes; client countdown derives from this
  status: "PENDING"|"ACCEPTED"|"DECLINED"|"EXPIRED"|"WITHDRAWN";
  respondedAt: string | null;                     // drives "Declined 11:04" / "Accepted 09:52"
}
type WaitlistEntry = {
  id: string; status: WaitlistStatus;
  patient: Patient; treatmentType: TreatmentType; practitionerId: string | null;
  preferredWindows: PreferredWindow[];
  position: number | null;                        // rank by createdAt among OFFERED + queue-eligible entries with the same treatment; null once ACCEPTED/REMOVED
  queueSize: number;                              // same population
  createdAt: string;
  offer: SlotOffer | null;                        // the latest offer (PENDING while OFFERED; last outcome otherwise)
  appointmentId: string | null;                   // set when ACCEPTED ("Open booking")
}
```

## booking-service

### Reference data
| Method | Path | Response |
|---|---|---|
| `GET` | `/api/config` | `{ timezone, slotStepMinutes, offerHoldMinutes, clinic: { name, addressLine1, addressLine2 } }` |
| `GET` | `/api/treatment-types` | `TreatmentType[]` |
| `GET` | `/api/practitioners` | `Practitioner[]` |

### Availability (computed, never stored)
`GET /api/availability?treatmentTypeId=&from=YYYY-MM-DD&to=YYYY-MM-DD[&practitionerId=]`

```json
{ "slotStepMinutes": 15,
  "days": [
    { "date": "2026-09-08", "closed": false,
      "slots": [ { "startTime": "2026-09-08T08:00:00+02:00", "practitionerId": "…" } ] },
    { "date": "2026-09-12", "closed": true, "slots": [] } ] }
```
- `closed` = no working hours that day for the practitioner(s) in scope. `closed: false` with an empty list renders as **Full**.
- Without `practitionerId`: union across practitioners, de-duplicated by `startTime` (first practitioner by name wins), ascending.
- Blocking: `REQUESTED`/`CONFIRMED` appointments and `PENDING` slot offers. Slots starting before `now` are omitted.
- `to - from` ≤ 31 days, else `400`.

### Appointments
| Method | Path | Body | Success | Errors |
|---|---|---|---|---|
| `POST` | `/api/appointments` | `{ treatmentTypeId, practitionerId, startTime, patient }` | `201 Appointment` (status `REQUESTED`) | `400`, `404`, `409 SLOT_TAKEN / OUTSIDE_WORKING_HOURS / IN_THE_PAST` |
| `GET` | `/api/appointments/{id}` | — | `200 Appointment` incl. `history` | `404` |
| `POST` | `/api/appointments/{id}/cancel` | optional `{ by: "PATIENT" \| "STAFF" }` (default `PATIENT`; drives the history line and event) | `200 Appointment` | `409 ILLEGAL_TRANSITION` |
| `POST` | `/api/appointments/{id}/reschedule` | `{ startTime, practitionerId? }` | `200 Appointment` (status unchanged) | `409 SLOT_TAKEN / … / ILLEGAL_TRANSITION` |
| `POST` | `/api/appointments/{id}/confirm` | — | `200 Appointment` | `409 ILLEGAL_TRANSITION` |
| `POST` | `/api/appointments/{id}/complete` | — | `200 Appointment` | `409 ILLEGAL_TRANSITION` |
| `POST` | `/api/appointments/{id}/no-show` | — | `200 Appointment` | `409 ILLEGAL_TRANSITION` |

State machine: `REQUESTED → CONFIRMED → COMPLETED`; `CANCELLED` and `NO_SHOW` reachable from
`REQUESTED` and `CONFIRMED`; `COMPLETED`, `CANCELLED`, `NO_SHOW` are terminal. Reschedule is
allowed in `REQUESTED`/`CONFIRMED` only and is atomic (old window released, new one taken
under the same lock); the released window goes to waitlist matching like a cancellation.

Concurrency: `POST /appointments`, `/reschedule` and offer acceptance run
`SELECT … FOR UPDATE` on the practitioner row (the practitioner's calendar) inside the
transaction, then check overlap, then insert. Two concurrent bookings of the same slot yield
exactly one `201` and one `409 SLOT_TAKEN` (`ConcurrentBookingIT`). Transitions lock the
appointment row; reschedule locks the appointment first, then the practitioners involved in
id order, so crossing reschedules cannot deadlock. The three 409 checks run in this order:
`IN_THE_PAST`, `OUTSIDE_WORKING_HOURS`, `SLOT_TAKEN`.

### Waitlist (patient)
| Method | Path | Body | Success | Errors |
|---|---|---|---|---|
| `POST` | `/api/waitlist` | `{ treatmentTypeId, practitionerId?, preferredWindows[], patient }` | `201 WaitlistEntry` | `400` (≥ 1 window), `404` |
| `GET` | `/api/waitlist/{id}` | — | `200 WaitlistEntry` (client polls every 5 s on P7) | `404` |
| `DELETE` | `/api/waitlist/{id}` | — | `204` (status `REMOVED`, pending offer withdrawn) | `404` |
| `POST` | `/api/waitlist/{id}/offer/accept` | — | `200 { entry: WaitlistEntry, appointment: Appointment }` (appointment `CONFIRMED`) | `409 OFFER_NOT_ACTIVE`, `409 SLOT_TAKEN` |
| `POST` | `/api/waitlist/{id}/offer/decline` | — | `200 WaitlistEntry` (status `DECLINED`, position kept; window re-offered to the next match) | `409 OFFER_NOT_ACTIVE` |

Matching (booking-service, triggered by consuming `CANCELLED`/`RESCHEDULED` from Kafka and by
the expiry/decline path): for the freed window `[start, end)` on practitioner `P`, pick the
lowest-position queue-eligible entry such that `entry.practitionerId ∈ {null, P}`,
`start + treatment.duration ≤ end`, `start` is in the future, at least one preferred window
matches `start` (`WEEKDAY_MORNINGS` Mon–Fri < 12:00 · `WEEKDAY_AFTERNOONS` Mon–Fri 12:00–16:00 ·
`AFTER_16` ≥ 16:00 · `ANY_THIS_WEEK` same ISO week as today · `FRIDAYS_ONLY` Friday), and the
entry has not already declined/expired an offer for that exact `start`/`P`. Create a `PENDING`
`SlotOffer` (`expiresAt = now + offerHoldMinutes`), set the entry `OFFERED`, publish
`waitlist-events OFFER_SENT`. Nothing matching → the window simply stays public.

### Staff
| Method | Path | Response |
|---|---|---|
| `GET` | `/api/staff/schedule?date=YYYY-MM-DD` | `DaySchedule` |
| `GET` | `/api/staff/schedule?from=&to=` | `WeekSchedule` (≤ 14 days) |
| `GET` | `/api/staff/waitlist` | `WaitlistEntry[]` — all except `REMOVED`; `ACCEPTED` kept for 24 h; ordered by `createdAt` |
| `POST` | `/api/staff/waitlist/{id}/offer` | `200 WaitlistEntry` — server offers the earliest free matching slot within 14 days; `409 OFFER_ALREADY_PENDING / NO_MATCHING_SLOT` |
| `POST` | `/api/staff/waitlist/{id}/offer/withdraw` | `200 WaitlistEntry` (offer `WITHDRAWN`, entry back to `WAITING`); `409 OFFER_NOT_ACTIVE` |
| `GET` | `/api/staff/recalls` | `RecallRow[]` |
| `POST` | `/api/staff/recalls/send` | body `{ email }` → `200 RecallRow` (publishes `RECALL_DUE` now, sets `recallSentAt`) |
| `GET` | `/api/staff/metrics/summary` | `MetricsSummary` |

```ts
type DaySchedule = {
  date: string;
  clinicOpen: { startTime: "HH:mm"; endTime: "HH:mm" };          // min/max working hours that day → calendar grid bounds
  practitioners: {
    id: string; name: string; title: "DENTIST"|"HYGIENIST";
    workingHours: { startTime: "HH:mm"; endTime: "HH:mm" }[];   // that weekday only; gaps are hatched
    appointments: Appointment[];                                // all statuses, no history
  }[];
}
type WeekSchedule = {
  days: { date: string; appointments: Appointment[]; bookedCount: number; noShowCount: number; openSlotCount: number }[];
}
// bookedCount = REQUESTED+CONFIRMED+COMPLETED; openSlotCount = free 30-min slots across all practitioners (0 ⇒ "Fully booked")

type RecallRow = {
  patient: Patient; practitionerName: string;
  lastVisitDate: string; dueDate: string;                         // dueDate = lastVisitDate + 6 months
  recallSentAt: string | null;
}
// population: patients (by normalised email) whose latest COMPLETED Check-up is ≥ 5 months old and who have no future REQUESTED/CONFIRMED Check-up

type MetricsSummary = {
  bookingsToday:          { value: number; delta: number };                  // appointments starting today (all statuses) vs same weekday last week
  cancellationsThisWeek:  { value: number; delta: number; refilledFromWaitlist: number }; // ISO week vs previous ISO week; refilled = accepted offers this week
  noShowRate:             { value: number; delta: number };                  // rolling 30 d: NO_SHOW / (COMPLETED + NO_SHOW), fraction; delta vs the 30 d before
  waitlistAvgWaitDays:    { value: number; delta: number; queued: number };  // mean (offer.respondedAt − createdAt) over entries ACCEPTED in the last 30 d; queued = active entries
}
```

## notification-service

| Method | Path | Response |
|---|---|---|
| `GET` | `/api/notifications?appointmentId=&waitlistEntryId=&email=&limit=50` | `Notification[]` newest first |

```ts
type Notification = {
  id: string;
  type: "BOOKING_RECEIVED"|"BOOKING_CONFIRMED"|"BOOKING_RESCHEDULED"|"BOOKING_CANCELLED"|"REMINDER"|"RECALL"|"WAITLIST_OFFER"|"WAITLIST_OFFER_EXPIRED";
  channels: ("SMS"|"EMAIL")[];                    // delivery is simulated; rows are what would have been sent
  recipient: { name: string; phone: string; email: string };
  subject: string; body: string;
  appointmentId: string | null; waitlistEntryId: string | null;
  createdAt: string;
}
```
The staff drawer merges these with `Appointment.history` into one timeline
(`"Confirmation sent by email"`, `"Reminder sent"`), sorted by time.

## Kafka topics and events

Envelope for every record: `{ eventId: uuid, type, occurredAt, ...payload }`, JSON, key = aggregate id.

| Topic | Key | Types | Payload |
|---|---|---|---|
| `appointment-events` | appointmentId | `BOOKED`, `CONFIRMED`, `RESCHEDULED`, `CANCELLED`, `COMPLETED`, `NO_SHOW`, `REMINDER_DUE` | `appointment` snapshot (no history); `RESCHEDULED` adds `previous: { startTime, endTime, practitionerId }`; `CANCELLED` adds `cancelledBy: "PATIENT"\|"STAFF"` |
| `waitlist-events` | waitlistEntryId | `ENTRY_CREATED`, `OFFER_SENT`, `OFFER_ACCEPTED`, `OFFER_DECLINED`, `OFFER_EXPIRED`, `OFFER_WITHDRAWN`, `ENTRY_REMOVED` | `entry` snapshot + `offer` (when relevant) |
| `recall-events` | patient email | `RECALL_DUE` | `patient`, `lastVisitDate`, `dueDate`, `practitionerName` |

Consumers:
- **notification-service** consumes all three and writes one `Notification` per relevant event
  (`BOOKED→BOOKING_RECEIVED`, `CONFIRMED→BOOKING_CONFIRMED`, `RESCHEDULED→BOOKING_RESCHEDULED`,
  `CANCELLED→BOOKING_CANCELLED`, `REMINDER_DUE→REMINDER`, `RECALL_DUE→RECALL`,
  `OFFER_SENT→WAITLIST_OFFER`, `OFFER_EXPIRED→WAITLIST_OFFER_EXPIRED`).
- **booking-service** consumes its own `appointment-events` (`CANCELLED`, `RESCHEDULED`) to run
  waitlist matching (consumer group `booking-waitlist-matcher`).

## Scheduled jobs (booking-service)

| Cron (Europe/Oslo) | Job | Effect |
|---|---|---|
| daily 18:00 | reminders | `REMINDER_DUE` for every `CONFIRMED` appointment starting tomorrow (idempotent: at most once per appointment) |
| daily 06:00 | recalls | `RECALL_DUE` for every `RecallRow` with `dueDate ≤ today` and `recallSentAt` null or > 30 days old; sets `recallSentAt`. Rows are listed from one month before `dueDate`. |
| every minute | offer expiry | `PENDING` offers past `expiresAt` → `EXPIRED`, entry `EXPIRED`, publish `OFFER_EXPIRED`, re-run matching for that window |

## Frontend routes

One Vite app (`frontend/`), served on port 3001 by compose and 5173 by `npm run dev`.

| Route | Screen |
|---|---|
| `/` | Patient booking flow P1–P6 (step machine with a history stack); reschedule re-enters at P3 via router state |
| `/appointments/{id}` | P9 "My appointment" (the reminder link) |
| `/waitlist/{id}` | P7 queue position + P8 offer sheet (polls every 5 s) |
| `/staff` | S1 Today · `/staff/week` S2 · `/staff/waitlist` S3 · `/staff/recalls` S4 |

The data layer is `src/api/client.ts` (one fetch wrapper, `ApiError` from problem+json) and `src/api/hooks.ts` (TanStack Query). Everything under `src/patient` and `src/staff` is the design's markup with inline styles; hover effects live in `src/styles.css`.

## Screen → endpoint map

| Screen | Reads | Writes |
|---|---|---|
| P1 | `GET /treatment-types` | — |
| P2 | `GET /practitioners` | — |
| P3 | `GET /availability` (7 days) | — |
| P4 | `GET /config` (hold/step copy) | `POST /appointments` |
| P5 | — | — |
| P6 | — | `POST /waitlist` |
| P7 / P8 | `GET /waitlist/{id}` (poll 5 s), `GET /config` | `DELETE /waitlist/{id}`, `POST …/offer/accept`, `POST …/offer/decline` |
| P9 | `GET /appointments/{id}` | `POST …/cancel`, `POST …/reschedule` |
| Staff chrome | counts from the three view queries | — |
| Metrics strip | `GET /staff/metrics/summary` | — |
| S1 Today | `GET /staff/schedule?date` | — |
| Drawer | `GET /appointments/{id}`, `GET :8082/notifications?appointmentId` | `POST …/confirm\|complete\|no-show\|cancel` |
| S2 Week | `GET /staff/schedule?from&to` | — |
| S3 Waitlist | `GET /staff/waitlist` (poll 15 s for countdowns) | `POST …/offer`, `POST …/offer/withdraw` |
| S4 Recalls | `GET /staff/recalls` | `POST /staff/recalls/send` |
