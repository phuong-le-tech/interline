import { useI18n } from './i18n/useI18n';
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
  const { t } = useI18n();
  return (
    <div aria-label={t('input_levels')} role="group">
      <Meter label={t('me')} rms={levels.mic} />
      <Meter label={t('them')} rms={levels.system} />
    </div>
  );
}
