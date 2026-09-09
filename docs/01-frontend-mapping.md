# Step 0 — Frontend mapping

Source material: `design_handoff_dentline/` — two self-contained prototypes
(`Dentline Patient.dc.html`, `Dentline Staff.dc.html`) running on the design tool's
`DCLogic` runtime (`support.js`), plus the handoff `README.md`.

> **Finding that changes the plan:** there is no React/TypeScript/Vite application in
> the repository. The `.dc.html` files are template + logic prototypes for a proprietary
> runtime, and the handoff README states they are "not production code to copy" and must
> be "recreated in the target codebase's environment". The frontend therefore has to be
> built (React 18 + TypeScript + Vite), porting the prototype templates 1:1 — the inline
> styles, copy, motion and states are all directly portable to JSX. See
> [Decisions](#decisions-that-need-your-sign-off) at the end.

Everything below is extracted from the prototype logic and templates, not from the
handoff README, except where noted.

## Conventions the prototypes use today

| Concern | In the prototype | Notes for the API |
|---|---|---|
| Entity ids | slug strings (`checkup`, `nordvik`) | Backend uses UUIDs; treatment types keep a stable `code` |
| Appointment reference | `DL-4821` (string, shown to users) | Kept as-is: `DL-` + zero-padded sequence |
| Status values | lowercase snake: `requested`, `confirmed`, `completed`, `cancelled`, `no_show` | API uses `UPPER_SNAKE`; the frontend data layer lower-cases for the palette lookup |
| Waitlist state | `offer`, `declined`, `waiting`, `accepted` | API: `WAITING`, `OFFERED`, `ACCEPTED`, `DECLINED`, `EXPIRED`, `REMOVED` |
| Preferred windows | ids `wm`, `wa`, `late`, `asap`, `fri` | API: `WEEKDAY_MORNINGS`, `WEEKDAY_AFTERNOONS`, `AFTER_16`, `ANY_THIS_WEEK`, `FRIDAYS_ONLY` |
| Practitioner title | display strings `Dentist` / `Hygienist` | API: `DENTIST` / `HYGIENIST`, mapped client-side |
| Clock times | `"HH:mm"` strings, minutes-since-midnight internally | Working hours stay `"HH:mm"`; instants are ISO-8601 with offset |
| Dates | day offsets from a frozen "today" (`2026-09-08`) | `YYYY-MM-DD`; "today" is the real date in `Europe/Oslo` |
| Display dates | `Tue 8 Sep`, `Tue 8 Sep · 08:00`, `2 Sep 2026, 06:00`, `2 Sep 14:12` | Client formatting only |
| Prices | pre-formatted strings `"1 190"` + `kr` suffix | API: `priceNok` integer; client formats with thin-space thousands |
| Durations | integer minutes | `durationMinutes` |
| Phone | `+47 913 44 208` (E.164 with spaces) | Stored as entered, validated ≥ 8 digits |
| Slot step | prop, 15 or 30 min | Server config `dentline.availability.slot-step-minutes`, exposed via `GET /api/config` |
| Offer hold | prop, default 15 min | Server config `dentline.waitlist.offer-hold-minutes`, exposed via `GET /api/config` |

## Patient prototype — screens

### P1 Treatment choice
- **Shows:** list of treatments: 2-letter code, name, duration (min), price.
- **Actions:** pick treatment → P2.
- **Backend:** `GET /api/treatment-types`.
- **Decoration:** intro copy, stagger animation. The 2-letter code (`CU`, `RC`) is not derivable from the name, so it is seeded as `TreatmentType.code`.

### P2 Practitioner choice
- **Shows:** "First available" card; per practitioner: initials, name, title, hours label (`08:00–15:30`).
- **Actions:** pick practitioner or "first available" (`practitionerId = null`) → P3, loads today.
- **Backend:** `GET /api/practitioners` (with working hours).
- **Derived client-side:** initials from name (skipping `Dr.`), hours label = earliest start–latest end across the week, title display string.

### P3 Slot picker
- **Shows:** 7-day strip from today (weekday, day number, note `N free` / `Full` / `Closed`), then slots for the selected day grouped Morning (< 12:00) / Afternoon with `N open` counts, or the fully-booked empty state.
- **States:** loading skeleton (fake 620 ms in the prototype → real request latency), slots, fully booked (`Full` = working hours exist but no slot fits; `Closed` = no working hours that day; the prototype distinguishes them and disables closed days).
- **Actions:** pick day (refetch); pick slot → P4 (remembers which practitioner owns the slot in first-available mode); "Join waitlist" → P6; "See next open day" → jumps to the next day in the strip that has slots.
- **Backend:** one call for the strip + slots: `GET /api/availability?treatmentTypeId&from&to&practitionerId?` returning per-day `closed` flag and slot list with `practitionerId` per slot.
- **Algorithm (prototype `slotsFor`, authoritative):** for each working-hours window on that weekday, `for t = start; t + duration <= end; t += step` emit `t` unless it overlaps a blocking appointment (`t < appt.end && t + duration > appt.start`). First-available merges all practitioners, de-duplicates identical start times keeping the first practitioner, sorts ascending. Blocking = `REQUESTED`/`CONFIRMED` appointments **plus active slot offers** (holds). Past slots on today must also be excluded (prototype ignores this because its clock is frozen).
- **Decoration:** the prototype hard-codes Thursday as fully booked clinic-wide; that becomes seed data.

### P4 Details & confirm
- **Shows:** summary rows (Treatment, Practitioner, When `Tue 8 Sep · 08:00`, Duration), total price, three fields.
- **Validation (client, mirrored server-side):** name ≥ 2 chars; phone ≥ 8 digits after stripping non-digits; email `^\S+@\S+\.\S+$`. Failing submit → toast "Check the highlighted fields".
- **Actions:** "Confirm booking" → P5.
- **Backend:** `POST /api/appointments` → `201` with the appointment, `400` with field errors, `409` if the slot was taken meanwhile (→ toast + back to P3 with the day refetched).
- **Decoration / over-promise:** footnote "Your slot is held while you fill this in" — the prototype does not hold anything, and the target design has no form-time hold. See decision 8.

### P5 Confirmation
- **Shows:** first name, email, reference in header (`Booking DL-4821`), summary rows, reminder strip.
- **Actions:** "Add to calendar" (toast only in the prototype) → client-generated `.ics`; "View my appointment" → P9.
- **Backend:** none beyond the `POST` response.
- **Note:** the toast text is "Appointment requested — confirmed" and P9 shows a `Confirmed` badge, but the staff side shows online bookings as `requested` until the front desk confirms. See decision 2.

### P6 Waitlist join
- **Shows:** five preferred-window pills (multi-select; the prototype pre-selects "Weekday mornings"), card echoing treatment + practitioner label ("first available" or a name). Join disabled until ≥ 1 window.
- **Actions:** "Join waitlist" → P7, toast "You are number N in the queue".
- **Backend:** `POST /api/waitlist` → `201` with `position`, `queueSize`.
- **Note:** the spec says `preferredWindow` (singular); the prototype is multi-select, so the API takes `preferredWindows[]`. The card echoes the chosen practitioner, so the entry carries an optional `practitionerId` used by matching.

### P7 Queue position
- **Shows:** position (48 px), `of {queueSize} waiting for {treatment}` (queue is **per treatment**), chosen windows, hold minutes.
- **Actions:** "Leave waitlist" → P1, toast. Receives the offer (P8) asynchronously.
- **Backend:** `GET /api/waitlist/{id}` polled (5 s) while on this screen; `DELETE /api/waitlist/{id}`.
- **Decoration:** "Typical wait at position N is 4–6 days" is static copy (position interpolated).

### P8 Slot offer (bottom sheet)
- **Shows:** offered time (`Fri 11 Sep · 13:45`), treatment + practitioner name, `m:ss` countdown, draining progress bar, hold minutes.
- **Countdown:** display-only, derived from server `expiresAt`; closes itself at zero.
- **Actions:** Accept → P9 with the new appointment (`CONFIRMED`), toast; Decline → stays on P7, keeps position, toast.
- **Backend:** offer comes from the polled `GET /api/waitlist/{id}` (`offer` object); `POST /api/waitlist/{id}/offer/accept` → `200 { entry, appointment }`; `POST /api/waitlist/{id}/offer/decline`. Both `409` if the offer is no longer active (expired / withdrawn).

### P9 My appointment
- **Shows:** reference, status badge (prototype has `confirmed` mint and `cancelled` grey only), when (`Wed 16 Sep · 09:15`), `Treatment · N min · Practitioner`, practitioner initials, static clinic address.
- **States:** active (Reschedule / Cancel buttons), cancelled (grey panel + "Book a new appointment"). Cancel dialog quotes the appointment time.
- **Actions:** Reschedule → P3 with the practitioner pre-selected, toast "Pick a new time — old slot stays held"; picking a slot then moves the appointment (no new details step). Cancel → dialog → cancelled state, toast.
- **Backend:** `GET /api/appointments/{id}`; `POST /api/appointments/{id}/reschedule { startTime, practitionerId? }`; `POST /api/appointments/{id}/cancel`.
- **Access:** no auth in scope — the UUID in the reminder link is the capability. Stated in README.
- **Gap:** needs `REQUESTED` (amber) and `COMPLETED`/`NO_SHOW` badge variants for real data; palette taken from the staff side. Reschedule/Cancel are hidden unless the appointment is `REQUESTED` or `CONFIRMED`.

## Staff prototype — screens

### Chrome (sidebar + header)
- **Shows:** nav items with counts — Today `11`, Week (none), Waitlist `6`, Recalls `6`; footer user `Silje Kvam · Front desk`; header title/subline per view; five-chip status legend.
- **Backend:** counts derive from the same queries the views use (today's appointment count, active waitlist entries, due recalls).
- **Decoration:** footer user (no auth), legend, `Week 37 · 7–11 September` (ISO week computed client-side), `Today · Tuesday 8 September` (formatted from the real date), subline `11 appointments · 3 practitioners · clinic open 08:00–18:00` (counts + min/max working hours from the schedule payload).

### Metrics strip (all views)
| Card | Value | Delta | Note (static copy) |
|---|---|---|---|
| Bookings today | `11` | `+2` vs same weekday last week | "vs. same day last week" |
| Cancellations this week | `4` | `−1` vs previous week | "N refilled from waitlist" (N is data) |
| No-show rate | `3.8%` | `+0.6` vs previous 30 days | "rolling 30 days" |
| Avg. waitlist wait | `5.2 d` | `−0.8` | "across N queued patients" (N is data) |

- **Backend:** `GET /api/staff/metrics/summary`. Delta colour is derived client-side (a positive delta is bad for cancellations and no-shows).

### S1 Today
- **Shows:** per practitioner column: initials, name, title, hours label, off-hours hatch (prototype: one off window per practitioner; real data has lunch breaks → hatch every gap outside working hours), hour lines 08:00–18:00, "now" line (frozen at 11:20 → real clock), appointment blocks with `08:00–08:30`, patient name, treatment name, status colours, `line-through` for cancelled. Blocks need: start, duration, patient name, treatment name, status.
- **Actions:** click block → drawer.
- **Backend:** `GET /api/staff/schedule?date=` → practitioners with working hours and their appointments for the day (all statuses, cancelled included — the prototype renders cancelled blocks).
- **Grid bounds:** 08:00–18:00 = clinic open hours (min start / max end across practitioners that day). Returned as `clinicOpen` so the grid is data-driven.

### Drawer (appointment details)
- **Shows:** reference, patient name, phone · email, status badge, rows Treatment / Practitioner / Time (`12:15–13:45`) / Duration / Price, 2×2 actions (Confirm, Complete, Mark no-show, Cancel — the one matching the current status disabled at 45 %), History (`2 Sep 14:12 Requested online by Kjersti`, `2 Sep 14:12 Reminder scheduled for the day before`, `3 Sep 09:04 Confirmed by Silje Kvam`).
- **Actions:** status transitions; no-show and cancel go through a confirm dialog with copy quoting patient and start time; every change toasts `"{patient} — {outcome}"`.
- **Backend:** `POST /api/appointments/{id}/confirm|complete|no-show|cancel` (`409` on an illegal transition, optimistic update with rollback). History = `GET /api/appointments/{id}` (`history[]`) merged with `GET /api/notifications?appointmentId=` from notification-service (this is where "Reminder scheduled…" and "Confirmation sent…" lines come from).
- **Change vs prototype:** the prototype only disables the action equal to the current status; with a real state machine `Confirm` on a `COMPLETED` appointment is also illegal. The drawer disables every action not in `allowedActions` from the API — same visual treatment, fewer 409s. "by Silje Kvam" becomes "by front desk" (no auth).

### S2 Week
- **Shows:** Mon–Fri columns for the current ISO week, header `Mon 7 Sep` + subline (`9 booked · 1 no-show`, `Today · 11 booked` on mint, `Fully booked`, `3 booked · openings`), compact blocks (time range, patient, `Treatment · N min`), empty copy "Nothing booked yet — a quiet day."
- **Actions:** click → prototype only toasts; real build opens the same drawer.
- **Backend:** `GET /api/staff/schedule?from&to` → per day appointments plus `bookedCount`, `noShowCount`, `openSlotCount` (30-min slots free across all practitioners; `0` ⇒ "Fully booked", `> 0` ⇒ "openings").
- **Decoration:** the prototype's week data are hand-written strings (`'9 booked · 1 no-show'`) that do not match the listed items; sublines are computed from data in the real build.

### S3 Waitlist
- **Shows:** Pos, patient (name, `phone · waiting 3 days`), treatment, window label(s), badge, action.
- **Badges / actions:** `Offer sent — expires in 12:34` (amber, pulsing, live countdown, "Withdraw offer") · `Declined 11:04` (red tint, "Send offer") · `Waiting` (grey, "Send offer") · `Accepted 09:52` (mint, "Open booking" → drawer for the created appointment). `Expired HH:mm` reuses the grey `Waiting` styling with the text changed.
- **Backend:** `GET /api/staff/waitlist` (all non-removed entries, accepted ones kept for 24 h); `POST /api/staff/waitlist/{id}/offer` (server picks the earliest matching free slot — the design has no slot picker); `POST /api/staff/waitlist/{id}/offer/withdraw`.
- **Position semantics:** `position` = rank by `createdAt` among queue-eligible entries **for the same treatment** (matches the patient's "3 of 7 waiting for Cleaning"). Table rows are sorted by `createdAt` globally. The prototype's 1–6 numbering is a global row index; see decision 6.

### S4 Recalls
- **Shows:** patient (+ practitioner of the last visit), last visit date, due (`Overdue 4 days` red / `Due 18 Sep`), recall sent timestamp or `—`, action "Send recall" / "Resend".
- **Actions:** send → sets timestamp to now, toast "Recall (re)sent to {patient}".
- **Backend:** `GET /api/staff/recalls` (patients whose last `COMPLETED` check-up is ≥ 5 months ago and who have no upcoming check-up, ordered by due date); `POST /api/staff/recalls/send`. Due = last visit + 6 months. The daily 06:00 job publishes `RECALL_DUE` for overdue ones automatically.
- **Identity:** there is no Patient entity in the spec; a patient is identified by normalised email across appointments. See decision 7.
- **Decoration:** the demo row `Hedda Moen, last visit 12 Mar → Overdue 4 days` is inconsistent with the 6-month rule (would be due 12 Sep); computed values win.

## Pure decoration (no backend meaning)
- Patient: "Demo states" bar (removed in production), phone-shell status bar, step dots, back-stack, stagger/pop/slide animations, skeleton timing, toasts, clinic address block, "Typical wait … 4–6 days" copy, "Add to calendar" (client-side `.ics`).
- Staff: sidebar user, status legend, hour-scale prop (`rowHeight`), now-line, all colour/border tokens, toast copy, history line "Reminder scheduled for the day before" as a booking-side history entry (kept because it is written by booking-service at booking time).
- Both: every derived label (initials, hours label, `waiting 3 days`, `Overdue 4 days`, Morning/Afternoon grouping, `N open`, `N free`, `Week 37`, header titles).

## Seed data the screens require (for Step 6)
Relative to the real "today", generated at startup by a seeder (Flyway seeds only static reference data: treatment types, practitioners, working hours — appointments and offers must be date-relative and an active countdown cannot be expressed in a migration).
- 3 practitioners with the handoff working hours (Nordvik Mon–Fri split, Sæther Mon–Thu late split, Lund Mon/Tue/Thu/Fri split).
- Today: 11 appointments across all 3 in every status incl. `NO_SHOW` (Line Haugland 10:00) and `CANCELLED` (Marte Rønning 09:00); remaining weekdays ≈ 15 more; Thursday clinic-wide full for 30-min slots.
- History rows for online booking + confirmation so the drawer history is populated.
- Waitlist: 6 entries — one `OFFERED` with `expiresAt ≈ now + 12:34`, one `DECLINED`, one `ACCEPTED` (with its appointment), three `WAITING`; mixed treatments so per-treatment positions look sane.
- Recalls: 6 patients with `COMPLETED` check-ups 5–6 months ago, two with `recallSentAt` set.
- Notification rows in notification-service for the seeded confirmations (seeded by replaying events on startup is the cleanest: the booking seeder publishes the events it would have published).

## Decisions that need your sign-off

1. **No frontend exists.** Proposal: Step 6 becomes "build the React + TypeScript + Vite app from the prototypes, wired to the API from the start". The prototype templates are inline-styled markup with `{{ }}` bindings and port mechanically to JSX; visuals, copy and motion are kept 1:1, the demo-states bar is dropped. If you have the actual React export elsewhere, drop it into `frontend/` before Step 6 and I will wire that instead.
2. **Online booking status.** `POST /api/appointments` creates `REQUESTED` (the staff side shows online bookings as requested until confirmed; history says "Requested online by …"). Accepting a waitlist offer creates `CONFIRMED` directly. P9 gets a `Requested` badge variant (amber tokens from the staff palette). The P5 toast keeps its copy.
3. **Where waitlist matching lives.** The spec puts "waitlist offer logic" in notification-service but also puts `WaitlistEntry`, `SlotOffer`, the expiry job and offer acceptance (which creates an appointment and must block availability) in booking-service. Proposal: booking-service owns the waitlist and matching, triggered by consuming `CANCELLED`/`RESCHEDULED` from Kafka (event-driven, same consumer machinery); notification-service consumes a third topic `waitlist-events` and stores the offer/expiry notifications (the "we text and email you"). A cross-service hold would otherwise be needed.
4. **Extra event types.** `RESCHEDULED` (frees the old window → waitlist matching, patient notification) and `REMINDER_DUE` (the daily reminder job) on `appointment-events`, in addition to the five listed.
5. **Staff "Send offer"** with no slot picker in the design: the server offers the earliest free slot within 14 days that matches the entry's treatment, practitioner preference and windows; `409` if none.
6. **Waitlist position** is per treatment queue (the only semantics that matches "of 7 waiting for Cleaning"). The staff Pos column shows it.
7. **Patient identity = normalised email** (no Patient table). Needed for recalls and "avg waitlist wait". Listed under production next steps in the README.
8. **No form-time hold.** Booking is atomic under the practitioner lock; a `409` sends the patient back to the slot picker with a toast. The P4 footnote "Your slot is held while you fill this in" then over-promises. Default: keep the copy untouched as instructed; say the word and I change it to "Openings are live — confirm to secure this time."
9. **Naming.** `UPPER_SNAKE` enums, ISO-8601 with offset, `priceNok` integer, UUID ids, `DL-nnnn` references. Transition endpoints are verbs on the appointment resource (`/confirm`, `/complete`, `/no-show`, `/cancel`, `/reschedule`) rather than a `PATCH … { status }`, so the state machine is explicit in the API.
