import type { Patient, Slot, TreatmentType } from '../../api/types';
import { price, whenLabel } from '../../lib/format';
import { SummaryRows, card, label } from '../ui';

export type FieldErrors = Partial<Record<keyof Patient, string>>;

/** The same rules the server enforces; the copy is the design's. */
export function validatePatient(p: Patient): FieldErrors {
  const errors: FieldErrors = {};
  if (p.name.trim().length < 2) errors.name = 'Please enter your full name';
  if (p.phone.replace(/\D/g, '').length < 8) errors.phone = 'Enter a phone number we can text';
  if (!/^\S+@\S+\.\S+$/.test(p.email)) errors.email = 'Enter a valid email address';
  return errors;
}

interface Props {
  treatment: TreatmentType;
  practitionerName: string;
  slot: Slot;
  patient: Patient;
  errors: FieldErrors;
  submitting: boolean;
  onChange: (next: Patient) => void;
  onSubmit: (errors: FieldErrors) => void;
}

export function DetailsScreen({ treatment, practitionerName, slot, patient, errors, submitting, onChange, onSubmit }: Props) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={card}>
        <div style={label({ marginBottom: 12 })}>Your appointment</div>
        <SummaryRows rows={[['Treatment', treatment.name], ['Practitioner', practitionerName], ['When', whenLabel(slot.startTime)], ['Duration', `${treatment.durationMinutes} min`]]} />
        <div style={{ borderTop: '1px solid #E3E9ED', marginTop: 10, paddingTop: 11, display: 'flex', justifyContent: 'space-between', fontSize: 14.5, fontWeight: 700 }}>
          <span>Total</span>
          <span style={{ color: '#0E7C7B' }}>{price(treatment.priceNok)} kr</span>
        </div>
      </div>

      <PatientFields patient={patient} errors={errors} onChange={onChange} />

      <button className="p-primary" onClick={() => onSubmit(validatePatient(patient))} disabled={submitting} style={{ width: '100%', padding: 15, border: 'none', borderRadius: 12, background: '#0E7C7B', color: '#FFF', fontSize: 15, fontWeight: 600, cursor: submitting ? 'wait' : 'pointer', boxShadow: '0 6px 16px rgba(14,124,123,.22)', opacity: submitting ? 0.8 : 1 }}>
        {submitting ? 'Booking…' : 'Confirm booking'}
      </button>
      <p style={{ margin: 0, fontSize: 11.5, lineHeight: 1.5, color: '#8A98A1', textAlign: 'center' }}>
        Free cancellation up to 24 hours before. Your slot is held while you fill this in.
      </p>
    </div>
  );
}

export function PatientFields({ patient, errors, onChange }: { patient: Patient; errors: FieldErrors; onChange: (next: Patient) => void }) {
  const fields: Array<{ key: keyof Patient; label: string; type: string; placeholder: string; mode: 'text' | 'tel' | 'email' }> = [
    { key: 'name', label: 'Full name', type: 'text', placeholder: 'Ingrid Bakken', mode: 'text' },
    { key: 'phone', label: 'Phone', type: 'tel', placeholder: '+47 913 44 208', mode: 'tel' },
    { key: 'email', label: 'Email', type: 'email', placeholder: 'ingrid.bakken@gmail.com', mode: 'email' },
  ];
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 11 }}>
      {fields.map((f) => (
        <label key={f.key} style={{ display: 'block' }}>
          <span style={{ display: 'block', fontSize: 12.5, fontWeight: 600, marginBottom: 5 }}>{f.label}</span>
          <input
            type={f.type}
            value={patient[f.key]}
            onChange={(e) => onChange({ ...patient, [f.key]: e.target.value })}
            placeholder={f.placeholder}
            inputMode={f.mode}
            aria-invalid={!!errors[f.key]}
            style={{ width: '100%', boxSizing: 'border-box', padding: '13px 14px', borderRadius: 12, border: `1px solid ${errors[f.key] ? '#B3261E' : '#D3DBE0'}`, background: '#FFF', fontSize: 14.5, color: '#1E2A32', fontFamily: 'inherit' }}
          />
          {errors[f.key] && <span style={{ display: 'block', fontSize: 11.5, color: '#B3261E', marginTop: 5, fontWeight: 500 }}>{errors[f.key]}</span>}
        </label>
      ))}
    </div>
  );
}
