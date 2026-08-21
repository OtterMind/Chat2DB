import { useEffect, useRef, useState } from 'react'
import { Save, FolderOpen, FilePlus2, Check, Loader2, AlertTriangle } from 'lucide-react'
import { Dropdown, Input, Modal, message } from 'antd'
import { projectsApi, type ProjectSummary } from '../api/projects'
import { useEditor } from './model'
import { useI18n } from '../i18n'

const AUTOSAVE_MS = 20_000

/**
 * Project bar: name, save state, open and new.
 *
 * Work is also written to a single autosave slot every 20 seconds while the
 * project is dirty, and offered back on the next launch — losing an edit to a
 * crash or a closed window is not an acceptable failure mode for an editor.
 */
export default function ProjectBar() {
  const { t } = useI18n()
  const { projectName, dirty, lastSavedAt, setProjectName, markSaved, loadSnapshot, toDocument } = useEditor()
  const [saving, setSaving] = useState(false)
  const [projects, setProjects] = useState<ProjectSummary[]>([])
  const restored = useRef(false)

  const refresh = () => projectsApi.list().then((r) => setProjects(r.projects)).catch(() => undefined)

  useEffect(() => {
    refresh()
  }, [])

  /** Offer the autosave back once, on first mount. */
  useEffect(() => {
    if (restored.current) return
    restored.current = true
    projectsApi
      .list()
      .then(async ({ hasAutosave }) => {
        if (!hasAutosave) return
        const doc = await projectsApi.loadAutosave()
        const clips = (doc.timeline?.clips ?? []).length
        if (!clips) return
        Modal.confirm({
          title: t('Restore unsaved work?', 'کار ذخیره‌نشده بازیابی شود؟'),
          content: t(
            `An autosave from your last session contains ${clips} clip(s).`,
            `ذخیره‌ی خودکار جلسه‌ی قبل شامل ${clips} کلیپ است.`
          ),
          okText: t('Restore', 'بازیابی'),
          cancelText: t('Discard', 'رد کردن'),
          onOk: () => loadSnapshot(doc.timeline as never, doc.name),
        })
      })
      .catch(() => undefined)
  }, [loadSnapshot, t])

  /** Periodic autosave while there are unsaved changes. */
  useEffect(() => {
    const timer = window.setInterval(() => {
      const state = useEditor.getState()
      if (!state.dirty || state.clips.length === 0) return
      projectsApi.autosave(state.projectName, state.toDocument()).catch(() => undefined)
    }, AUTOSAVE_MS)
    return () => window.clearInterval(timer)
  }, [])

  const save = async (name = projectName) => {
    const state = useEditor.getState()
    if (state.clips.length === 0) {
      message.info(t('Nothing to save yet.', 'هنوز چیزی برای ذخیره نیست.'))
      return
    }
    setSaving(true)
    try {
      await projectsApi.save(name, state.toDocument())
      markSaved()
      refresh()
      message.success(t('Project saved', 'پروژه ذخیره شد'))
    } catch (err) {
      message.error((err as Error).message)
    } finally {
      setSaving(false)
    }
  }

  /** Ctrl+S anywhere in the editor. */
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
        e.preventDefault()
        void save()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  const open = async (name: string) => {
    try {
      const doc = await projectsApi.load(name)
      loadSnapshot(doc.timeline as never, doc.name)
      if (doc.missingMedia?.length) {
        message.warning(
          t(
            `${doc.missingMedia.length} media file(s) could not be found.`,
            `${doc.missingMedia.length} فایل رسانه پیدا نشد.`
          )
        )
      } else {
        message.success(t('Project opened', 'پروژه باز شد'))
      }
    } catch (err) {
      message.error((err as Error).message)
    }
  }

  const startNew = () => {
    const proceed = () => {
      useEditor.getState().clearTimeline()
      loadSnapshot({ clips: [], transitions: [] }, t('Untitled', 'بدون نام'))
    }
    if (dirty) {
      Modal.confirm({
        title: t('Discard unsaved changes?', 'تغییرات ذخیره‌نشده رها شود؟'),
        okText: t('Discard', 'رها کن'),
        cancelText: t('Cancel', 'انصراف'),
        onOk: proceed,
      })
    } else proceed()
  }

  return (
    <div className="pj">
      <Input
        className="pj__name"
        value={projectName}
        onChange={(e) => setProjectName(e.target.value)}
        size="small"
        aria-label={t('Project name', 'نام پروژه')}
      />

      <span className={`pj__state ${dirty ? 'is-dirty' : ''}`}>
        {saving ? (
          <>
            <Loader2 size={13} className="ce-spin" /> {t('Saving…', 'در حال ذخیره…')}
          </>
        ) : dirty ? (
          <>
            <AlertTriangle size={13} /> {t('Unsaved changes', 'تغییرات ذخیره‌نشده')}
          </>
        ) : lastSavedAt ? (
          <>
            <Check size={13} /> {t('Saved', 'ذخیره شد')}
          </>
        ) : (
          t('New project', 'پروژه جدید')
        )}
      </span>

      <button className="ce-btn ce-btn--sm" onClick={() => save()} disabled={saving}>
        <Save size={15} /> {t('Save', 'ذخیره')} <kbd>Ctrl+S</kbd>
      </button>

      <Dropdown
        trigger={['click']}
        menu={{
          items: projects.length
            ? projects.map((p) => ({
                key: p.name,
                label: `${p.name} · ${p.clips} ${t('clips', 'کلیپ')}`,
                onClick: () => open(p.name),
              }))
            : [{ key: 'none', label: t('No saved projects', 'پروژه‌ی ذخیره‌شده‌ای نیست'), disabled: true }],
        }}
      >
        <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={refresh}>
          <FolderOpen size={15} /> {t('Open', 'باز کردن')}
        </button>
      </Dropdown>

      <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={startNew}>
        <FilePlus2 size={15} /> {t('New', 'جدید')}
      </button>
    </div>
  )
}
