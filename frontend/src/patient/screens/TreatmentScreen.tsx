import type { TreatmentType } from '../../api/types';
import { price } from '../../lib/format';

export function TreatmentScreen({ treatments, onPick }: { treatments: TreatmentType[]; onPick: (t: TreatmentType) => void }) {
  return (
    <div data-stagger="1" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <p style={{ margin: '2px 0 8px', fontSize: 13, lineHeight: 1.5, color: '#4A5A64' }}>
        Book in three steps. Choose what you need and we will show real openings from our practitioners' hours.
      </p>
      {treatments.map((t) => (
        <button key={t.id} className="p-card" onClick={() => onPick(t)} style={{ textAlign: 'left', display: 'flex', alignItems: 'center', gap: 14, width: '100%', background: '#FFF', border: '1px solid #E3E9ED', borderRadius: 12, padding: '14px 15px', cursor: 'pointer', boxShadow: '0 1px 2px rgba(30,42,50,.05)' }}>
          <span style={{ width: 40, height: 40, flex: 'none', borderRadius: 12, background: '#D6F2F0', display: 'grid', placeItems: 'center', fontSize: 13, fontWeight: 700, color: '#0E7C7B' }}>{t.code}</span>
          <span style={{ flex: 1, minWidth: 0 }}>
            <span style={{ display: 'block', fontSize: 14.5, fontWeight: 600 }}>{t.name}</span>
            <span style={{ display: 'block', fontSize: 12.5, color: '#65757F', marginTop: 2 }}>{t.durationMinutes} min</span>
          </span>
          <span style={{ fontSize: 14, fontWeight: 600, color: '#0E7C7B', flex: 'none' }}>{price(t.priceNok)} kr</span>
        </button>
      ))}
    </div>
  );
}
