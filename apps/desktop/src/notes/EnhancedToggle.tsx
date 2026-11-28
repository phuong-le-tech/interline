import { useI18n } from '../i18n/useI18n';
import React from 'react';

export type Mode = 'mine' | 'enhanced';

interface Props {
  mode: Mode;
  onChange: (mode: Mode) => void;
}

export const EnhancedToggle: React.FC<Props> = ({ mode, onChange }) => {
  const { t } = useI18n();
  return (
  <fieldset role="radiogroup" aria-label={t('version')}>
    <label>
      <input
        type="radio"
        name="version"
        value="mine"
        checked={mode === 'mine'}
        onChange={() => onChange('mine')}
      />
      {t('mine')}
    </label>
    <label>
      <input
        type="radio"
        name="version"
        value="enhanced"
        checked={mode === 'enhanced'}
        onChange={() => onChange('enhanced')}
      />
      {t('enhanced')}
    </label>
  </fieldset>
);
};
