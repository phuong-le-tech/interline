import { useI18n } from '../i18n/useI18n';
import React from 'react';
import { CitationChip } from './CitationChip';

export type AiState = 'proposed' | 'accepted' | 'edited';

interface Source {
  segmentId: number;
  label: string;
}

interface Props {
  blockId: string;
  text: string;
  state: AiState;
  sources: Source[];
  onSelectSource: (segmentId: number) => void;
  onAccept?: () => void;
  onDelete?: () => void;
}

export const AiAddition: React.FC<Props> = ({ blockId, text, state, sources, onSelectSource, onAccept, onDelete }) => {
  const { t } = useI18n();
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      e.preventDefault();
      onAccept?.();
    } else if (e.key === 'Backspace') {
      e.preventDefault();
      onDelete?.();
    }
  };

  return (
    <div
      data-block-id={blockId}
      data-origin="ai"
      data-ai-state={state}
      className="ai-addition"
      tabIndex={0}
      onKeyDown={handleKeyDown}
    >
      <span className="sr-only">{t('ai_addition')}</span>
      <p>{text}</p>
      {sources.map((s) => (
        <CitationChip key={s.segmentId} segmentId={s.segmentId} label={s.label} onSelect={onSelectSource} />
      ))}
    </div>
  );
};
