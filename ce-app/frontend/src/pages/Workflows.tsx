import { useEffect, useState } from 'react'
import { message } from 'antd'
import { Play, FolderClock, Workflow as WfIcon } from 'lucide-react'
import Page, { Card } from '../components/Page'
import { useI18n } from '../i18n'
import { backendOrigin } from '../api/runtime'
import { pickMedia } from '../api/render'

interface Preset {
  id: string
  en: string
  fa: string
  desc_en: string
  desc_fa: string
  nodes_meta: { id: string; label: string }[]
}

/**
 * The local automation layer (n8n-flavoured, local-first): each preset is an
 * ordered node chain over our own engine steps; run it on a file, or let a
 * watched folder fire it on every new video. Every run reports per-node progress.
 */
export default function Workflows() {
  const { t, lang } = useI18n()
  const [presets, setPresets] = useState<Preset[]>([])
  const [watch, setWatch] = useState<{ active: boolean; dir: string | null }>({ active: false, dir: null })
  const [progress, setProgress] = useState<Record<string, string>>({})
  const [extOut, setExtOut] = useState('')

  const ext = async (kind: string) => {
    const post = (path: string, body: unknown) =>
      fetch(`${backendOrigin}${path}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }).then((r) => r.json())
    try {
      if (kind === 'webhook') setExtOut(JSON.stringify(await post('/api/extend/webhook/test', { event: 'test' })))
      if (kind === 'fanout') setExtOut(JSON.stringify((await post('/api/extend/fanout', { name: 'demo', platforms: ['tiktok', 'reels'] })).specs?.length ?? 0) + ' platforms')
      if (kind === 'chain') setExtOut(JSON.stringify((await post('/api/extend/chain', { text: 'a volleyball rally' })).steps?.map((s: any) => s.step)))
      if (kind === 'vault') { await post('/api/extend/vault/set', { service: 'demo', value: 'x' }); setExtOut('vault: ' + JSON.stringify((await fetch(`${backendOrigin}/api/extend/vault/list`).then((r) => r.json())).services)) }
    } catch (err) {
      setExtOut((err as Error).message)
    }
  }

  const load = () =>
    fetch(`${backendOrigin}/api/workflows/list`).then((r) => r.json())
      .then((d) => { setPresets(d.presets ?? []); setWatch(d.watch ?? { active: false, dir: null }) })
      .catch(() => undefined)
  useEffect(() => { void load() }, [])

  const run = async (preset: Preset) => {
    const picker = pickMedia()
    const paths = picker ? await picker : null
    if (!paths?.[0]) return
    setProgress((p) => ({ ...p, [preset.id]: t('starting…', 'شروع…') }))
    try {
      const started = await fetch(`${backendOrigin}/api/workflows/run`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ preset: preset.id, path: paths[0] }),
      }).then((r) => r.json())
      const poll = window.setInterval(async () => {
        const p = await fetch(`${backendOrigin}/api/tasks/${started.id}`).then((r) => r.json())
        if (p.status === 'running') {
          setProgress((prev) => ({ ...prev, [preset.id]: `${p.stage ?? ''} ${Math.round((p.progress ?? 0) * 100)}%` }))
          return
        }
        window.clearInterval(poll)
        if (p.status === 'done') {
          setProgress((prev) => ({ ...prev, [preset.id]: '' }))
          message.success(t(`${preset.en} finished — project saved`, `${preset.fa} تمام شد و پروژه ذخیره شد`))
        } else {
          setProgress((prev) => ({ ...prev, [preset.id]: '' }))
          message.error(p.error || t('workflow failed', 'ورک‌فلو ناموفق بود'))
        }
      }, 800)
    } catch (err) {
      setProgress((prev) => ({ ...prev, [preset.id]: '' }))
      message.error((err as Error).message)
    }
  }

  const toggleWatch = async () => {
    if (watch.active) {
      setWatch((await fetch(`${backendOrigin}/api/workflows/watch/stop`, { method: 'POST' }).then((r) => r.json())))
      return
    }
    const dir = '~/CuttingEdge/watch'
    setWatch((await fetch(`${backendOrigin}/api/workflows/watch/start`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ dir, preset: 'shorts' }),
    }).then((r) => r.json())))
    message.info(t('Watching the folder — drop a video and the Shorts factory runs.',
      'پوشه زیر نظر است — یک ویدیو بینداز تا کارخانه‌ی شورت اجرا شود.'))
  }

  return (
    <Page title={t('Workflows', 'ورک‌فلوها')}
      subtitle={t('Chain your own pipeline — a trigger feeds ordered nodes, like n8n, but fully local',
        'مثل n8n زنجیره‌ی nodeها بساز، ولی کاملاً محلی')} width="md" back>
      <Card title={t('Watched folder (trigger)', 'پوشه‌ی زیر نظر (تریگر)')}>
        <p className="ce-hint">
          {watch.active
            ? t(`Watching ${watch.dir} — new videos run the Shorts factory automatically.`, `در حال پایش ${watch.dir} — ویدیوی جدید خودکار پردازش می‌شود.`)
            : t('Drop-in automation: any new video in the folder is ingested, cut, captioned and saved.', 'اتوماسیون drop-in: هر ویدیوی جدید بریده، زیرنویس و ذخیره می‌شود.')}
        </p>
        <div className="ce-actions" style={{ marginTop: 10 }}>
          <button className={`ce-btn ce-btn--sm ${watch.active ? 'ce-btn--auto' : ''}`} onClick={() => void toggleWatch()}>
            <FolderClock size={14} /> {watch.active ? t('Stop watching', 'توقف پایش') : t('Watch a folder', 'پایش پوشه')}
          </button>
        </div>
      </Card>

      {presets.map((preset) => (
        <Card key={preset.id} title={lang === 'fa' ? preset.fa : preset.en}>
          <p className="ce-hint">{lang === 'fa' ? preset.desc_fa : preset.desc_en}</p>
          <div className="wf-chain" dir="ltr">
            {preset.nodes_meta.map((node, i) => (
              <span key={node.id} className="wf-node" title={node.label}>
                <span className="wf-node__id mono">{node.id}</span>
                {i < preset.nodes_meta.length - 1 && <span className="wf-node__arrow">→</span>}
              </span>
            ))}
          </div>
          <div className="ce-actions" style={{ marginTop: 10 }}>
            <button className="ce-btn ce-btn--sm" onClick={() => void run(preset)}>
              <Play size={14} /> {t('Run on a file', 'اجرا روی یک فایل')}
            </button>
            {progress[preset.id] && <span className="ce-hint mono" dir="ltr">{progress[preset.id]}</span>}
          </div>
        </Card>
      ))}
      <Card title={t('Extensions — the eight n8n-inspired doors', 'افزونه‌ها — هشت درِ الهام‌گرفته از n8n')}>
        <div className="ce-actions" style={{ flexWrap: 'wrap' }}>
          <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => void ext('webhook')}>
            {t('Test webhook → your n8n', 'تست وب‌هوک → n8n تو')}
          </button>
          <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => void ext('fanout')}>
            {t('Fan-out platforms', 'خروجی چندکاناله')}
          </button>
          <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => void ext('chain')}>
            {t('LLM chain (summarise→title→hook)', 'زنجیرهٔ LLM')}
          </button>
          <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => void ext('vault')}>
            {t('Local key vault', 'خزانهٔ کلید محلی')}
          </button>
        </div>
        {extOut && <p className="ce-hint mono" dir="ltr" style={{ marginTop: 8 }}>{extOut}</p>}
      </Card>

      <p className="ce-hint" style={{ marginTop: 12 }}>
        <WfIcon size={13} /> {t('Inspired by open n8n workflow patterns — rebuilt local-first: no cloud, your keys, your machine.',
          'با الهام از الگوهای باز n8n — بازسازی local-first: بدون ابر، روی دستگاه خودت.')}
      </p>
    </Page>
  )
}
