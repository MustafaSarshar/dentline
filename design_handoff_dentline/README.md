# Handoff: Dentline — appointment booking (patient flow + staff dashboard)

## Overview
Dentline is an appointment booking system for a dental clinic (Dentline Majorstuen, Oslo). Two interfaces:

1. **Patient booking flow** — mobile-first. Choose treatment → practitioner (or "first available") → date/time from computed availability → patient details → confirmation. Handles the fully-booked path (waitlist join, queue position, time-limited slot offer with countdown) and a "my appointment" view with reschedule/cancel.
2. **Staff dashboard** — desktop-first. Today view (column per practitioner), week overview, waitlist queue with live offer status, 6-month recalls, and a metrics strip.

Target backend: **Kotlin + Spring Boot + PostgreSQL + Kafka**. The frontend prototypes were built to mirror that domain model exactly, including the timed reservation logic and event-driven notifications.

## About the design files
The two files in this bundle are **design references created in HTML** — interactive prototypes that show intended look and behavior. They are **not production code to copy**. The task is to **recreate these designs in the target codebase's environment** (React/Next, Vue, Angular, Compose Multiplatform, native — whatever the project uses) following its established patterns, component library and state management. If no frontend exists yet, pick the framework that best fits the stack (a Kotlin/Spring backend pairs naturally with a React + TypeScript SPA or Next.js app) and implement there.

The prototype logic (availability computation, status transitions, offer countdown) is intentionally realistic and can be read as **specification for the backend**, not as an implementation to port.

## Fidelity
**High fidelity.** Colors, typography, spacing, radii, shadows, motion timings and all copy are final and should be reproduced precisely. Demo data is illustrative but realistic and can be used for seeding/fixtures.

---

## Domain model (authoritative — use these exact concepts)

### Practitioner
| Field | Type | Notes |
|---|---|---|
| id | UUID | |
| name | String | e.g. "Dr. Astrid Nordvik" |
| title | Enum | `DENTIST` \| `HYGIENIST` — rendered as "Dentist" / "Hygienist" |
| photoUrl | String? | Prototype falls back to initials on a mint circle |
| workingHours | List<WorkingHours> | per weekday, may be several windows per day (lunch break) |

`WorkingHours`: `dayOfWeek` (1–7), `startTime`, `endTime`. Multiple rows per weekday model breaks.

Demo practitioners:
- **Dr. Astrid Nordvik** — Dentist, initials AN, Mon–Fri 08:00–11:30 and 12:15–15:30
- **Dr. Henrik Sæther** — Dentist, initials HS, Mon–Thu 10:00–13:00 and 13:45–18:00
- **Mari Lund** — Hygienist, initials ML, Mon, Tue, Thu, Fri 08:00–11:30 and 12:15–15:30

### TreatmentType
| id | name | durationMinutes | priceMinorUnits | display |
|---|---|---|---|---|
| checkup | Check-up | 30 | 89000 | 890 kr |
| clean | Cleaning | 45 | 119000 | 1 190 kr |
| fill | Filling | 60 | 175000 | 1 750 kr |
| root | Root canal | 90 | 450000 | 4 500 kr |
| white | Whitening | 60 | 320000 | 3 200 kr |

Prices are NOK, formatted with a non-breaking/thin space thousands separator and a lowercase `kr` suffix. Store as minor units (øre) or `NUMERIC(10,2)`; never floats.

### Appointment
| Field | Type | Notes |
|---|---|---|
| id / reference | UUID / String | reference shown to users as `DL-4821` |
| patient | Patient | name, phone (E.164, `+47 913 44 208`), email |
| practitioner | Practitioner | |
| treatmentType | TreatmentType | |
| startTime / endTime | Instant / LocalDateTime | `endTime = startTime + treatment.duration` — validate on write |
| status | Enum | see below |

**Status flow:** `REQUESTED → CONFIRMED → COMPLETED`, with `CANCELLED` and `NO_SHOW` as terminal branches from `REQUESTED`/`CONFIRMED`. Enforce transitions server-side; illegal transitions → 409. The staff drawer disables the action matching the current status.

### WaitlistEntry
| Field | Type | Notes |
|---|---|---|
| id | UUID | |
| patient | Patient | |
| desiredTreatment | TreatmentType | |
| preferredWindows | Set<Enum> | `WEEKDAY_MORNINGS`, `WEEKDAY_AFTERNOONS`, `AFTER_16`, `ANY_THIS_WEEK`, `FRIDAYS_ONLY` |
| position | Int | derived from `createdAt` ordering within a treatment queue — do not store a mutable rank |
| offerStatus | Enum | `WAITING`, `OFFER_SENT`, `ACCEPTED`, `DECLINED`, `EXPIRED` |
| offerExpiresAt | Instant? | set when an offer is sent |

### Availability — computed, never stored
There is **no `Slot` entity**. Available slots are derived:

```
slots(practitioner, date, treatment) =
  for each workingHours window on that weekday:
    for t = window.start; t + treatment.duration <= window.end; t += SLOT_STEP:
      emit t unless it overlaps any non-cancelled appointment
      (overlap: t < appt.end && t + duration > appt.start)
```

- `SLOT_STEP` is 15 minutes by default (the prototype exposes 15/30 as a tweak). Make it configurable.
- `CANCELLED` and `NO_SHOW` appointments do **not** block availability for future dates; `REQUESTED` and `CONFIRMED` do. Held (reserved) slots must also block — see below.
- "First available" merges all practitioners' slots for the treatment, de-duplicates identical start times, sorts ascending, and remembers which practitioner each slot belongs to.
- A day with no working hours renders as **Closed**; a day whose slots are all consumed renders as **Full**.

### Timed reservation (the offer)
When an appointment is cancelled, the freed window is offered to the first matching waitlist entry:
1. Create a **hold** on the freed window (`heldUntil = now + 15 min`, default configurable) so it disappears from public availability.
2. Publish an offer event; the patient sees a modal with a live countdown, "This slot is held for you for 15 minutes", and Accept / Decline.
3. **Accept** → create appointment in `CONFIRMED`, remove waitlist entry, release the hold.
4. **Decline** → release the hold, pass the offer to the next entry, keep the declining patient's queue position.
5. **Expiry** → same as decline but mark `EXPIRED`. Drive expiry from a scheduled sweep, not from client timers; the client countdown is display-only and must reconcile with `offerExpiresAt` from the server.

Staff see this as `Offer sent — expires in 12:34`, `Declined 11:04`, `Accepted 09:52`, or `Waiting`.

---

## Suggested API surface

Patient (public / token-scoped):
```
GET  /api/treatment-types
GET  /api/practitioners
GET  /api/availability?treatmentTypeId&practitionerId?&from&to
     → [{ date, practitionerId, slots: ["08:00", "08:15", ...] }]
POST /api/appointments            { treatmentTypeId, practitionerId, startTime, patient{...} }
     → 201 { reference, status: "CONFIRMED" | "REQUESTED", ... }   409 if the slot went away
GET  /api/appointments/{token}    my-appointment view via emailed link
POST /api/appointments/{token}/cancel
POST /api/appointments/{token}/reschedule   { startTime, practitionerId }
POST /api/waitlist                { treatmentTypeId, preferredWindows[], patient{...} }
     → { id, position, queueSize }
GET  /api/waitlist/{token}        → { position, queueSize, offer? { startTime, practitionerName, expiresAt } }
POST /api/waitlist/{token}/offer/accept | /decline
DELETE /api/waitlist/{token}
```

Staff (authenticated):
```
GET  /api/staff/schedule?date              per-practitioner day schedule
GET  /api/staff/schedule?from&to           week overview
PATCH /api/staff/appointments/{id}/status  { status: CONFIRMED|COMPLETED|NO_SHOW|CANCELLED }
GET  /api/staff/waitlist
POST /api/staff/waitlist/{id}/offer        send / withdraw
GET  /api/staff/recalls
POST /api/staff/recalls/{patientId}/send
GET  /api/staff/metrics                    bookings today, cancellations this week, no-show rate, avg waitlist wait
```

Times: send ISO-8601 with offset; render in `Europe/Oslo`, 24-hour, no am/pm. All display dates in the prototype are e.g. `Tue 8 Sep`, `2 Sep 2026, 06:00`.

## Kafka / eventing the UI implies
- `appointment.requested`, `appointment.confirmed`, `appointment.cancelled`, `appointment.completed`, `appointment.no_show`
- `appointment.reminder.scheduled` — the confirmation screen promises "You'll receive a reminder the day before"; the drawer history shows "Reminder scheduled for the day before"
- `waitlist.entry.created`, `waitlist.offer.sent`, `waitlist.offer.accepted`, `waitlist.offer.declined`, `waitlist.offer.expired`
- `recall.due` / `recall.sent` — a scheduled job publishes recall events daily at **06:00** (recall timestamps in the UI are all `06:00`)

Consumers: notification service (SMS + email), metrics exporter (Prometheus counters/histograms behind the four stat cards), waitlist matcher.

## Metrics behind the strip
`bookings_today` (count), `cancellations_this_week` (count), `no_show_rate` (rolling 30 days, %), `waitlist_wait_days_avg` (mean days from waitlist creation to booking). Each card shows a delta vs. the previous comparable period and a one-line note.

---

## Screens

### P1 — Treatment choice (patient, entry point)
- **Purpose:** pick a TreatmentType.
- **Layout:** single column, 390px design width, 18px horizontal padding, 10px gap. Intro paragraph 13px/1.5 `#4A5A64`: "Book in three steps. Choose what you need and we will show real openings from our practitioners' hours."
- **Treatment card:** full-width button, `#FFF`, 1px `#E3E9ED`, radius 12px, padding 14px 15px, shadow `0 1px 2px rgba(30,42,50,.05)`; 40×40 mint (`#D6F2F0`) rounded square with a 2-letter code in 13px/700 teal; name 14.5px/600; duration 12.5px `#65757F`; price right-aligned 14px/600 teal. Hover: border teal, shadow `0 8px 20px rgba(14,124,123,.16)`, `translateY(-2px)`.
- Cards enter with a 45ms staggered fade-up.

### P2 — Practitioner choice
- "First available" card on top: teal `#0E7C7B` fill, white text, 42px circle at 18% white, subtitle "Earliest opening across the clinic".
- Section label "OR CHOOSE A PRACTITIONER" (11px/600, 0.08em, uppercase, `#65757F`).
- Practitioner rows: 44px mint circle with initials, name 14.5px/600, meta `Title · 08:00–15:30`, chevron.
- Header shows the chosen treatment name and `30 min · 890 kr`.

### P3 — Slot picker
- **Day strip:** horizontally scrollable row of 58px-wide buttons (weekday abbrev, day number 16px/600, and a status line: `N free`, `Full`, or `Closed`). Selected = teal fill/white text; closed = `#F2F6F8` bg, `#8A98A1` text, `not-allowed`.
- **Loading:** 620ms skeleton — a 12×96px bar plus nine 42px pills in a 3-column grid, `#E3E9ED`, 1.3s shimmer.
- **Slots:** grouped `Morning` (< 12:00) and `Afternoon` (≥ 12:00), each with an uppercase label and `N open` count; pills in a 3-col grid, 9px gap, `#FFF` on 1px `#CFE7E6`, radius 12px, teal 14px/600 label. Hover: teal fill, white text, `translateY(-2px)`.
- Footnote 11.5px `#8A98A1`: "Openings are calculated live from <practitioner>'s working hours minus booked appointments."
- **Fully booked state:** white card, 64px mint circle containing a small geometric tooth (26px block, radius `8px 8px 13px 13px`, teal), heading "This day is fully booked", body "Nothing left on <day> for <practitioner>. Join the waitlist and we will offer you the first cancellation that fits.", primary "Join waitlist", secondary "See next open day".

### P4 — Details & confirm
- Summary card: rows Treatment / Practitioner / When / Duration (13.5px, label `#65757F`, value 600), divider, total in teal 14.5px/700.
- Fields: Full name, Phone (`tel`, inputMode tel), Email. 12.5px/600 labels, inputs 13px 14px padding, radius 12px, 1px `#D3DBE0`; focus adds `0 0 0 3px rgba(14,124,123,.14)`; error border `#B3261E` with an 11.5px message.
- **Validation:** name ≥ 2 chars ("Please enter your full name"); phone ≥ 8 digits after stripping non-digits ("Enter a phone number we can text"); email matches `^\S+@\S+\.\S+$` ("Enter a valid email address"). Failing submit also fires the toast "Check the highlighted fields".
- Primary button "Confirm booking" 15px/600, teal, shadow `0 6px 16px rgba(14,124,123,.22)`. Footnote: "Free cancellation up to 24 hours before. Your slot is held while you fill this in."

### P5 — Confirmation
76px mint circle with ✓ (pops in, 500ms), "You are booked in", "See you soon, <first name>. A confirmation is on its way to <email>.", summary card, "Add to calendar" (outline teal — should emit an .ics / Google Calendar link), "View my appointment", and a mint info strip: "You'll receive a reminder the day before."

### P6 — Waitlist join
Intro: "Tell us when you can come in. The moment a matching appointment frees up, we hold it for you and send an offer." Preferred-window pills (multi-select, radius 999px, teal when on). Card echoing treatment + practitioner. "Join waitlist" is disabled (`#B7C3CA`) until at least one window is chosen.

### P7 — Queue position
Card with uppercase "YOUR PLACE IN THE QUEUE", position in 48px/700 teal, "of 7 waiting for Cleaning", divider, chosen windows and "Typical wait at position 3 is 4–6 days." Below: "We will text and email you when a slot is offered. Offers are held for 15 minutes before passing to the next person." plus "Leave waitlist".

### P8 — Slot offer (modal, bottom sheet)
Overlay `rgba(30,42,50,.42)`, sheet radius 18px, slides up 280ms. Pulsing teal dot + "A SLOT JUST OPENED", offered time 18px/700, treatment + practitioner, mint block with a tabular-numeral `mm:ss` countdown in 22px/700 teal and "This slot is held for you for 15 minutes.", a 4px teal progress bar draining with `transition: width 1s linear`, then Decline / Accept slot (accept is 1.4× wider). On expiry the sheet closes by itself.

### P9 — My appointment (opened from the reminder link)
Reference `DL-4821`, status badge (dot + label; confirmed = mint/teal, cancelled = grey), when 19px/700, `Treatment · 30 min · Practitioner`, divider, practitioner avatar + address "Dentline Majorstuen / Kirkeveien 64B, 0364 Oslo". Actions: "Reschedule" (outline teal), "Cancel appointment" (outline `#E7CFCD`, text `#B3261E`).
**Cancel dialog:** "Cancel this appointment?" / "Your <when> slot will be released to the waitlist right away, and may be taken within minutes. This cannot be undone." / Keep it · Yes, cancel (`#B3261E`). After cancelling: grey panel "This appointment is cancelled and the slot has been released to the waitlist. You can book a new time whenever suits you." + "Book a new appointment".

### S1 — Staff: Today
- **Chrome:** 224px white sidebar (logo mark 30px teal rounded square with "D", nav items Today/Week/Waitlist/Recalls each with a dot + count pill, active = mint bg + `#0B5654` text; footer user "Silje Kvam · Front desk").
- **Header:** "Today · Tuesday 8 September" 21px/700 + subline; right side a status legend of five chips (9px swatch + label).
- **Metrics strip:** 4-up grid, white cards, uppercase label, 25px/700 value, delta (teal for good, `#B3261E` for bad), note.
- **Calendar:** 64px gutter of hour labels 08:00–18:00 + one column per practitioner. Vertical scale **1px per minute** (10h = 600px), configurable 0.7–1.6. Hairlines `#F0F4F6` at each hour. Off-hours shown as a 135° diagonal hatch (`#F7F9FA`/`#FFF`, 6px). A "now" line: 2px teal at 55% opacity (11:20 in the demo). Appointment blocks are absolutely positioned (`top = (start − 08:00) × scale`, `height = duration × scale − 4`, min 34px), inset 7px, radius 10px, 1.5px border, showing `08:00–08:30`, patient name 12.5px/600, treatment 11px. They grow in from the top (300ms).
- **Status palette:** requested `#FFF8EC` bg / dashed `#D99A2B` border / `#7A5310` text · confirmed `#D6F2F0` / `#0E7C7B` / `#0B5654` · completed `#E8F0E8` / `#7E9E82` / `#3E5641` · cancelled `#F2F6F8` / `#C3CDD3` / `#65757F` with `line-through` on the name · no-show `#FCEDEC` / `#B3261E` / `#8F1E18`.
- **Detail drawer** (392px, slides in 240ms from the right, overlay `rgba(30,42,50,.32)`): reference, patient name 19px/700, phone · email, status badge, grey panel with Treatment / Practitioner / Time / Duration / Price, a 2×2 action grid (Confirm = filled teal, Complete = outline, Mark no-show and Cancel = danger outline; the action matching the current status is disabled at 45% opacity), and a History list (`2 Sep 14:12 Requested online by …`, `Reminder scheduled for the day before`, `3 Sep 09:04 Confirmed by Silje Kvam`).
- **Destructive confirms:** "Mark as no-show?" — "<patient> will be flagged as a no-show and counted in the no-show rate. The slot stays used for today." · "Cancel this appointment?" — "The 12:15 slot will be released and offered to the waitlist immediately. <patient> gets a cancellation notice."
- Every status change raises a toast: dark `#1E2A32` pill, centred bottom, e.g. "Kjersti Aune — cancelled, slot released to waitlist".

### S2 — Staff: Week (read + click only, no drag)
Five day columns (Mon–Fri), each with a header (`Mon 7 Sep` + subline like `9 booked · 1 no-show`, `Today · 11 booked` on a mint header, `Fully booked`, `3 booked · openings`) and a vertical list of compact status-coloured blocks (time, patient, `Treatment · N min`). Empty column copy: "Nothing booked yet — a quiet day."

### S3 — Staff: Waitlist
Table: Pos (14px/700 teal) · Patient (name + `phone · waiting 3 days`) · Treatment · Time window · Offer status badge · action button. Statuses: `Offer sent — expires in 12:34` (amber, pulsing dot, live tabular countdown, action "Withdraw offer"), `Declined 11:04` (red tint, "Send offer"), `Waiting` (grey, "Send offer"), `Accepted 09:52` (mint, "Open booking"). Header subline: "6 patients queued · offers are held for 15 minutes before passing on".

### S4 — Staff: Recalls
Table: Patient (+ practitioner) · Last visit · Due (`Overdue 4 days` in `#B3261E`, otherwise `Due 18 Sep`) · Recall sent (`2 Sep 2026, 06:00` or `—`) · action ("Send recall" / "Resend"). Sending sets the timestamp and toasts "Recall sent to <patient>". Header subline: "6-month check-up recalls · the scheduled job publishes recall events at 06:00 daily".

---

## Interactions & behavior
- **Patient navigation** is a step machine with a history stack; the back arrow pops it. A 3-dot progress indicator marks steps 1–3 (treatment, practitioner/slots, details).
- **Reschedule** returns to the slot picker with the practitioner pre-selected and toasts "Pick a new time — old slot stays held".
- **Waitlist offer** appears ~4s after joining in the prototype; in production it is pushed (WebSocket/SSE or polling) when the backend sends the offer. The countdown must be derived from server `offerExpiresAt`.
- **Toasts** last 2.8s, one at a time (a new toast replaces the previous).
- **Motion:** entrances 300–360ms `cubic-bezier(.2,.7,.3,1)`; hover/press transitions 160–200ms; buttons scale to 0.982–0.985 while pressed; pulsing dots 1.8s `ease-out` infinite. Everything is disabled under `prefers-reduced-motion: reduce`.
- **Accessibility (core requirement, WCAG AA):** visible focus ring on every interactive element — `2.5px solid #0E7C7B`, 2px offset; dialogs/drawers use `role="dialog" aria-modal="true"` with labels (add focus trapping and Esc-to-close in the real build — the prototype does not implement them); toasts are `role="status"`; the sidebar's active item is `aria-current="page"`; day/slot buttons carry real `disabled` state; calendar blocks have an aria-label like "Ingrid Bakken, Check-up at 08:00, Completed"; status is never signalled by colour alone (each badge pairs a dot with a text label). All body text meets 4.5:1 on its background.
- **Responsive:** patient side is mobile-first (390px design width, fluid to larger screens, 44px+ hit targets); staff side is desktop-first — below ~1100px the metrics strip should drop to 2 columns and the day calendar should scroll horizontally rather than compress columns.

## State
Patient: `screen`, `history[]`, `treatment`, `practitioner|null` (null = first available), `dayOffset`, `slot`, `loading`, `name/phone/email`, `errors{}`, `preferredWindows[]`, `joined`, `offerOpen`, `offerSecondsLeft`, `cancelDialogOpen`, `appointmentStatus`, `toast`.
Staff: `view`, `statusOverrides{id→status}`, `selectedAppointmentId`, `confirmDialog|null`, `offerSecondsLeft`, `recallSentAt{}`, `toast`.
In production, replace the prototype's in-memory data with server state (React Query/SWR or equivalent); status mutations are optimistic with rollback on 409.

## Design tokens
```
Background        #FAFBFC     Surface / card    #FFFFFF
Canvas (outside)  #EEF1F3     Muted surface     #F2F6F8
Primary teal      #0E7C7B     Primary hover     #0A5F5E     Primary deep #0B5654
Accent mint       #D6F2F0     Mint border       #B6E4E1     Slot border  #CFE7E6
Text              #1E2A32     Secondary #4A5A64  Tertiary #65757F  Faint #8A98A1
Hairline          #E3E9ED     Grid line #F0F4F6  Input border #D3DBE0  Disabled #B7C3CA
Danger            #B3261E     Danger deep #8F1E18  Danger tint #FCEDEC  Danger border #E7CFCD
Warning           #D99A2B     Warning tint #FFF8EC  Warning text #7A5310
Success muted     #7E9E82     Success tint #E8F0E8  Success text #3E5641

Radius     10px (controls) · 12px (cards, inputs) · 14–18px (dialogs) · 999px (pills) · 28px (phone shell)
Spacing    4 · 7 · 9 · 12 · 14 · 18 · 22 · 28 (px)
Type       Inter 400/500/600/700. 10.5–11px uppercase labels (0.07–0.09em tracking),
           11.5–13px meta, 13.5–14.5px body, 15–16.5px titles, 19–21px headings,
           25px metric values, 48px queue position. Tabular numerals on all clocks/countdowns.
Shadows    card 0 1px 2px rgba(30,42,50,.05) · hover 0 8px 20px rgba(14,124,123,.16)
           drawer -14px 0 40px rgba(30,42,50,.18) · dialog 0 20px 50px rgba(30,42,50,.24)
           toast 0 12px 30px rgba(30,42,50,.26) · phone 0 24px 60px rgba(30,42,50,.16)
Overlays   rgba(30,42,50,.32) drawer · rgba(30,42,50,.42) modal
```

## Assets
None external. Inter is loaded from Google Fonts — self-host it in production. Practitioner photos are placeholders (initials on a mint circle); wire up `photoUrl` and keep the initials as fallback. The tooth in the fully-booked empty state is a deliberately minimal geometric block, not an illustration — replace it with a proper minimal icon from your icon set if you have one. No icon library is used; the few glyphs (✓ ← › ✦ ×) should become real icons.

## Files
- `Dentline Patient.dc.html` — patient flow prototype. Includes a "Demo states" bar at the top for jumping to Start booking / Fully booked / Waitlist offer / My appointment / Confirmation. **Remove that bar in production.**
- `Dentline Staff.dc.html` — staff dashboard prototype (sidebar nav switches views).

Both are single self-contained HTML files: open them in a browser to see every state and the exact motion. The availability computation lives at the top of each file's logic class and is the clearest specification of the slot algorithm.

## Demo data
Realistic Norwegian data is baked in and suitable for seeds: patients Ingrid Bakken, Omar Hussain, Kjersti Aune, Tobias Fjeld, Line Haugland, Sondre Vik, Amina Yusuf, Erlend Strøm, Marte Rønning, Jonas Berge, Sara Nyland (plus week/waitlist/recall names). The demo day is **Tuesday 8 September 2026** with 11 appointments across 3 practitioners covering every status (including one no-show, Line Haugland 10:00, and one cancelled, Marte Rønning 09:00), Thursday clinic-wide fully booked, a 6-person waitlist with one live offer, and 6 recall rows with two already sent.
