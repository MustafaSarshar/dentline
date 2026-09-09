import { Navigate, Route, Routes } from 'react-router-dom';
import { PatientApp } from './patient/PatientApp';
import { StaffApp } from './staff/StaffApp';

/** Patient booking lives at the root; the clinic dashboard under /staff. */
export function App() {
  return (
    <Routes>
      <Route path="/staff/*" element={<StaffApp />} />
      <Route path="/*" element={<PatientApp />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
