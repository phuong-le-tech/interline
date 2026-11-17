// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AiAddition } from './AiAddition';
import { CitationChip } from './CitationChip';
import { EnhancedToggle } from './EnhancedToggle';

/** AC9 (component states): citation chip, Mine/Enhanced toggle and the AI addition block. */

afterEach(cleanup);

describe('AC9_ui citation chip', () => {
  it('is a button named after its source and selects its segment when clicked', () => {
    const onSelect = vi.fn();
    render(<CitationChip segmentId={1002} label="Them 00:08" onSelect={onSelect} />);

    fireEvent.click(screen.getByRole('button', { name: 'Source: Them 00:08' }));

    expect(onSelect).toHaveBeenCalledExactlyOnceWith(1002);
  });

  it('shows the source label as its visible text', () => {
    render(<CitationChip segmentId={7} label="Me 01:30" onSelect={() => undefined} />);

    expect(screen.getByRole('button', { name: /^Source/ })).toHaveTextContent('Me 01:30');
  });
});

describe('AC9_ui version toggle', () => {
  it('offers Mine and Enhanced in a radiogroup named Version, with the current mode checked', () => {
    render(<EnhancedToggle mode="mine" onChange={() => undefined} />);

    const group = screen.getByRole('radiogroup', { name: 'Version' });
    expect(group).toContainElement(screen.getByRole('radio', { name: 'Mine' }));
    expect(screen.getByRole('radio', { name: 'Mine' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Enhanced' })).not.toBeChecked();
  });

  it('checks Enhanced when the mode is enhanced', () => {
    render(<EnhancedToggle mode="enhanced" onChange={() => undefined} />);

    expect(screen.getByRole('radio', { name: 'Enhanced' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Mine' })).not.toBeChecked();
  });

  it('reports the chosen mode', () => {
    const onChange = vi.fn();
    render(<EnhancedToggle mode="mine" onChange={onChange} />);

    fireEvent.click(screen.getByRole('radio', { name: 'Enhanced' }));

    expect(onChange).toHaveBeenCalledExactlyOnceWith('enhanced');
  });
});

describe('AC9_ui AI addition', () => {
  const sources = [{ segmentId: 1002, label: 'Them 00:08' }, { segmentId: 1003, label: 'Me 00:20' }];

  it('marks the block as AI origin with its state and id, and carries the screen reader label', () => {
    const { container } = render(<AiAddition blockId="b1" text="La finance participe." state="proposed" sources={sources} onSelectSource={() => undefined} />);

    const block = container.querySelector('[data-block-id="b1"]');
    expect(block).not.toBeNull();
    expect(block).toHaveAttribute('data-origin', 'ai');
    expect(block).toHaveAttribute('data-ai-state', 'proposed');
    expect(block).toHaveTextContent('La finance participe.');
    expect(screen.getAllByText('AI addition')).toHaveLength(1);
  });

  it.each(['proposed', 'accepted', 'edited'] as const)('exposes the %s state', (state) => {
    const { container } = render(<AiAddition blockId="b1" text="x" state={state} sources={sources} onSelectSource={() => undefined} />);

    expect(container.querySelector('[data-block-id="b1"]')).toHaveAttribute('data-ai-state', state);
  });

  it('renders one citation chip per source and reports the selected segment', () => {
    const onSelectSource = vi.fn();
    render(<AiAddition blockId="b1" text="x" state="proposed" sources={sources} onSelectSource={onSelectSource} />);

    expect(screen.getAllByRole('button', { name: /^Source/ })).toHaveLength(2);
    fireEvent.click(screen.getByRole('button', { name: 'Source: Me 00:20' }));

    expect(onSelectSource).toHaveBeenCalledExactlyOnceWith(1003);
  });
});
