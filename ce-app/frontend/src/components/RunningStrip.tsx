import { useNavigate } from 'react-router-dom'
import { Loader2, CheckCircle2, AlertTriangle } from 'lucide-react'
import { useRuntime, selectAllTasks } from '../store/runtime'
import { useI18n } from '../i18n'

/**
 * Horizontal strip of everything that is currently running.
 *
 * It reads from the global runtime store, so the progress you see here is the
 * same on every screen — switching tabs cannot interrupt or reset it.
 */
export default function RunningStrip({ compact = false }: { compact?: boolean }) {
  const navigate = useNavigate()
  const { t } = useI18n()
  const tasks = useRuntime(selectAllTasks).filter((t) =>
    compact ? t.status === 'running' : t.status !== 'done' || Date.now() - t.updatedAt < 20_000
  )
  const clearFinished = useRuntime((s) => s.clearFinished)

  if (tasks.length === 0) return null

  return (
    <div className={`ce-running ${compact ? 'ce-running--compact' : ''}`}>
      <div className="ce-running__head">
        <span>
          {t('In progress', 'در حال انجام')} ({tasks.filter((x) => x.status === 'running').length})
        </span>
        <button className="ce-link" onClick={clearFinished}>
          {t('Clear finished', 'پاک‌سازی تمام‌شده‌ها')}
        </button>
      </div>
      <div className="ce-running__list">
        {tasks.map((task) => (
          <button
            key={task.id}
            className="ce-running__item"
            onClick={() => task.route && navigate(task.route)}
          >
            <span className="ce-running__icon">
              {task.status === 'running' && <Loader2 size={16} className="ce-spin" />}
              {task.status === 'done' && <CheckCircle2 size={16} color="#22C55E" />}
              {task.status === 'failed' && <AlertTriangle size={16} color="#F87171" />}
            </span>
            <span className="ce-running__body">
              <span className="ce-running__label">{task.label}</span>
              <span className="ce-running__stage">
                {task.error ?? task.stage ?? '—'} · {Math.round(task.progress)}%
              </span>
              <span className="ce-progress">
                <span
                  className="ce-progress__bar"
                  style={{
                    width: `${Math.min(100, Math.max(2, task.progress))}%`,
                    background:
                      task.status === 'failed'
                        ? '#F87171'
                        : task.status === 'done'
                          ? '#22C55E'
                          : 'linear-gradient(90deg,#6366F1,#8B5CF6)',
                  }}
                />
              </span>
            </span>
          </button>
        ))}
      </div>
    </div>
  )
}
