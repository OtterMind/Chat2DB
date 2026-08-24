import { useEffect, useState } from 'react'
import { Cpu, Gauge, Loader2, Download, CheckCircle2, AlertTriangle } from 'lucide-react'
import { Card } from './Page'
import api from '../api/client'
import { useI18n } from '../i18n'

/**
 * The graphics card, and what it is really doing for you.
 *
 * Every line here comes from a probe on this machine, not from a capability
 * list: the encoder is asked to encode a frame, the decoder to decode a file,
 * and the benchmark encodes the same clip both ways and times them. A claim
 * about a graphics card that was not measured on the machine it runs on is a
 * brochure.
 */
interface GpuStatus {
  name: string | null
  memoryMb: number | null
  driver: string | null
  encode: boolean
  decode: boolean
  whisperDevice: string
  whisperDetail: string
  notes: string[]
  used: string[]
}

interface CudaStatus {
  card: string | null
  device: string
  detail: string
  librariesInstalled: boolean
  downloadMb: number
  canInstall: boolean
}

const SLOW = { timeout: 60 * 60_000 }

export default function GpuCard() {
  const { t } = useI18n()
  const [status, setStatus] = useState<GpuStatus | null>(null)
  const [cuda, setCuda] = useState<CudaStatus | null>(null)
  const [busy, setBusy] = useState<'check' | 'bench' | 'install' | null>(null)
  const [bench, setBench] = useState<{ cpu: number | null; gpu: number | null; speedup?: number } | null>(null)

  const load = async (deep = false) => {
    const [gpu, cudaState] = await Promise.all([
      api.get(`/gpu/status${deep ? '?deep=true' : ''}`, { timeout: 120_000 }).then((r) => r.data),
      api.get('/ai/cuda/status', { timeout: 120_000 }).then((r) => r.data).catch(() => null),
    ])
    setStatus(gpu)
    setCuda(cudaState)
  }

  useEffect(() => {
    void load().catch(() => undefined)
  }, [])

  const check = async () => {
    setBusy('check')
    try { await load(true) } finally { setBusy(null) }
  }

  const measure = async () => {
    setBusy('bench')
    try {
      const result = await api.post('/gpu/benchmark', { seconds: 5, width: 1920, height: 1080 }, SLOW)
      setBench(result.data)
    } finally { setBusy(null) }
  }

  const installCuda = async () => {
    setBusy('install')
    try {
      await api.post('/ai/cuda/install', {}, SLOW)
      await load(true)
    } finally { setBusy(null) }
  }

  const yes = (value: boolean) => (value ? t('yes', 'بله') : t('no', 'خیر'))

  return (
    <Card title={t('Graphics card', 'کارت گرافیک')} testId="gpu-card">
      {!status ? (
        <p className="ce-hint"><Loader2 size={14} className="ce-spin" /> {t('Checking…', 'در حال بررسی…')}</p>
      ) : (
        <>
          <div className="ce-kv">
            <span><Cpu size={14} /> {t('Card', 'کارت')}</span>
            <strong dir="ltr" data-testid="gpu-name">
              {status.name ?? t('none detected', 'پیدا نشد')}
              {status.memoryMb ? ` · ${(status.memoryMb / 1024).toFixed(0)} GB` : ''}
              {status.driver ? ` · ${status.driver}` : ''}
            </strong>
          </div>
          <div className="ce-kv">
            <span>{t('Encoding on the card', 'انکود روی کارت')}</span>
            <strong data-state={status.encode ? 'working' : 'missing'}>{yes(status.encode)}</strong>
          </div>
          <div className="ce-kv">
            <span>{t('Decoding on the card', 'دیکود روی کارت')}</span>
            <strong data-state={status.decode ? 'working' : 'missing'}>{yes(status.decode)}</strong>
          </div>
          <div className="ce-kv">
            <span>{t('Speech recognition', 'تشخیص گفتار')}</span>
            <strong data-state={status.whisperDevice === 'cuda' ? 'working' : 'idle'} dir="ltr">
              {status.whisperDevice}{status.whisperDetail ? ` — ${status.whisperDetail}` : ''}
            </strong>
          </div>

          {status.used.length > 0 && (
            <div className="ce-kv">
              <span>{t('Used for', 'استفاده می‌شود برای')}</span>
              <strong>{status.used.join(' · ')}</strong>
            </div>
          )}

          {status.notes.map((note, index) => (
            <p key={index} className="ce-hint"><AlertTriangle size={13} /> {note}</p>
          ))}

          {bench && (
            <div className="ce-kv" data-testid="gpu-bench">
              <span><Gauge size={14} /> {t('5 s of 1080p', '۵ ثانیه ۱۰۸۰p')}</span>
              <strong dir="ltr">
                {t('processor', 'پردازنده')} {bench.cpu ?? '—'}s
                {bench.gpu != null && ` · ${t('card', 'کارت')} ${bench.gpu}s`}
                {bench.speedup != null && ` · ×${bench.speedup}`}
              </strong>
            </div>
          )}

          <div className="ce-actions" style={{ marginTop: 12 }}>
            <button className="ce-btn ce-btn--ghost ce-btn--sm" disabled={busy !== null} onClick={() => void check()}>
              {busy === 'check' ? <Loader2 size={15} className="ce-spin" /> : <CheckCircle2 size={15} />}
              {t('Check everything', 'همه را بررسی کن')}
            </button>
            <button className="ce-btn ce-btn--ghost ce-btn--sm" disabled={busy !== null} onClick={() => void measure()}>
              {busy === 'bench' ? <Loader2 size={15} className="ce-spin" /> : <Gauge size={15} />}
              {t('Measure it', 'اندازه بگیر')}
            </button>
            {cuda?.canInstall && (
              <button className="ce-btn ce-btn--sm" disabled={busy !== null} onClick={() => void installCuda()}>
                {busy === 'install' ? <Loader2 size={15} className="ce-spin" /> : <Download size={15} />}
                {t(
                  `Get the CUDA libraries (${cuda.downloadMb} MB)`,
                  `دریافت کتابخانه‌های CUDA (${cuda.downloadMb} مگابایت)`
                )}
              </button>
            )}
          </div>

          {cuda && cuda.device !== 'cuda' && cuda.card && (
            <p className="ce-hint">
              {t(
                'Transcription is running on the processor. Your card can do it once cuBLAS and cuDNN are here.',
                'تشخیص گفتار روی پردازنده اجرا می‌شود. کارت تو می‌تواند این کار را انجام دهد، به‌شرط نصب cuBLAS و cuDNN.'
              )}
            </p>
          )}
        </>
      )}
    </Card>
  )
}
