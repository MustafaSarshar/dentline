-- Static reference data from the design handoff. Fixed UUIDs so seeds and tests can refer to them.
-- Date-relative demo data (appointments, waitlist, offers) is created by the startup seeder instead,
-- because a migration cannot express "today" or "an offer that expires in 12 minutes".

insert into treatment_type (id, code, name, duration_minutes, price_nok) values
    ('10000000-0000-0000-0000-000000000001', 'CU', 'Check-up',   30,  890.00),
    ('10000000-0000-0000-0000-000000000002', 'CL', 'Cleaning',   45, 1190.00),
    ('10000000-0000-0000-0000-000000000003', 'FI', 'Filling',    60, 1750.00),
    ('10000000-0000-0000-0000-000000000004', 'RC', 'Root canal', 90, 4500.00),
    ('10000000-0000-0000-0000-000000000005', 'WH', 'Whitening',  60, 3200.00);

insert into practitioner (id, name, title) values
    ('20000000-0000-0000-0000-000000000001', 'Dr. Astrid Nordvik', 'DENTIST'),
    ('20000000-0000-0000-0000-000000000002', 'Dr. Henrik Sæther',  'DENTIST'),
    ('20000000-0000-0000-0000-000000000003', 'Mari Lund',          'HYGIENIST');

-- Nordvik: Mon–Fri 08:00–11:30 and 12:15–15:30
insert into working_hours (id, practitioner_id, day_of_week, start_time, end_time)
select gen_random_uuid(), '20000000-0000-0000-0000-000000000001', d, w.s, w.e
from generate_series(1, 5) as d
cross join (values ('08:00'::time, '11:30'::time), ('12:15'::time, '15:30'::time)) as w(s, e);

-- Sæther: Mon–Thu 10:00–13:00 and 13:45–18:00
insert into working_hours (id, practitioner_id, day_of_week, start_time, end_time)
select gen_random_uuid(), '20000000-0000-0000-0000-000000000002', d, w.s, w.e
from generate_series(1, 4) as d
cross join (values ('10:00'::time, '13:00'::time), ('13:45'::time, '18:00'::time)) as w(s, e);

-- Lund: Mon, Tue, Thu, Fri 08:00–11:30 and 12:15–15:30
insert into working_hours (id, practitioner_id, day_of_week, start_time, end_time)
select gen_random_uuid(), '20000000-0000-0000-0000-000000000003', d, w.s, w.e
from unnest(array[1, 2, 4, 5]) as d
cross join (values ('08:00'::time, '11:30'::time), ('12:15'::time, '15:30'::time)) as w(s, e);
