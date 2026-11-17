import React from 'react';

export type Mode = 'mine' | 'enhanced';

interface Props {
  mode: Mode;
  onChange: (mode: Mode) => void;
}

export const EnhancedToggle: React.FC<Props> = ({ mode, onChange }) => (
  <fieldset role="radiogroup" aria-label="Version">
    <label>
      <input
        type="radio"
        name="version"
        value="mine"
        checked={mode === 'mine'}
        onChange={() => onChange('mine')}
      />
      Mine
    </label>
    <label>
      <input
        type="radio"
        name="version"
        value="enhanced"
        checked={mode === 'enhanced'}
        onChange={() => onChange('enhanced')}
      />
      Enhanced
    </label>
  </fieldset>
);
