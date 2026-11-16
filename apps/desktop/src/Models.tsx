import { formatBytes } from './liveState';
import type { ModelState, ModelView } from './coreClient';

const PERCENT = 100;

function percent(model: ModelView): number {
  if (model.sizeBytes <= 0) return 0;
  return Math.min(PERCENT, Math.floor((model.downloadedBytes / model.sizeBytes) * PERCENT));
}

function actionLabel(state: ModelState): string {
  if (state === 'failed') return 'Retry';
  return state === 'partial' ? 'Resume' : 'Download';
}

export function Models({ models, onDownload }: { models: ModelView[]; onDownload: (id: string) => void }) {
  return (
    <section aria-label="Models">
      <h2>Models</h2>
      <p>Speech models run on this Mac. Nothing is downloaded until you ask for it.</p>
      <ul aria-label="Model catalog">
        {models.map((model) => (
          <li key={model.id}>
            <strong>{model.name}</strong> <span>{formatBytes(model.sizeBytes)}</span> <span>{model.state}</span>
            {model.state === 'downloading' && (
              <div
                role="progressbar"
                aria-label={`Download of ${model.name}`}
                aria-valuemin={0}
                aria-valuemax={PERCENT}
                aria-valuenow={percent(model)}
              >
                <div className="bar-fill" style={{ width: `${percent(model)}%` }} />
              </div>
            )}
            {model.state === 'failed' && model.error && <p role="alert">{model.error}</p>}
            {model.state !== 'installed' && (
              <button type="button" disabled={model.state === 'downloading'} onClick={() => onDownload(model.id)}>
                {actionLabel(model.state)}
              </button>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
