import React from 'react';

interface Props {
  segmentId: number;
  label: string;
  onSelect: (segmentId: number) => void;
}

export const CitationChip: React.FC<Props> = ({ segmentId, label, onSelect }) => (
  <button
    type="button"
    className="citation-chip"
    aria-label={`Source: ${label}`}
    onClick={() => onSelect(segmentId)}
  >
    {label}
  </button>
);
