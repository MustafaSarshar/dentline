-- Booking-service schema.
-- Availability is never stored: it is computed from working_hours minus blocking appointments
-- and pending slot offers (see README, "Why availability is computed").

create table practitioner (
    id          uuid primary key,
    name        text        not null,
    title       text        not null check (title in ('DENTIST', 'HYGIENIST')),
    created_at  timestamptz not null default now()
);

-- One row per working window; several rows per weekday model breaks (e.g. lunch).
create table working_hours (
    id               uuid primary key,
    practitioner_id  uuid     not null references practitioner (id) on delete cascade,
    day_of_week      smallint not null check (day_of_week between 1 and 7), -- ISO: 1 = Monday
    start_time       time     not null,
    end_time         time     not null,
    constraint working_hours_window_valid check (start_time < end_time)
);
create index working_hours_practitioner_day_idx on working_hours (practitioner_id, day_of_week);

create table treatment_type (
    id                uuid primary key,
    code              text           not null unique,
    name              text           not null unique,
    duration_minutes  integer        not null check (duration_minutes > 0),
    price_nok         numeric(10, 2) not null check (price_nok >= 0)
);

-- Human-readable reference shown to patients and staff (DL-4821).
create sequence appointment_reference_seq start with 4801;

create table appointment (
    id                 uuid primary key,
    reference          text        not null unique,
    practitioner_id    uuid        not null references practitioner (id),
    treatment_type_id  uuid        not null references treatment_type (id),
    patient_name       text        not null,
    patient_phone      text        not null,
    patient_email      text        not null,
    start_time         timestamptz not null,
    end_time           timestamptz not null,
    status             text        not null check (status in ('REQUESTED', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'NO_SHOW')),
    reminder_sent_at   timestamptz,
    created_at         timestamptz not null,
    updated_at         timestamptz not null,
    constraint appointment_window_valid check (start_time < end_time)
);
-- The overlap check during booking only looks at blocking statuses on one practitioner's calendar.
create index appointment_calendar_idx on appointment (practitioner_id, start_time)
    where status in ('REQUESTED', 'CONFIRMED');
create index appointment_start_idx on appointment (start_time);
create index appointment_patient_email_idx on appointment (lower(patient_email));

create table appointment_history (
    id              uuid primary key,
    appointment_id  uuid        not null references appointment (id) on delete cascade,
    occurred_at     timestamptz not null,
    type            text        not null,
    description     text        not null
);
create index appointment_history_appointment_idx on appointment_history (appointment_id, occurred_at);

create table waitlist_entry (
    id                 uuid primary key,
    patient_name       text        not null,
    patient_phone      text        not null,
    patient_email      text        not null,
    treatment_type_id  uuid        not null references treatment_type (id),
    practitioner_id    uuid        references practitioner (id), -- null = any practitioner
    status             text        not null check (status in ('WAITING', 'OFFERED', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'REMOVED')),
    appointment_id     uuid        references appointment (id),  -- set when an offer is accepted
    created_at         timestamptz not null,
    updated_at         timestamptz not null
);
-- Position is derived from created_at within a treatment queue; never stored.
create index waitlist_entry_queue_idx on waitlist_entry (treatment_type_id, created_at)
    where status in ('WAITING', 'OFFERED', 'DECLINED', 'EXPIRED');

create table waitlist_entry_window (
    waitlist_entry_id  uuid not null references waitlist_entry (id) on delete cascade,
    preferred_window   text not null check (preferred_window in ('WEEKDAY_MORNINGS', 'WEEKDAY_AFTERNOONS', 'AFTER_16', 'ANY_THIS_WEEK', 'FRIDAYS_ONLY')),
    primary key (waitlist_entry_id, preferred_window)
);

-- A time-limited hold on a freed window, offered to one waitlist entry. Pending offers block availability.
create table slot_offer (
    id                 uuid primary key,
    waitlist_entry_id  uuid        not null references waitlist_entry (id) on delete cascade,
    practitioner_id    uuid        not null references practitioner (id),
    start_time         timestamptz not null,
    end_time           timestamptz not null,
    expires_at         timestamptz not null,
    status             text        not null check (status in ('PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'WITHDRAWN')),
    responded_at       timestamptz,
    created_at         timestamptz not null,
    constraint slot_offer_window_valid check (start_time < end_time)
);
create index slot_offer_pending_idx on slot_offer (practitioner_id, start_time) where status = 'PENDING';
create index slot_offer_entry_idx on slot_offer (waitlist_entry_id, created_at);

-- Last recall notice per patient (identified by normalised email; there is no patient table yet).
create table recall_notice (
    patient_email  text        primary key,
    patient_name   text        not null,
    patient_phone  text        not null,
    sent_at        timestamptz not null
);
