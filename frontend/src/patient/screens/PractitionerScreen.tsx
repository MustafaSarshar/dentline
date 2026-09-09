import type { Practitioner } from '../../api/types';
import { hoursLabel, initials, titleLabel } from '../../lib/format';
import { Avatar, label } from '../ui';

export function PractitionerScreen({ practitioners, onPick }: { practitioners: Practitioner[]; onPick: (p: Practitioner | null) => void }) {
  return (
    <div data-stagger="1" style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <button className="p-primary" onClick={() => onPick(null)} style={{ textAlign: 'left', display: 'flex', alignItems: 'center', gap: 14, background: '#0E7C7B', border: '1px solid #0E7C7B', borderRadius: 12, padding: 15, cursor: 'pointer', color: '#FFF' }}>
        <span style={{ width: 42, height: 42, flex: 'none', borderRadius: '50%', background: 'rgba(255,255,255,.18)', display: 'grid', placeItems: 'center', fontSize: 17 }}>✦</span>
        <span style={{ flex: 1 }}>
          <span style={{ display: 'block', fontSize: 14.5, fontWeight: 600 }}>First available</span>
          <span style={{ display: 'block', fontSize: 12.5, opacity: 0.85, marginTop: 2 }}>Earliest opening across the clinic</span>
        </span>
      </button>
      <div style={label({ margin: '8px 0 -2px' })}>Or choose a practitioner</div>
      {practitioners.map((p) => (
        <button key={p.id} className="p-card" onClick={() => onPick(p)} style={{ textAlign: 'left', display: 'flex', alignItems: 'center', gap: 14, background: '#FFF', border: '1px solid #E3E9ED', borderRadius: 12, padding: '13px 15px', cursor: 'pointer', boxShadow: '0 1px 2px rgba(30,42,50,.05)' }}>
          <Avatar text={initials(p.name)} />
          <span style={{ flex: 1, minWidth: 0 }}>
            <span style={{ display: 'block', fontSize: 14.5, fontWeight: 600 }}>{p.name}</span>
            <span style={{ display: 'block', fontSize: 12.5, color: '#65757F', marginTop: 2 }}>{titleLabel(p.title)} · {hoursLabel(p.workingHours)}</span>
          </span>
          <span style={{ color: '#65757F', flex: 'none' }}>›</span>
        </button>
      ))}
    </div>
  );
}
