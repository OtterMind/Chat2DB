import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { message } from 'antd'
import { ArrowLeft, Plus, Zap, TrendingUp, Film, Clock3, Wand2 } from 'lucide-react'
import { BADGE_LABELS, FEATURES, GROUP_TITLES, type FeatureTile } from '../features/catalog'
import { useI18n } from '../i18n'
import { jobsApi, systemApi } from '../api/jobs'
import { projectsApi } from '../api/projects'
import { useEditor, formatTimecode } from '../editor/model'
import { stageLabel, statusLabel } from '../lib/labels'

function Tile({ tile, onOpen }: { tile: FeatureTile; onOpen: (t: FeatureTile) => void }) {
  const { lang } = useI18n()
  const i = lang === 'fa' ? 1 : 0
  return (
    <button className="ce-tile" onClick={() => onOpen(tile)} title={tile.hint[i]}>
      <span className="ce-tile__icon" style={{ background: tile.gradient }}>
        {tile.icon}
        {tile.badge && (
          <span className={`ce-tile__badge ce-tile__badge--${tile.badge === 'soon' ? 'soon' : 'new'}`}>
            {BADGE_LABELS[tile.badge][i]}
          </span>
        )}
      </span>
      <span className="ce-tile__label">{tile.label[i]}</span>
    </button>
  )
}

export default function Home() {
  const navigate = useNavigate()
  const { t, lang } = useI18n()
  const i = lang === 'fa' ? 1 : 0
  const [query, setQuery] = useState('')

  const { data: jobsData } = useQuery({
    queryKey: ['jobs'],
    queryFn: () => jobsApi.list(1, 6),
    staleTime: 10_000,
  })
  const { data: info } = useQuery({
    queryKey: ['systemInfo'],
    queryFn: () => systemApi.info(),
    staleTime: 60_000,
  })

  const groups = useMemo(() => {
    const filtered = query.trim()
      ? FEATURES.filter((f) =>
          [...f.label, ...f.hint].some((v) => v.toLowerCase().includes(query.trim().toLowerCase()))
        )
      : FEATURES
    const order: FeatureTile['group'][] = ['core', 'ai', 'polish', 'publish', 'system']
    return order
      .map((g) => ({ group: g, items: filtered.filter((f) => f.group === g) }))
      .filter((g) => g.items.length > 0)
  }, [query])

  const openTile = (tile: FeatureTile) => navigate(tile.route)
  const jobs = jobsData?.jobs ?? []

  /* Saved editor projects are the real "recents" of a video app. */
  const { data: projectData } = useQuery({
    queryKey: ['projects'],
    queryFn: () => projectsApi.list(),
    staleTime: 5_000,
  })
  const projects = (projectData?.projects ?? []).slice(0, 8)

  const openProject = async (name: string) => {
    try {
      const doc = await projectsApi.load(name)
      useEditor.getState().loadSnapshot(doc.timeline as never, doc.name)
      if (doc.missingMedia?.length) {
        message.warning(
          t(`${doc.missingMedia.length} media file(s) could not be found.`,
            `${doc.missingMedia.length} فایل رسانه پیدا نشد.`)
        )
      }
      navigate('/studio')
    } catch (err) {
      message.error((err as Error).message)
    }
  }

  return (
    <div className="ce-home">
      <div className="ce-searchbar">
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={t('Search Cutting Edge features', 'جست‌وجو در امکانات Cutting Edge')}
          aria-label={t('Search', 'جست‌وجو')}
        />
      </div>

      {/* The two things a video app is opened for, side by side. */}
      <section className="ce-start">
        <button className="ce-start__main" onClick={() => navigate('/studio?import=1')}>
          <span className="ce-start__icon"><Plus size={26} /></span>
          <strong>{t('New video', 'ویدیوی جدید')}</strong>
          <span className="ce-start__hint">{t('Pick files and start editing', 'فایل انتخاب کن و شروع کن')}</span>
        </button>
        <button className="ce-start__main ce-start__main--alt" onClick={() => navigate('/studio')}>
          <span className="ce-start__icon"><Film size={24} /></span>
          <strong>{t('Open the editor', 'باز کردن میز تدوین')}</strong>
          <span className="ce-start__hint">{t('Continue where you left off', 'ادامه‌ی همان‌جا که بودی')}</span>
        </button>
      </section>

      {/* Recent editor projects, the row a phone editor puts under the buttons. */}
      <section className="ce-group">
        <div className="ce-group__head">
          <h3>{t('Recent projects', 'پروژه‌های اخیر')}</h3>
          {projects.length > 0 && (
            <button className="ce-link" onClick={() => navigate('/studio')}>
              {t('Editor', 'میز تدوین')} <ArrowLeft size={14} />
            </button>
          )}
        </div>
        {projects.length === 0 ? (
          <div className="ce-empty">
            {t('No saved projects yet — “New video” starts one.', 'هنوز پروژه‌ای ذخیره نشده — با «ویدیوی جدید» شروع کن.')}
          </div>
        ) : (
          <div className="ce-reel">
            {projects.map((project) => (
              <button
                key={project.name}
                className={`ce-reelcard ${project.broken ? 'is-broken' : ''}`}
                onClick={() => void openProject(project.name)}
                title={project.name}
              >
                <span className="ce-reelcard__art">
                  <Film size={20} />
                  <span className="ce-reelcard__len" dir="ltr">{formatTimecode(project.duration)}</span>
                </span>
                <span className="ce-reelcard__name" dir="auto">{project.name}</span>
                <span className="ce-reelcard__meta">
                  <Clock3 size={11} /> {new Date(project.updatedAt * 1000).toLocaleDateString()}
                </span>
              </button>
            ))}
          </div>
        )}
      </section>

      <section className="ce-banner ce-banner--primary" onClick={() => navigate('/new')}>
        <div className="ce-banner__text">
          <h2>{t('Turn long videos into viral clips', 'ویدیوی بلندت را به کلیپ وایرال تبدیل کن')}</h2>
          <p>{t('Drop a YouTube link or a file — AI does the rest', 'لینک یوتیوب بده یا فایل بگذار — بقیه‌اش با هوش مصنوعی')}</p>
        </div>
        <span className="ce-banner__cta">
          <Wand2 size={16} /> {t('Auto clip', 'کلیپ خودکار')}
        </span>
      </section>

      {groups.map(({ group, items }) => (
        <section key={group} className="ce-group">
          <div className="ce-group__head">
            <h3>{GROUP_TITLES[group][i]}</h3>
          </div>
          <div className="ce-grid">
            {items.map((tile) => (
              <Tile key={tile.id} tile={tile} onOpen={openTile} />
            ))}
          </div>
        </section>
      ))}

      <section className="ce-banner ce-banner--secondary">
        <div className="ce-banner__text">
          <h2>
            <Zap size={18} style={{ verticalAlign: '-3px' }} />{' '}
            {t('Completely free and open source', 'کاملاً رایگان و متن‌باز')}
          </h2>
          <p>{t('No subscription, no watermark, everything runs on your machine', 'بدون اشتراک، بدون واترمارک، پردازش روی سیستم خودت')}</p>
        </div>
      </section>

      <section className="ce-group">
        <div className="ce-group__head">
          <h3>{t('Automatic clip jobs', 'کارهای کلیپ خودکار')}</h3>
          <button className="ce-link" onClick={() => navigate('/dashboard')}>
            {t('See all', 'همه')} <ArrowLeft size={14} />
          </button>
        </div>
        {jobs.length === 0 ? (
          <div className="ce-empty">
            {t('No automatic jobs yet.', 'هنوز کار خودکاری اجرا نشده.')}
          </div>
        ) : (
          <div className="ce-joblist">
            {jobs.map((job) => (
              <button key={job.id} className="ce-jobcard" onClick={() => navigate(`/jobs/${job.id}`)}>
                <span className={`ce-dot ce-dot--${job.status}`} />
                <span className="ce-jobcard__name">{job.name}</span>
                <span className="ce-jobcard__meta">
                  {stageLabel(job.current_stage, lang) ?? statusLabel(job.status, lang)}
                  {job.status === 'processing' ? ` · ${Math.round(job.progress)}%` : ''}
                </span>
              </button>
            ))}
          </div>
        )}
      </section>

      <section className="ce-status">
        <TrendingUp size={14} />
        <span>FFmpeg: {info?.ffmpeg_found ? t('ready', 'آماده') : t('not found', 'یافت نشد')}</span>
        <span>
          {t('GPU', 'پردازنده گرافیکی')}: {info?.cuda_available ? t('enabled', 'فعال') : 'CPU'}
        </span>
        <span>
          {t('Free space', 'فضای آزاد')}: {info?.disk_free_gb ?? '—'} {t('GB', 'گیگابایت')}
        </span>
      </section>
    </div>
  )
}
