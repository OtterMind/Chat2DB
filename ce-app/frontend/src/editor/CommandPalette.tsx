import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { Command } from 'cmdk'
import {
  FolderOpen, Upload, Scissors, Copy, Trash2, Undo2, Redo2, AudioWaveform,
  Film, Wand2, Home, Sparkles, Settings, Layers, Music4, Type, VolumeX,
} from 'lucide-react'
import { useI18n } from '../i18n'

export interface PaletteAction {
  id: string
  icon: JSX.Element
  label: [en: string, fa: string]
  run: () => void
}

/**
 * Ctrl+K — the command palette the advisors asked for: one input that reaches
 * every tool, in both languages, keyboard-first. Actions are injected by the
 * Studio so they always call the *real* handlers; nothing here is a mock.
 */
export default function CommandPalette({
  open, onClose, actions,
}: { open: boolean; onClose: () => void; actions: PaletteAction[] }) {
  const { t, lang } = useI18n()
  const navigate = useNavigate()
  const i = lang === 'fa' ? 1 : 0

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        // toggle is handled by the parent's state; here we only stop the browser
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  if (!open) return null

  const nav: PaletteAction[] = [
    { id: 'home', icon: <Home size={15} />, label: ['Go home', 'برگشت به خانه'], run: () => navigate('/') },
    { id: 'style', icon: <Sparkles size={15} />, label: ['Style Match', 'استایل مچ'], run: () => navigate('/style') },
    { id: 'settings', icon: <Settings size={15} />, label: ['Settings', 'تنظیمات'], run: () => navigate('/settings') },
  ]

  return (
    <div className="cmdk-overlay" onClick={onClose}>
      <div className="cmdk" onClick={(e) => e.stopPropagation()}>
        <Command label={t('Command menu', 'منوی فرمان')}>
          <div className="cmdk__in">
            <Command.Input placeholder={t('Type a command…', 'دستور بنویس…')} autoFocus />
            <span className="kbd">Esc</span>
          </div>
          <Command.List>
            <Command.Empty>{t('No matching command.', 'فرمانی جور در نیامد.')}</Command.Empty>
            {[...actions, ...nav].map((a) => (
              <Command.Item
                key={a.id}
                value={`${a.label[0]} ${a.label[1]}`}
                onSelect={() => { a.run(); onClose() }}
              >
                <span className="cmdk__ic">{a.icon}</span>
                <span>{a.label[i]}</span>
                <span className="cmdk__go">↵</span>
              </Command.Item>
            ))}
          </Command.List>
          <div className="cmdk__foot">
            <span><span className="kbd">↑↓</span> {t('move', 'جابه‌جایی')}</span>
            <span><span className="kbd">↵</span> {t('run', 'اجرا')}</span>
          </div>
        </Command>
      </div>
    </div>
  )
}

// keep icon imports referenced for tree-shaking clarity
export const PALETTE_ICONS = {
  FolderOpen, Upload, Scissors, Copy, Trash2, Undo2, Redo2, AudioWaveform,
  Film, Wand2, Layers, Music4, Type, VolumeX,
}
