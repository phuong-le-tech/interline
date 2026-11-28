import { useI18n } from '../i18n/useI18n';
import { trapDialogFocus } from '../dialogFocus';
import { useEffect, useRef, useState } from 'react';
import { CoreClient, type SearchHit } from '../coreClient';

interface Props {
  client: CoreClient;
  onClose: () => void;
  onOpenMeeting: (meetingId: string, segmentId?: number) => void;
  onOpenSettings?: () => void;
}

export function Palette({ client, onClose, onOpenMeeting, onOpenSettings }: Props) {
  const { t } = useI18n();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchHit[]>([]);
  const [selected, setSelected] = useState(-1);
  const inputRef = useRef<HTMLInputElement>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const previous = document.activeElement;
    const dialog = dialogRef.current!;
    dialog.showModal();
    inputRef.current?.focus();
    return () => { dialog.close(); if (previous instanceof HTMLElement && previous.isConnected) previous.focus(); };
  }, []);

  useEffect(() => {
    if (query.length < 2) {
      setResults([]);
      return;
    }
    const timer = setTimeout(() => {
      void client.search(query).then((r) => setResults(r.results)).catch(() => setResults([]));
    }, 150);
    return () => clearTimeout(timer);
  }, [query, client]);

  const openSelected = () => {
    const hit = results[selected];
    if (!hit) return;
    const segmentId = hit.type === 'segment' ? Number(hit.id) : undefined;
    onOpenMeeting(hit.meeting_id, segmentId);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setSelected((i) => Math.min(i + 1, results.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setSelected((i) => Math.max(i - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      openSelected();
    } else if (e.key === 'Escape') {
      onClose();
    }
  };

  return (
    <dialog ref={dialogRef} className="palette-dialog" aria-modal="true" aria-label={t('search')} onKeyDown={trapDialogFocus} onCancel={(event) => { event.preventDefault(); onClose(); }}>
      <div className="palette" onClick={(e) => e.stopPropagation()}>
        <input
          ref={inputRef}
          type="text"
          role="combobox"
          aria-expanded={results.length > 0}
          aria-activedescendant={selected >= 0 ? `search-result-${selected}` : undefined}
          placeholder={t('search_placeholder')}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={handleKeyDown}
          aria-label={t('search_label')}
        />
        <div className="settings-actions">
          {onOpenSettings && <button type="button" onClick={onOpenSettings}>{t('settings')}</button>}
          <button type="button" onClick={onClose}>{t('dismiss')}</button>
        </div>
        {query.length >= 2 && results.length === 0 && <p>{t('no_results')}</p>}
        <p className="sr-only" aria-live="polite">{results.length} {t('search_results')}</p>
        <ul role="listbox" aria-label={t('search_results')}>
          {results.map((hit, index) => (
            <li
              key={`${hit.type}-${hit.id}`}
              id={`search-result-${index}`}
              role="option"
              aria-selected={index === selected}
            >
              <button
                type="button"
                onClick={() => {
                  const segmentId = hit.type === 'segment' ? Number(hit.id) : undefined;
                  onOpenMeeting(hit.meeting_id, segmentId);
                }}
              >
                {hit.snippet.replace(/[«»]/g, '')}
              </button>
            </li>
          ))}
        </ul>
      </div>
    </dialog>
  );
}
