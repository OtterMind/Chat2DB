import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, Plus, Zap, TrendingUp } from 'lucide-react'
import { BADGE_LABELS, FEATURES, GROUP_TITLES, type FeatureTile } from '../features/catalog'
import { useI18n } from '../i18n'
import { jobsApi, systemApi } from '../api/jobs'
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

      {/* Primary call to action, styled like the promo banner of a super-app */}
      <section className="ce-banner ce-banner--primary" onClick={() => navigate('/new')}>
        <div className="ce-banner__text">
          <h2>{t('Turn long videos into viral clips', 'ویدیوی بلندت را به کلیپ وایرال تبدیل کن')}</h2>
          <p>{t('Drop a YouTube link or a file — AI does the rest', 'لینک یوتیوب بده یا فایل بگذار — بقیه‌اش با هوش مصنوعی')}</p>
        </div>
        <span className="ce-banner__cta">
          <Plus size={16} /> {t('New project', 'پروژه جدید')}
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
          <h3>{t('Recent projects', 'پروژه‌های اخیر')}</h3>
          <button className="ce-link" onClick={() => navigate('/dashboard')}>
            {t('See all', 'همه')} <ArrowLeft size={14} />
          </button>
        </div>
        {jobs.length === 0 ? (
          <div className="ce-empty">
            {t('No projects yet — start with “New project”.', 'هنوز پروژه‌ای نساخته‌ای. از «پروژه جدید» شروع کن.')}
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
