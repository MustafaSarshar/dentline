-- Notification-service schema. Delivery is simulated: each row is the message that would have
-- been sent by SMS/email. The clinic view reads them through GET /api/notifications.

create table notification (
    id                 uuid primary key,
    -- The Kafka event that produced this row. Unique so redelivered events are ignored (idempotent consumer).
    source_event_id    uuid        not null unique,
    type               text        not null check (type in (
                           'BOOKING_RECEIVED', 'BOOKING_CONFIRMED', 'BOOKING_RESCHEDULED', 'BOOKING_CANCELLED',
                           'REMINDER', 'RECALL', 'WAITLIST_OFFER', 'WAITLIST_OFFER_EXPIRED')),
    channels           text        not null, -- comma-separated subset of SMS,EMAIL
    recipient_name     text        not null,
    recipient_phone    text        not null,
    recipient_email    text        not null,
    subject            text        not null,
    body               text        not null,
    appointment_id     uuid,
    waitlist_entry_id  uuid,
    created_at         timestamptz not null
);
create index notification_appointment_idx on notification (appointment_id, created_at desc);
create index notification_waitlist_entry_idx on notification (waitlist_entry_id, created_at desc);
create index notification_recipient_email_idx on notification (lower(recipient_email), created_at desc);
create index notification_created_idx on notification (created_at desc);
