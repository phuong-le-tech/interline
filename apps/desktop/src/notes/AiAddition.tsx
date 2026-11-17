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
}

export const AiAddition: React.FC<Props> = ({ blockId, text, state, sources, onSelectSource }) => (
  <div data-block-id={blockId} data-origin="ai" data-ai-state={state} className="ai-addition">
    <span className="sr-only">AI addition</span>
    <p>{text}</p>
    {sources.map((s) => (
      <CitationChip key={s.segmentId} segmentId={s.segmentId} label={s.label} onSelect={onSelectSource} />
    ))}
  </div>
);
