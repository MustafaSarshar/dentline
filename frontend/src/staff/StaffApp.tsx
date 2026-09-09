import { useMemo, useState } from 'react';
import { NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useConfig, useDaySchedule, useRecalls, useStaffWaitlist } from '../api/hooks';
import type { AppointmentStatus } from '../api/types';
import { ToastProvider, useToastMessage } from '../components/Toast';
import { addDays, isoWeek, longDate, mondayOf, parseDate, todayIn } from '../lib/format';
import { STATUS, STATUS_ORDER } from '../lib/status';
import { AppointmentDrawer } from './AppointmentDrawer';
import { MetricsStrip } from './MetricsStrip';
import { RecallsView } from './RecallsView';
import { TodayView } from './TodayView';
import { WaitlistView } from './WaitlistView';
import { WeekView } from './WeekView';

const MON_LONG = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

/** Optimistic status overrides keyed by appointment id, rolled back on 409 (see AppointmentDrawer). */
export type StatusOverrides = Record<string, AppointmentStatus>;

export function StaffApp() {
  return (
    <ToastProvider>
      <StaffShell />
    </ToastProvider>
  );
}

function StaffShell() {
  const location = useLocation();
  const config = useConfig();
  const timezone = config.data?.timezone ?? 'Europe/Oslo';
  const today = useMemo(() => todayIn(timezone), [timezone]);
  const monday = mondayOf(today);
  const friday = addDays(monday, 4);

  const day = useDaySchedule(today);
  const waitlist = useStaffWaitlist();
  const recalls = useRecalls();
  const toast = useToastMessage();

  const [selected, setSelected] = useState<string | null>(null);
  const [overrides, setOverrides] = useState<StatusOverrides>({});

  const view = location.pathname.replace(/^\/staff\/?/, '') || 'today';
  const todayCount = day.data ? day.data.practitioners.reduce((n, p) => n + p.appointments.length, 0) : undefined;
  const queued = waitlist.data?.filter((w) => w.status !== 'ACCEPTED' && w.status !== 'REMOVED').length;
  const clinicOpen = day.data?.clinicOpen;
  const weekStart = parseDate(monday);
  const weekEnd = parseDate(friday);
  const weekRange = weekStart.getMonth() === weekEnd.getMonth()
    ? `${weekStart.getDate()}–${weekEnd.getDate()} ${MON_LONG[weekStart.getMonth()]}`
    : `${weekStart.getDate()} ${MON_LONG[weekStart.getMonth()]} – ${weekEnd.getDate()} ${MON_LONG[weekEnd.getMonth()]}`;

  const headers: Record<string, [string, string]> = {
    today: [`Today · ${longDate(today)}`, `${todayCount ?? '…'} appointments · ${day.data?.practitioners.length ?? 3} practitioners · clinic open ${clinicOpen ? `${clinicOpen.startTime}–${clinicOpen.endTime}` : '…'}`],
    week: [`Week ${isoWeek(today)} · ${weekRange}`, 'Read-only overview. Click any appointment for details.'],
    waitlist: ['Waitlist', `${queued ?? '…'} patients queued · offers are held for ${config.data?.offerHoldMinutes ?? 15} minutes before passing on`],
    recalls: ['Recalls', '6-month check-up recalls · the scheduled job publishes recall events at 06:00 daily'],
  };
  const [title, sub] = headers[view] ?? headers.today;

  const nav = [
    { key: 'today', label: 'Today', to: '/staff', count: todayCount },
    { key: 'week', label: 'Week', to: '/staff/week', count: undefined },
    { key: 'waitlist', label: 'Waitlist', to: '/staff/waitlist', count: waitlist.data?.length },
    { key: 'recalls', label: 'Recalls', to: '/staff/recalls', count: recalls.data?.length },
  ];

  return (
    <div className="staff-app" style={{ minHeight: '100vh', display: 'flex', color: '#1E2A32', position: 'relative' }}>
      <div style={{ width: 224, flex: 'none', borderRight: '1px solid #E3E9ED', background: '#FFF', padding: '22px 14px', display: 'flex', flexDirection: 'column', gap: 26, minHeight: '100vh', boxSizing: 'border-box' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '0 6px' }}>
          <span style={{ width: 30, height: 30, borderRadius: 9, background: '#0E7C7B', display: 'grid', placeItems: 'center', color: '#FFF', fontSize: 13, fontWeight: 700 }}>D</span>
          <span style={{ fontSize: 15, fontWeight: 700, letterSpacing: '-.01em' }}>Dentline</span>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
          <div style={{ fontSize: 10.5, fontWeight: 600, letterSpacing: '.09em', textTransform: 'uppercase', color: '#8A98A1', padding: '0 8px 7px' }}>Schedule</div>
          {nav.map((n) => {
            const current = view === n.key;
            return (
              <NavLink key={n.key} to={n.to} end className="s-nav" aria-current={current ? 'page' : undefined} onClick={() => setSelected(null)} style={{ display: 'flex', alignItems: 'center', gap: 10, textAlign: 'left', padding: '9px 10px', border: 'none', borderRadius: 10, background: current ? '#D6F2F0' : 'transparent', color: current ? '#0B5654' : '#1E2A32', fontSize: 13.5, fontWeight: current ? 600 : 500, cursor: 'pointer', textDecoration: 'none' }}>
                <span style={{ width: 6, height: 6, borderRadius: '50%', background: current ? '#0E7C7B' : '#C3CDD3', flex: 'none' }} />
                <span style={{ flex: 1 }}>{n.label}</span>
                {n.count !== undefined && n.count > 0 && (
                  <span style={{ fontSize: 11, fontWeight: 600, color: '#65757F', background: '#F2F6F8', borderRadius: 6, padding: '2px 6px' }}>{n.count}</span>
                )}
              </NavLink>
            );
          })}
        </div>
        <div style={{ marginTop: 'auto', display: 'flex', alignItems: 'center', gap: 10, padding: 10, borderTop: '1px solid #E3E9ED' }}>
          <span style={{ width: 32, height: 32, borderRadius: '50%', background: '#D6F2F0', display: 'grid', placeItems: 'center', fontSize: 12, fontWeight: 700, color: '#0E7C7B' }}>SK</span>
          <span style={{ lineHeight: 1.3 }}>
            <span style={{ display: 'block', fontSize: 12.5, fontWeight: 600 }}>Silje Kvam</span>
            <span style={{ display: 'block', fontSize: 11, color: '#65757F' }}>Front desk</span>
          </span>
        </div>
      </div>

      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 20, padding: '22px 28px 16px', borderBottom: '1px solid #E3E9ED', flexWrap: 'wrap' }}>
          <div>
            <div style={{ fontSize: 21, fontWeight: 700, letterSpacing: '-.02em' }}>{title}</div>
            <div style={{ fontSize: 13, color: '#65757F', marginTop: 3 }}>{sub}</div>
          </div>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            {STATUS_ORDER.map((k) => (
              <span key={k} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 11.5, color: '#4A5A64', padding: '5px 9px', border: '1px solid #E3E9ED', borderRadius: 8, background: '#FFF' }}>
                <span style={{ width: 9, height: 9, borderRadius: 3, background: STATUS[k].bg, border: `1.5px solid ${STATUS[k].border}` }} />
                {STATUS[k].label}
              </span>
            ))}
          </div>
        </div>

        <MetricsStrip />

        <div style={{ padding: '18px 28px 34px', flex: 1, minWidth: 0 }}>
          <Routes>
            <Route index element={<TodayView date={today} timezone={timezone} overrides={overrides} onOpen={setSelected} />} />
            <Route path="week" element={<WeekView monday={monday} today={today} overrides={overrides} onOpen={setSelected} />} />
            <Route path="waitlist" element={<WaitlistView onOpenBooking={setSelected} />} />
            <Route path="recalls" element={<RecallsView />} />
            <Route path="*" element={<Navigate to="/staff" replace />} />
          </Routes>
        </div>
      </div>

      {selected && (
        <AppointmentDrawer appointmentId={selected} overrides={overrides} setOverrides={setOverrides} onClose={() => setSelected(null)} />
      )}

      {toast && (
        <div role="status" style={{ position: 'fixed', left: '50%', bottom: 26, transform: 'translateX(-50%)', background: '#1E2A32', color: '#FFF', padding: '13px 18px', borderRadius: 12, fontSize: 13, fontWeight: 500, display: 'flex', gap: 10, alignItems: 'center', animation: 'dl-up-s .22s ease', boxShadow: '0 12px 30px rgba(30,42,50,.26)', zIndex: 40 }}>
          <span style={{ color: '#8FE3DF' }}>✓</span>
          {toast}
        </div>
      )}
    </div>
  );
}
