import { backendOrigin } from '../api/runtime'
import { useRuntime } from '../store/runtime'

/**
 * Uploads run at module scope, deliberately **outside** the React tree.
 *
 * A component that owns an XHR cancels it when it unmounts — that is exactly why
 * switching tabs used to kill an upload. Here the request object lives in a
 * module-level map, so navigation cannot touch it; progress is mirrored into the
 * runtime store and any screen can render it.
 */
const inFlight = new Map<string, XMLHttpRequest>()

export interface UploadOptions {
  /** Where the file is POSTed, e.g. `/api/jobs/import`. */
  path: string
  file: File
  label?: string
  fields?: Record<string, string>
  onDone?: (response: unknown) => void
  onError?: (message: string) => void
}

export function startBackgroundUpload({ path, file, label, fields, onDone, onError }: UploadOptions): string {
  const id = `upload:${Date.now()}:${Math.random().toString(36).slice(2, 8)}`
  const { upsertTask, patchTask } = useRuntime.getState()

  upsertTask({
    id,
    kind: 'upload',
    label: label ?? `آپلود ${file.name}`,
    stage: 'در حال ارسال',
    progress: 0,
    status: 'running',
  })

  const form = new FormData()
  form.append('file', file)
  for (const [k, v] of Object.entries(fields ?? {})) form.append(k, v)

  const xhr = new XMLHttpRequest()
  inFlight.set(id, xhr)
  xhr.open('POST', `${backendOrigin}${path}`)

  xhr.upload.onprogress = (event) => {
    if (!event.lengthComputable) return
    patchTask(id, { progress: (event.loaded / event.total) * 100 })
  }
  xhr.onload = () => {
    inFlight.delete(id)
    if (xhr.status >= 200 && xhr.status < 300) {
      patchTask(id, { progress: 100, status: 'done', stage: 'کامل شد' })
      try {
        onDone?.(JSON.parse(xhr.responseText))
      } catch {
        onDone?.(xhr.responseText)
      }
    } else {
      patchTask(id, { status: 'failed', error: `HTTP ${xhr.status}` })
      onError?.(`HTTP ${xhr.status}`)
    }
  }
  xhr.onerror = () => {
    inFlight.delete(id)
    patchTask(id, { status: 'failed', error: 'خطای شبکه' })
    onError?.('network error')
  }
  xhr.onabort = () => {
    inFlight.delete(id)
    patchTask(id, { status: 'failed', error: 'لغو شد' })
  }

  xhr.send(form)
  return id
}

/** Explicit cancellation — the only way an upload stops. */
export function cancelBackgroundUpload(id: string) {
  inFlight.get(id)?.abort()
  inFlight.delete(id)
}

export function isUploadInFlight(id: string) {
  return inFlight.has(id)
}
