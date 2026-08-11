import { usePurgeGatewayCache, useTraffic } from '../../api';
import { ArmedAction, PageHead, SectionHead } from '../../components';
import { useI18n } from '../../i18n';

import { AuditLog } from './AuditLog';

/**
 * The two halves of one question. The log says what the gateway decided; the traffic figures say
 * what it absorbed so that no caller had to implement caching, backoff, or rate limiting itself.
 */
export function ActivityPage() {
  const { t } = useI18n();
  return (
    <>
      <PageHead section={t('nav.console')} title={t('activity.title')} intro={t('activity.lead')} />
      <TrafficBand />
      <AuditLog />
    </>
  );
}

function TrafficBand() {
  const { t, formatNumber, formatTime } = useI18n();
  const traffic = useTraffic();
  const purge = usePurgeGatewayCache();

  // A missing figure is not worth an error banner over the whole page.
  if (traffic.isError) return null;

  // The band keeps its height while the figures are being fetched. The log below is the reason the
  // page was opened, and it should not slide down once a second request lands.
  if (traffic.isPending) {
    return (
      <section className="mb-8" aria-hidden="true">
        <SectionHead>{t('traffic.title')}</SectionHead>
        <div className="panel">
          <div className="grid divide-line sm:grid-cols-2 sm:divide-x">
            <div className="px-4 py-3">
              <div className="skeleton h-5" />
            </div>
            <div className="border-t border-line px-4 py-3 sm:border-t-0">
              <div className="skeleton h-5" />
            </div>
          </div>
        </div>
      </section>
    );
  }

  const { cache, cooldowns } = traffic.data;
  const spared = ['HIT', 'STALE', 'REVALIDATED', 'COALESCED'].reduce(
    (sum, key) => sum + (cache.outcomes[key] ?? 0),
    0,
  );

  return (
    <section className="mb-8">
      <SectionHead
        aside={
          cache.enabled && cache.entries > 0 ? (
            <ArmedAction
              trigger={t('traffic.purgeAll')}
              confirm={t('traffic.purgeAllConfirm')}
              pending={t('traffic.purgeAllPending')}
              description={t('traffic.purgeAllDescription')}
              onConfirm={async () => {
                await purge.mutateAsync();
              }}
            />
          ) : undefined
        }
      >
        {t('traffic.title')}
      </SectionHead>

      {!cache.enabled ? (
        <p className="panel px-4 py-5 text-center text-sm text-text-2">{t('traffic.disabled')}</p>
      ) : (
        <div className="panel">
          <dl className="grid divide-line sm:grid-cols-2 sm:divide-x">
            <div className="flex items-baseline justify-between gap-4 px-4 py-3">
              <dt className="stamp text-text-2">{t('traffic.spared')}</dt>
              <dd className="text-right">
                <span className="num font-semibold">{formatNumber(spared)}</span>
                <span className="ml-2 text-xs text-text-2">
                  {t('traffic.spareRatio', { percent: Math.round(cache.hitRatio * 100) })}
                </span>
              </dd>
            </div>
            <div className="flex items-baseline justify-between gap-4 border-t border-line px-4 py-3 sm:border-t-0">
              <dt className="stamp text-text-2">{t('traffic.stored')}</dt>
              <dd className="num">
                {t('traffic.storedValue', {
                  entries: formatNumber(cache.entries),
                  max: formatNumber(cache.maxEntries),
                })}
              </dd>
            </div>
          </dl>

          {cooldowns.length > 0 && (
            <div className="border-t border-line px-4 py-3">
              <p className="stamp text-warn">{t('traffic.cooldowns')}</p>
              <ul className="mt-1.5 space-y-1 text-xs text-text-2">
                {cooldowns.map((pause) => (
                  <li key={pause.providerId}>
                    {t('traffic.cooldownRow', {
                      name: pause.providerName ?? pause.providerSlug ?? pause.providerId,
                      status: pause.status,
                      time: formatTime(pause.until),
                    })}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </section>
  );
}
