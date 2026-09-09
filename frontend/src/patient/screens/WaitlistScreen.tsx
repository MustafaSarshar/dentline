import type { Patient, PreferredWindow } from '../../api/types';
import { WINDOWS, WINDOW_LABEL } from '../../lib/format';
import { PrimaryButton, card } from '../ui';
import { PatientFields, validatePatient, type FieldErrors } from './DetailsScreen';

interface Props {
  treatmentName: string;
  practitionerLabel: string;
  windows: PreferredWindow[];
  onToggle: (w: PreferredWindow) => void;
  patient: Patient;
  errors: FieldErrors;
  onChange: (next: Patient) => void;
  submitting: boolean;
  onJoin: (errors: FieldErrors) => void;
}

/** The design's join screen, plus the contact fields the waitlist needs to reach the patient. */
export function WaitlistScreen({ treatmentName, practitionerLabel, windows, onToggle, patient, errors, onChange, submitting, onJoin }: Props) {
  const none = windows.length === 0;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <p style={{ margin: 0, fontSize: 13, lineHeight: 1.5, color: '#4A5A64' }}>
        Tell us when you can come in. The moment a matching appointment frees up, we hold it for you and send an offer.
      </p>
      <div style={card}>
        <div style={{ fontSize: 12.5, fontWeight: 600, marginBottom: 10 }}>Preferred time windows</div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {WINDOWS.map((w) => {
            const on = windows.includes(w);
            return (
              <button key={w} onClick={() => onToggle(w)} aria-pressed={on} style={{ padding: '9px 13px', borderRadius: 999, border: `1px solid ${on ? '#0E7C7B' : '#D3DBE0'}`, background: on ? '#0E7C7B' : '#FFF', color: on ? '#FFF' : '#1E2A32', fontSize: 12.5, fontWeight: 600, cursor: 'pointer' }}>
                {WINDOW_LABEL[w]}
              </button>
            );
          })}
        </div>
      </div>
      <div style={card}>
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 14, fontSize: 13.5, padding: '3px 0' }}>
          <span style={{ color: '#65757F' }}>Treatment</span>
          <span style={{ fontWeight: 600 }}>{treatmentName}</span>
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 14, fontSize: 13.5, padding: '6px 0 0' }}>
          <span style={{ color: '#65757F' }}>Practitioner</span>
          <span style={{ fontWeight: 600 }}>{practitionerLabel}</span>
        </div>
      </div>
      <PatientFields patient={patient} errors={errors} onChange={onChange} />
      <PrimaryButton disabled={none || submitting} onClick={() => onJoin(validatePatient(patient))}>
        {submitting ? 'Joining…' : 'Join waitlist'}
      </PrimaryButton>
    </div>
  );
}
