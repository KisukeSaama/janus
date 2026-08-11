import { useState } from 'react';
import { AlertTriangle, Check, Play, X } from 'lucide-react';
import { CopyField, Field, Sheet } from '../../components';
import { useI18n } from '../../i18n';
import { absolutePath, curlFor, probeGateway, type ProbeResult } from '../../lib/connections';

import type { NewConnection } from './ConnectFlow';

/**
 * The screen a developer actually needed: the key, the identifier, a request that works as pasted,
 * and a button that runs it for real.
 *
 * A setup wizard that ends on "created successfully" leaves the hardest question unanswered, which
 * is whether the thing works. This one answers it from the console, with the key it has held for
 * exactly one moment, and it tells apart the two failures that look identical from the outside:
 * Janus refusing, and the API refusing.
 */
export function ConnectionReady({ connection, onDismiss }: { connection: NewConnection; onDismiss: () => void }) {
  const { t } = useI18n();
  const curl = curlFor(
    connection.slug,
    '/',
    connection.applicationId,
    // Reusing a service means its key was shown once, long ago: the command carries a variable
    // rather than a value nobody in this console can supply.
    connection.key || '$JANUS_API_KEY',
  );

  return (
    <Sheet
      label={t('ready.title', { name: connection.apiName })}
      footer={
        <button className="btn btn-primary ml-auto min-w-[12rem]" onClick={onDismiss} autoFocus>
          {t('ready.done')}
        </button>
      }
    >
      <div className="space-y-6">
        <div>
          {connection.key && <p className="stamp text-accent-text">{t('ready.stamp')}</p>}
          <h1 className="mt-2.5 text-2xl font-semibold tracking-title">
            {t('ready.title', { name: connection.apiName })}
          </h1>
          {connection.key && <p className="mt-2 max-w-[64ch] text-text-2">{t('ready.lead')}</p>}
        </div>

        <div className="space-y-4">
          {connection.key && <CopyField label={t('ready.key')} value={connection.key} />}
          <CopyField label={t('ready.id')} value={connection.applicationId} />
          <div>
            <CopyField label={t('ready.call')} value={curl} block />
            <p className="mt-1.5 text-xs text-text-3">{t('ready.callHint')}</p>
          </div>
        </div>

        {connection.key && (
          <Probe slug={connection.slug} applicationId={connection.applicationId} apiKey={connection.key} />
        )}
      </div>
    </Sheet>
  );
}

/* ── Proving it, from here ─────────────────────────────────────────────── */

type Verdict = { tone: 'ok' | 'warn' | 'bad'; title: string; hint?: string };

/** A GET, because it is the one method that proves the hop without changing anything upstream. */
function Probe({ slug, applicationId, apiKey }: { slug: string; applicationId: string; apiKey: string }) {
  const { t } = useI18n();
  const [path, setPath] = useState('/');
  const [sending, setSending] = useState(false);
  const [result, setResult] = useState<ProbeResult | null>(null);

  async function send() {
    setSending(true);
    try {
      setResult(await probeGateway(slug, absolutePath(path) || '/', applicationId, apiKey, 'GET'));
    } finally {
      setSending(false);
    }
  }

  const verdict: Verdict | null = !result
    ? null
    : result.verdict === 'unreachable'
      ? { tone: 'bad', title: t('probe.unreachable'), hint: t('probe.unreachableHint') }
      : result.verdict === 'refused'
        ? { tone: 'bad', title: t('probe.refused'), hint: result.detail }
        : result.status < 400
          ? { tone: 'ok', title: t('probe.forwarded'), hint: t('probe.forwardedHint', { status: result.status }) }
          : { tone: 'warn', title: t('probe.upstream'), hint: t('probe.upstreamHint', { status: result.status }) };

  return (
    <section className="panel px-4 py-4 md:px-5 md:py-5">
      <h2 className="text-base font-semibold">{t('probe.title')}</h2>
      <p className="mt-1.5 max-w-[64ch] text-sm text-text-2">{t('probe.lead')}</p>

      <div className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-end">
        <div className="min-w-0 flex-1">
          <Field
            label={t('probe.path')}
            data
            autoComplete="off"
            value={path}
            onChange={(e) => setPath(e.target.value)}
          />
        </div>
        <button className="btn btn-secondary shrink-0" onClick={() => void send()} disabled={sending}>
          <Play size={14} strokeWidth={2.25} />
          {sending ? t('probe.sending') : result ? t('probe.again') : t('probe.send')}
        </button>
      </div>

      {verdict && result && (
        <div
          aria-live="polite"
          className={`mt-4 rounded-panel border px-3.5 py-3 ${
            verdict.tone === 'ok'
              ? 'border-ok/45 bg-ok-wash'
              : verdict.tone === 'warn'
                ? 'border-warn/45 bg-warn-wash'
                : 'border-bad/40 bg-bad-wash'
          }`}
        >
          <p className="flex items-center gap-2 text-sm font-medium">
            <ToneIcon tone={verdict.tone} />
            {verdict.title}
            <span className="data ml-auto text-xs text-text-2">{t('probe.took', { millis: result.millis })}</span>
          </p>
          {verdict.hint && <p className="mt-1.5 max-w-[64ch] text-sm text-text-2">{verdict.hint}</p>}
          <p className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-2xs text-text-3">
            {result.cache && <span className="data">{t('probe.cache', { value: result.cache })}</span>}
            {result.correlationId && (
              <span className="data">{t('probe.correlation', { id: result.correlationId.slice(0, 12) })}</span>
            )}
          </p>
        </div>
      )}
    </section>
  );
}

function ToneIcon({ tone }: { tone: 'ok' | 'warn' | 'bad' }) {
  const Icon = tone === 'ok' ? Check : tone === 'warn' ? AlertTriangle : X;
  const color = tone === 'ok' ? 'text-ok' : tone === 'warn' ? 'text-warn' : 'text-bad';
  return <Icon size={16} strokeWidth={2.5} className={`shrink-0 ${color}`} />;
}
