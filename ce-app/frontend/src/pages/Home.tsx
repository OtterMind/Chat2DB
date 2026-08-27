import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { Modal, message } from 'antd'
import { Plus, Wand2, Film, Clock3, Trash2, CircleDashed, Search, Sparkles, Settings, Activity } from 'lucide-react'
import BrandMark from '../components/BrandMark'
import { useI18n } from '../i18n'
import { systemApi } from '../api/jobs'
import { projectsApi } from '../api/projects'
import { useEditor, formatTimecode } from '../editor/model'
import UpdateCard from '../components/UpdateCard'

declare const __APP_VERSION__: string

/**
 * Launcher, rebuilt from the root on the Hybrid spec (C7.1):
 * 90% minimal pro (near-black surfaces, hairlines, white type),
 * 9% cyberpunk (the two action dots + playhead-pink accents),
 * 1% glass (the Ask-AI FAB).
 * Everything else lives behind ⌘K — a launcher is for starting, not for
 * displaying the whole catalogue.
 */

const NAV: { id: string; route: string; fa: string; en: string }[] = [
  { id: 'studio', route: '/studio', fa: 'میز تدوین', en: 'Editor' },
  { id: 'style', route: '/style', fa: 'استایل مچ', en: 'Style Match' },
  { id: 'new', route: '/new', fa: 'کلیپ خودکار از لینک', en: 'Auto clip from a link' },
  { id: 'dashboard', route: '/dashboard', fa: 'کارهای کلیپ', en: 'Clip jobs' },
  { id: 'uploads', route: '/uploads', fa: 'انتشار', en: 'Publish' },
  { id: 'settings', route: '/settings', fa: 'تنظیمات', en: 'Settings' },
  { id: 'doctor', route: '/doctor', fa: 'عیب‌یابی', en: 'Diagnostics' },
]

/** First-run tour, kept as one slim glass line — never a trapping overlay. */
function TourLine({ onDone }: { onDone: () => void }) {
  const { t } = useI18n()
  return (
    <div className="ln-tour" role="note">
      <span>
        {t('First run? Drop a video → auto-clip 30s vertical → captions on → export.',
          'اولین بار؟ ویدیو بینداز → برش خودکار ۳۰ثانیه عمودی → زیرنویس روشن → خروجی.')}
      </span>
      <button className="ln-tour__x" onClick={onDone} aria-label={t('Dismiss', 'بستن')}>✕</button>
    </div>
  )
}

export default function Home() {
  const navigate = useNavigate()
  const { t, lang } = useI18n()
  const i = lang === 'fa' ? 1 : 0

  const [paletteOpen, setPaletteOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [tourDone, setTourDone] = useState(() => localStorage.getItem('ce.tour.done') === '1')

  /** ⌘K anywhere on the launcher. */
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setPaletteOpen((v) => !v)
      }
      if (e.key === 'Escape') setPaletteOpen(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const { data: info } = useQuery({ queryKey: ['systemInfo'], queryFn: () => systemApi.info(), staleTime: 30_000 })
  const { data: projectData, refetch: refetchProjects } = useQuery({
    queryKey: ['projects'],
    queryFn: () => projectsApi.list(),
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: true,
  })
  const projects = (projectData?.projects ?? []).slice(0, 6)
  const hasAutosave = projectData?.hasAutosave ?? false

  const openAutosave = async () => {
    try {
      const doc = await projectsApi.loadAutosave()
      useEditor.getState().loadSnapshot(doc.timeline as never, doc.name)
      navigate('/studio')
    } catch {
      message.error(t('The unfinished project could not be opened.', 'پروژه‌ی نیمه‌کاره باز نشد.'))
    }
  }

  const openProject = async (name: string) => {
    try {
      const doc = await projectsApi.load(name)
      useEditor.getState().loadSnapshot(doc.timeline as never, doc.name)
      if (doc.missingMedia?.length) {
        message.warning(t(`${doc.missingMedia.length} media file(s) could not be found.`,
          `${doc.missingMedia.length} فایل رسانه پیدا نشد.`))
      }
      navigate('/studio')
    } catch (err) {
      message.error((err as Error).message)
    }
  }

  const removeProject = async (event: React.MouseEvent, name: string) => {
    event.stopPropagation()
    try {
      await projectsApi.remove(name)
      await refetchProjects()
      message.success(t('Project deleted', 'پروژه حذف شد'))
    } catch (err) {
      message.error((err as Error).message)
    }
  }

  const navItems = NAV.filter((n) =>
    query.trim() ? [n.fa, n.en].some((v) => v.toLowerCase().includes(query.trim().toLowerCase())) : true)

  return (
    <div className="ln-home">
      {/* top bar: palette + settings, nothing else */}
      <div className="ln-top">
        <button className="ln-top__btn" onClick={() => setPaletteOpen(true)} title="Ctrl+K">
          <Search size={14} /> <span className="ln-top__kbd">⌘K</span>
        </button>
        <button className="ln-top__btn" onClick={() => navigate('/settings')} title={t('Settings', 'تنظیمات')}>
          <Settings size={14} />
        </button>
        <button className="ln-top__btn" onClick={() => navigate('/doctor')} title={t('Diagnostics', 'عیب‌یابی')}>
          <Activity size={14} />
        </button>
      </div>

      {/* hero */}
      <header className="ln-hero">
        <BrandMark size="lg" />
        <p className="ln-hero__tag">{t('AI-powered desktop video editor', 'میز تدوین ویدیوی رومیزی با هوش مصنوعی')}</p>
        <p className="ln-hero__ver mono" dir="ltr">v{__APP_VERSION__}</p>
        <span className="ln-hairline" />
      </header>

      {!tourDone && (
        <TourLine onDone={() => { localStorage.setItem('ce.tour.done', '1'); setTourDone(true) }} />
      )}

      {/* the two actions, cyberpunk dots */}
      <div className="ln-actions">
        <button className="ln-action" onClick={() => navigate('/studio?import=1')}>
          <span className="ln-action__dot ln-action__dot--cyan" />
          <Plus size={16} /> {t('New Project', 'پروژه‌ی جدید')}
        </button>
        <button className="ln-action" onClick={() => navigate('/style')}>
          <span className="ln-action__dot ln-action__dot--pink" />
          <Sparkles size={16} /> {t('Style Match', 'استایل مچ')}
        </button>
        <button className="ln-action ln-action--ghost" onClick={() => navigate('/new')}>
          <Wand2 size={15} /> {t('Auto clip', 'کلیپ خودکار')}
        </button>
      </div>

      <UpdateCard />

      {/* RECENT */}
      <section className="ln-recent">
        <h3 className="ce-eyebrow">{t('Recent', 'اخیر')}</h3>
        <span className="ln-hairline ln-hairline--short" />
        {projects.length === 0 && !hasAutosave ? (
          <div className="ln-empty">
            <Film size={18} />
            <span>{t('No saved projects yet — “New Project” starts one.', 'هنوز پروژه‌ای ذخیره نشده — «پروژه‌ی جدید» شروع می‌کند.')}</span>
          </div>
        ) : (
          <>
            {hasAutosave && (
              <button className="ln-row" onClick={() => void openAutosave()}>
                <span className="ln-row__thumb"><CircleDashed size={16} /></span>
                <span className="ln-row__body">
                  <strong dir="auto">{t('Unfinished project', 'پروژه‌ی نیمه‌کاره')}</strong>
                  <span className="ln-row__meta">{t('Autosaved', 'ذخیره‌ی خودکار')}</span>
                </span>
                <span className="ln-row__dot ln-row__dot--amber" title={t('draft', 'پیش‌نویس')} />
              </button>
            )}
            {projects.map((project) => (
              <div
                key={project.name}
                className="ln-row"
                role="button"
                tabIndex={0}
                onKeyDown={(e) => e.key === 'Enter' && void openProject(project.name)}
                onClick={() => void openProject(project.name)}
                title={project.name}
              >
                <span className="ln-row__thumb"><Film size={16} /></span>
                <span className="ln-row__body">
                  <strong dir="auto">{project.name}</strong>
                  <span className="ln-row__meta">
                    <Clock3 size={11} /> {new Date(project.updatedAt * 1000).toLocaleDateString()}
                    {' · '}<span dir="ltr">{formatTimecode(project.duration)}</span>
                  </span>
                </span>
                <button
                  className="ln-row__del"
                  onClick={(e) => void removeProject(e, project.name)}
                  title={t('Delete this project', 'حذف این پروژه')}
                  aria-label={t('Delete', 'حذف')}
                >
                  <Trash2 size={13} />
                </button>
                <span className={`ln-row__dot ${project.broken ? 'ln-row__dot--red' : 'ln-row__dot--green'}`} />
              </div>
            ))}
          </>
        )}
      </section>

      {/* AI FAB — the 1% glass */}
      <button className="ln-fab" onClick={() => navigate('/studio')}>
        <Sparkles size={15} /> {t('Ask AI', 'از هوش مصنوعی بخواه')}
      </button>

      {/* status strip */}
      <footer className="ln-status mono" dir="ltr">
        <span className={`ln-row__dot ${info ? 'ln-row__dot--green' : 'ln-row__dot--red'}`} />
        <span>{info ? 'backend online' : 'backend offline'}</span>
        <span>v{__APP_VERSION__}</span>
        <span>{info?.disk_free_gb ?? '—'} GB free</span>
        <span>{info?.cuda_available ? 'GPU on' : 'CPU'}</span>
        <span style={{ marginInlineStart: 'auto', opacity: 0.6 }}>
          {t('free & open source', 'رایگان و متن‌باز')}
        </span>
      </footer>

      <Modal
        open={paletteOpen}
        onCancel={() => setPaletteOpen(false)}
        footer={null}
        width={440}
        title={null}
      >
        <input
          autoFocus
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={t('Jump to…', 'برو به…')}
          style={{ width: '100%', marginBottom: 10 }}
        />
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          {navItems.map((n) => (
            <button
              key={n.id}
              className="ln-pal"
              onClick={() => { setPaletteOpen(false); setQuery(''); navigate(n.route) }}
            >
              {i === 1 ? n.fa : n.en}
            </button>
          ))}
        </div>
      </Modal>
    </div>
  )
}
