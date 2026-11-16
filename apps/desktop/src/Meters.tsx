import { meterValue } from './liveState';

export type Levels = { mic: number; system: number };

function Meter({ label, rms }: { label: string; rms: number }) {
  const value = meterValue(rms);
  return (
    <div className="meter-row">
      <span>{label}</span>
      <div role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={value}>
        <div className="bar-fill" style={{ width: `${value}%` }} />
      </div>
    </div>
  );
}

export function Meters({ levels }: { levels: Levels }) {
  return (
    <div aria-label="Input levels" role="group">
      <Meter label="Me" rms={levels.mic} />
      <Meter label="Them" rms={levels.system} />
    </div>
  );
}
