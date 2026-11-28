import { useI18n } from '../i18n/useI18n';
interface Props<T> {
  segmentId: T;
  label: string;
  onSelect: (segmentId: T) => void;
}

export const CitationChip = <T extends string | number,>({ segmentId, label, onSelect }: Props<T>) => {
  const { t } = useI18n();
  return (
  <button
    type="button"
    className="citation-chip"
    aria-label={t('source_named', { name: label })}
    onClick={() => onSelect(segmentId)}
  >
    {label}
  </button>
);
};
