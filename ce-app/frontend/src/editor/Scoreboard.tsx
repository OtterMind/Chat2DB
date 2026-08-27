/**
 * Scoreboard — brain output UI
 *
 * The "rules vs LLM" race result, in one card. Built for v0.9.34.
 *
 * Variants:
 *  - "minimal" (default): pure minimal pro, no decoration
 *  - "cyberpunk": pink + glow on the winner score only
 *
 * Reads the scoreboard that `core.brain.race` already produces, so the model
 * shape is fixed and this component only changes how it looks.
 */
import { type ReactNode } from 'react'
import { useI18n } from '../i18n'
import { formatNumber } from '../lib/format'

export interface ScoreboardEntry {
  name: string
  score: number
  shots?: number
  seconds?: number
  note?: string
  skipped?: string[]
}

export interface ScoreboardProps {
  winner: string
  scoreboard: ScoreboardEntry[]
  /** Visual accent — defaults to "minimal" (production safe) */
  variant?: 'minimal' | 'cyberpunk'
  /** Show metric breakdown under the score (rhythm, duration, meaning, freshness) */
  metrics?: Record<string, number>
  /** When the result was produced (for the timestamp footer) */
  elapsedSeconds?: number
  /** Optional CTA — usually "Apply" */
  cta?: ReactNode
}

const MAX = 1.0
const WINNER_THRESHOLD = 0.05

export function Scoreboard({
  winner,
  scoreboard,
  variant = 'minimal',
  metrics,
  elapsedSeconds,
  cta,
}: ScoreboardProps) {
  const { t } = useI18n()
  const sorted = [...scoreboard].sort((a, b) => b.score - a.score)
  const winnerScore = sorted[0]?.score ?? 0
  const isCyberpunk = variant === 'cyberpunk'

  return (
    <section className={`sb sb--${variant}`} aria-label={t('Brain scoreboard', 'جدول امتیازات مغز')}>
      <header className="sb__head">
        <span className={`sb__eyebrow ${isCyberpunk ? 'sb__eyebrow--neon' : ''}`}>
          {t('Brain decision', 'تصمیم مغز')}
        </span>
        {elapsedSeconds !== undefined && (
          <span className="sb__time ce-mono" dir="ltr">
            {formatNumber(elapsedSeconds, 1)}s
          </span>
        )}
      </header>

      <ol className="sb__list">
        {sorted.map((row, index) => {
          const isWinner = row.name === winner
          const isSkipped = (row.skipped?.length ?? 0) > 0
          const opacity = 1 - index * 0.18  // dimmer further down
          const sizeForIndex = [28, 22, 20, 18][index] ?? 18

          return (
            <li
              key={row.name}
              className={[
                'sb__row',
                isWinner ? 'sb__row--winner' : '',
                isSkipped ? 'sb__row--skipped' : '',
              ].filter(Boolean).join(' ')}
              style={{ opacity: isSkipped ? 0.4 : opacity }}
            >
              <div className="sb__row-main">
                <div className="sb__row-name">
                  <code className="ce-mono">{row.name}</code>
                  {isWinner && isCyberpunk && (
                    <span className="sb__star" aria-label="winner">★</span>
                  )}
                </div>
                {row.note && (
                  <p className="sb__row-note">{row.note}</p>
                )}
                <div className="sb__row-meta ce-mono">
                  {row.shots !== undefined && <span>{row.shots} {t('shots', 'برش')}</span>}
                  {row.seconds !== undefined && <span>{formatNumber(row.seconds, 1)}s</span>}
                </div>
              </div>
              <div className="sb__row-score">
                <span
                  className={`sb__score ce-mono ${isWinner && isCyberpunk ? 'sb__score--neon' : ''}`}
                  style={{ fontSize: sizeForIndex }}
                >
                  {formatNumber(row.score, 2)}
                </span>
                <span className={`sb__status ${isWinner ? 'is-used' : isSkipped ? 'is-skip' : 'is-cand'}`}>
                  {isWinner
                    ? t('Used', 'انتخاب شد')
                    : isSkipped
                      ? t('Skipped', 'رد شد')
                      : t('Candidate', 'نامزد')}
                </span>
              </div>
            </li>
          )
        })}
      </ol>

      {metrics && Object.keys(metrics).length > 0 && (
        <div className="sb__metrics">
          <h4 className="sb__metrics-title">
            {t('Score breakdown', 'جزئیات امتیاز')}
            <span className="sb__metrics-target ce-mono">{sorted[0]?.name}</span>
          </h4>
          <ul className="sb__metric-list">
            {Object.entries(metrics).map(([key, value]) => (
              <li key={key} className="sb__metric">
                <span className="sb__metric-label">{key}</span>
                <span className="sb__metric-bar" aria-hidden>
                  <span
                    className="sb__metric-fill"
                    style={{ width: `${(value / MAX) * 100}%` }}
                  />
                </span>
                <span className="sb__metric-value ce-mono">{formatNumber(value, 2)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {cta && <footer className="sb__cta">{cta}</footer>}

      {/* Hidden, for screen readers */}
      <span className="ce-sr-only">
        {t(
          `Winner: ${winner} with score ${formatNumber(winnerScore, 2)}`,
          `برنده: ${winner} با امتیاز ${formatNumber(winnerScore, 2)}`
        )}
      </span>
    </section>
  )
}

/* ----------------------------------------------------------------- styles */

/* Inline so this file is drop-in: just `import { Scoreboard } from './Scoreboard'`
   If you prefer, move this block into global.css and add `.sb--minimal` etc. */
const style = document?.createElement('style')
if (style && !document.getElementById('sb-styles')) {
  style.id = 'sb-styles'
  style.textContent = `
.sb { display: flex; flex-direction: column; gap: 16px; padding: 24px; }
.sb__head { display: flex; align-items: baseline; justify-content: space-between; }
.sb__eyebrow {
  font-size: 11px; font-weight: 500; text-transform: uppercase;
  letter-spacing: 0.08em; color: var(--ce-text-tertiary, rgba(255,255,255,0.5));
}
.sb__eyebrow--neon { color: var(--ce-neon-cyan, #00F0FF); text-shadow: var(--ce-glow-cyan, 0 0 8px rgba(0,240,255,0.5)); }
.sb__time { font-size: 11px; color: var(--ce-text-tertiary, rgba(255,255,255,0.5)); }
.sb__list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0; }
.sb__row {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 16px;
  padding: 16px 0; border-top: 1px solid var(--ce-border, rgba(255,255,255,0.08));
  transition: opacity var(--ce-duration, 200ms) var(--ce-ease, ease);
}
.sb__row--winner {
  border-left: 3px solid var(--ce-neon-green, #10F0A0);
  padding-left: 12px;
  background: rgba(255,255,255,0.02);
}
.sb__row--skipped { text-decoration: line-through; }
.sb__row-main { display: flex; flex-direction: column; gap: 4px; min-width: 0; flex: 1; }
.sb__row-name { display: flex; align-items: center; gap: 8px; }
.sb__row-name code { font-size: 14px; color: var(--ce-text, #fff); }
.sb__star { color: var(--ce-neon-pink, #FF2D9C); text-shadow: var(--ce-glow-pink, 0 0 8px rgba(255,45,156,0.6)); }
.sb__row-note { font-size: 13px; color: var(--ce-text-secondary, rgba(255,255,255,0.7)); margin: 0; }
.sb__row-meta { display: flex; gap: 12px; font-size: 11px; color: var(--ce-text-tertiary, rgba(255,255,255,0.5)); }
.sb__row-score { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }
.sb__score { font-weight: 500; color: var(--ce-text, #fff); line-height: 1; }
.sb__score--neon { color: var(--ce-neon-pink, #FF2D9C); text-shadow: var(--ce-glow-pink, 0 0 8px rgba(255,45,156,0.6)); }
.sb__status { font-size: 11px; padding: 2px 8px; border-radius: 999px; border: 1px solid currentColor; opacity: 0.7; }
.sb__status.is-used { color: var(--ce-neon-green, #10F0A0); }
.sb__status.is-skip { color: var(--ce-text-tertiary, rgba(255,255,255,0.5)); }
.sb__status.is-cand { color: var(--ce-text-tertiary, rgba(255,255,255,0.5)); }
.sb__metrics { display: flex; flex-direction: column; gap: 8px; padding-top: 8px; }
.sb__metrics-title { display: flex; justify-content: space-between; font-size: 13px; font-weight: 500; color: var(--ce-text, #fff); margin: 0; }
.sb__metrics-target { color: var(--ce-text-tertiary, rgba(255,255,255,0.5)); font-weight: 400; font-size: 12px; }
.sb__metric-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.sb__metric { display: grid; grid-template-columns: 100px 1fr 50px; gap: 12px; align-items: center; font-size: 12px; }
.sb__metric-label { color: var(--ce-text-tertiary, rgba(255,255,255,0.5)); text-transform: capitalize; }
.sb__metric-bar { height: 4px; background: rgba(255,255,255,0.12); border-radius: 4px; overflow: hidden; }
.sb__metric-fill { display: block; height: 100%; background: var(--ce-text, #fff); border-radius: 4px; transition: width var(--ce-duration, 200ms) var(--ce-ease, ease); }
.sb__metric-value { color: var(--ce-text, #fff); text-align: right; }
.sb__cta { padding-top: 16px; border-top: 1px solid var(--ce-border, rgba(255,255,255,0.08)); }
.ce-sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
`
  document.head.appendChild(style)
}
