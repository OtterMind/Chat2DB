import type { ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { ArrowRight, ArrowLeft } from 'lucide-react'
import { useI18n } from '../i18n'

/**
 * Every screen renders through this shell, so page structure — width, heading
 * position, spacing and the gap kept clear for the task dock — is identical
 * everywhere. That is what stops layouts from drifting or colliding as the app
 * grows.
 */
export default function Page({
  title,
  subtitle,
  actions,
  back = false,
  width = 'md',
  /**
   * Editors want the whole window: the heading strip is dropped and the tool
   * bars become the top of the screen, the way a video app is expected to look.
   */
  bare = false,
  children,
}: {
  title: string
  subtitle?: string
  actions?: ReactNode
  back?: boolean
  width?: 'sm' | 'md' | 'lg'
  bare?: boolean
  children: ReactNode
}) {
  const navigate = useNavigate()
  const { t, isFa } = useI18n()
  const Back = isFa ? ArrowRight : ArrowLeft
  return (
    <div className={`ce-page ce-page--${width} ${bare ? 'ce-page--bare' : ''}`}>
      {!bare && (
      <header className="ce-page__head">
        <div className="ce-page__title">
          {back && (
            <button className="ce-iconbtn" onClick={() => navigate(-1)} aria-label={t('Back', 'بازگشت')}>
              <Back size={20} />
            </button>
          )}
          <div>
            <h2>{title}</h2>
            {subtitle && <p>{subtitle}</p>}
          </div>
        </div>
        {actions && <div className="ce-page__actions">{actions}</div>}
      </header>
      )}
      <div className="ce-page__body">{children}</div>
    </div>
  )
}

/** Numbers, units, paths and URLs must stay LTR inside Persian sentences. */
export function Num({ children }: { children: ReactNode }) {
  return (
    <span className="ce-num" dir="ltr">
      {children}
    </span>
  )
}

export function Card({
  title,
  extra,
  children,
  tone,
}: {
  title?: string
  extra?: ReactNode
  children: ReactNode
  tone?: 'default' | 'danger' | 'success'
}) {
  return (
    <section className={`ce-card ${tone ? `ce-card--${tone}` : ''}`}>
      {(title || extra) && (
        <div className="ce-card__head">
          {title && <h3>{title}</h3>}
          {extra}
        </div>
      )}
      <div className="ce-card__body">{children}</div>
    </section>
  )
}

export function Stat({ label, value, hint }: { label: string; value: ReactNode; hint?: string }) {
  return (
    <div className="ce-stat">
      <span className="ce-stat__label">{label}</span>
      <span className="ce-stat__value">{value}</span>
      {hint && <span className="ce-stat__hint">{hint}</span>}
    </div>
  )
}
