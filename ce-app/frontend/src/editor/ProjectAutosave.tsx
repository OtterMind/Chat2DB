import { useEffect, useRef } from 'react'
import { message } from 'antd'
import { projectsApi } from '../api/projects'
import { useEditor } from './model'
import { useI18n } from '../i18n'

const AUTOSAVE_MS = 20_000

/**
 * Project persistence, with no interface of its own.
 *
 * The editor no longer carries a save bar: the work saves itself every twenty
 * seconds while it is dirty, `Ctrl+S` names and stores it, and unfinished work
 * shows up under "Recent projects" on the home screen — which is where a person
 * looks for it anyway. Nothing about the mechanism changed, only where it is
 * visible.
 */
export default function ProjectAutosave() {
  const { t } = useI18n()
  const saving = useRef(false)

  /** Periodic autosave while there are unsaved changes. */
  useEffect(() => {
    const timer = window.setInterval(() => {
      const state = useEditor.getState()
      if (!state.dirty || state.clips.length === 0) return
      projectsApi.autosave(state.projectName, state.toDocument()).catch(() => undefined)
    }, AUTOSAVE_MS)
    return () => window.clearInterval(timer)
  }, [])

  /** Ctrl+S saves under the current name, silently creating one if needed. */
  useEffect(() => {
    const onKey = async (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== 's') return
      event.preventDefault()
      const state = useEditor.getState()
      if (state.clips.length === 0) {
        message.info(t('Nothing to save yet.', 'هنوز چیزی برای ذخیره نیست.'))
        return
      }
      if (saving.current) return
      saving.current = true
      try {
        await projectsApi.save(state.projectName, state.toDocument())
        useEditor.getState().markSaved()
        message.success(t('Project saved', 'پروژه ذخیره شد'))
      } catch (error) {
        message.error((error as Error).message)
      } finally {
        saving.current = false
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [t])

  /** Save once more when the window goes away — a crash must not cost an edit. */
  useEffect(() => {
    const flush = () => {
      const state = useEditor.getState()
      if (state.dirty && state.clips.length > 0) {
        void projectsApi.autosave(state.projectName, state.toDocument()).catch(() => undefined)
      }
    }
    window.addEventListener('beforeunload', flush)
    return () => window.removeEventListener('beforeunload', flush)
  }, [])

  return null
}
