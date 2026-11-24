interface Props<T> {
  segmentId: T;
  label: string;
  onSelect: (segmentId: T) => void;
}

export const CitationChip = <T extends string | number,>({ segmentId, label, onSelect }: Props<T>) => (
  <button
    type="button"
    className="citation-chip"
    aria-label={`Source: ${label}`}
    onClick={() => onSelect(segmentId)}
  >
    {label}
  </button>
);
