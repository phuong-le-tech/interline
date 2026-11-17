/* eslint-disable @typescript-eslint/no-unused-vars -- skeleton: parameters are used once the code-writer implements it */
// M2 skeleton. Behaviour is specified by components.test.tsx (AC9).

export type AiState = 'proposed' | 'accepted' | 'edited';

export type AiAdditionProps = {
  blockId: string;
  text: string;
  state: AiState;
  sources: { segmentId: number; label: string }[];
  onSelectSource: (segmentId: number) => void;
};

export function AiAddition(_props: AiAdditionProps) {
  return null;
}
