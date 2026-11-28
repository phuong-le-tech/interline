import { useI18n } from './i18n/useI18n';
import { formatBytes } from './liveState';
import type { ModelState, ModelView, SystemSettings } from './coreClient';

const PERCENT = 100;

function percent(model: ModelView): number {
  if (model.sizeBytes <= 0) return 0;
  return Math.min(PERCENT, Math.floor((model.downloadedBytes / model.sizeBytes) * PERCENT));
}

function actionLabel(state: ModelState, t: ReturnType<typeof useI18n>['t']): string {
  if (state === 'failed') return t('retry');
  return state === 'partial' ? t('resume') : t('download');
}

export function Models({ models, onDownload, system }: { models: ModelView[]; onDownload: (id: string) => void; system?: SystemSettings }) {
  const { t } = useI18n();
  const speech = models.filter((model) => model.kind === 'stt').sort((a, b) => a.sizeBytes - b.sizeBytes);
  const ram = system?.ram_bytes;
  const recommended = speech.filter((model) => model.sizeBytes <= (ram ?? 0) / 16).at(-1) ?? speech[0];
  const smaller = speech[0];
  return (
    <section aria-label={t('models')}>
      <h2>{t('models')}</h2>
      <p>{t('speech_local')}</p>
      <p>{ram && recommended ? t('ram_detected', { size: formatBytes(ram), name: recommended.name }) : t('ram_unknown')}</p>
      <p>{t('background_download')}</p>
      <ul aria-label={t('model_catalog')}>
        {models.map((model) => (
          <li key={model.id}>
            <strong>{model.name}</strong> <span>{formatBytes(model.sizeBytes)}</span> <span aria-live="polite">{t(`model_${model.state}`)}</span>
            {recommended?.id === model.id && <span> - {t('model_recommended')}</span>}
            {model.state === 'downloading' && (
              <div
                role="progressbar"
                aria-label={t('download_named', { name: model.name })}
                aria-valuemin={0}
                aria-valuemax={PERCENT}
                aria-valuenow={percent(model)}
              >
                <div className="bar-fill" style={{ width: `${percent(model)}%` }} />
              </div>
            )}
            {model.state === 'failed' && model.error && <p role="alert">{model.error}</p>}
            {model.state !== 'installed' && ((system && model.sizeBytes - model.downloadedBytes > system.free_bytes)
              || /no space|disk full|not enough space/i.test(model.error ?? '')) && <p role="status">
              {system && t('disk_required', { required: formatBytes(model.sizeBytes - model.downloadedBytes), available: formatBytes(system.free_bytes) })}{' '}
              {smaller && smaller.id !== model.id && t('smaller_model', { name: smaller.name, size: formatBytes(smaller.sizeBytes) })}
            </p>}
            {model.state !== 'installed' && (
              <button type="button" disabled={model.state === 'downloading'} onClick={() => onDownload(model.id)}>
                {actionLabel(model.state, t)}
              </button>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
