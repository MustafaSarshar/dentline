import { Route, Routes } from 'react-router-dom';
import { ToastProvider } from '../components/Toast';
import { BookingFlow } from './BookingFlow';
import { AppointmentScreen } from './AppointmentScreen';
import { QueuedScreen } from './QueuedScreen';

/**
 * Patient side. The three-step booking flow is a step machine inside BookingFlow; the two
 * screens that are reached by link (the reminder link, the waitlist link) have their own routes.
 */
export function PatientApp() {
  return (
    <ToastProvider>
      <Routes>
        <Route path="/" element={<BookingFlow />} />
        <Route path="/appointments/:id" element={<AppointmentScreen />} />
        <Route path="/waitlist/:id" element={<QueuedScreen />} />
      </Routes>
    </ToastProvider>
  );
}

/** Passed via router state when "Reschedule" sends the patient back to the slot picker. */
export interface RescheduleIntent {
  appointmentId: string;
  reference: string;
  treatmentTypeId: string;
  practitionerId: string;
}
