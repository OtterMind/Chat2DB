import { useRef, useState } from 'react'
import { Modal, Segmented, message } from 'antd'
import { Monitor, Camera, Square } from 'lucide-react'
import { useEditor } from './model'
import { useI18n } from '../i18n'
import { backendOrigin } from '../api/runtime'
import { renderApi } from '../api/render'

/**
 * Veed-style recorder, local-first: the screen or the webcam is captured with
 * the browser's MediaRecorder, the blob is posted to the backend and lands in
 * ~/CuttingEdge/recordings, then imported onto the timeline like any clip.
 * Nothing ever leaves the machine.
 */
export default function RecorderModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useI18n()
  const [mode, setMode] = useState<'screen' | 'camera'>('screen')
  const [recording, setRecording] = useState(false)
  const recRef = useRef<MediaRecorder | null>(null)
  const streamRef = useRef<MediaStream | null>(null)

  const start = async () => {
    try {
      const stream =
        mode === 'screen'
          ? await navigator.mediaDevices.getDisplayMedia({ video: true, audio: true })
          : await navigator.mediaDevices.getUserMedia({ video: true, audio: true })
      streamRef.current = stream
      const rec = new MediaRecorder(stream, { mimeType: 'video/webm' })
      const chunks: Blob[] = []
      rec.ondataavailable = (e) => chunks.push(e.data)
      rec.onstop = async () => {
        stream.getTracks().forEach((tr) => tr.stop())
        const blob = new Blob(chunks, { type: 'video/webm' })
        const reader = new FileReader()
        reader.onload = async () => {
          const b64 = String(reader.result).split(',')[1]
          try {
            const saved = await fetch(`${backendOrigin}/api/render/recordings/save`, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ name: `rec-${Date.now()}`, data: b64, ext: 'webm' }),
            }).then((r) => r.json())
            const info = await renderApi.probe(saved.path)
            const state = useEditor.getState()
            let lane = state.tracks.find((x) => x.kind === 'video')
            if (!lane) {
              state.addTrack('video')
              lane = useEditor.getState().tracks.find((x) => x.kind === 'video')
            }
            state.addClip({
              trackId: lane?.id ?? 'v1',
              start: 0,
              duration: Math.max(0.5, info.duration),
              offset: 0,
              sourceDuration: Math.max(0.5, info.duration),
              width: info.width || undefined,
              height: info.height || undefined,
              src: info.path,
              label: t('Recording', 'ضبط'),
              color: '#8B5CF6',
            })
            message.success(t('Recording added to the timeline', 'ضبط به تایم‌لاین اضافه شد'))
          } catch (err) {
            message.error((err as Error).message)
          }
        }
        reader.readAsDataURL(blob)
        setRecording(false)
        onClose()
      }
      recRef.current = rec
      rec.start()
      setRecording(true)
    } catch {
      message.error(t('The recorder needs permission in this environment.', 'ضبط در این محیط به اجازه نیاز دارد.'))
    }
  }

  const stop = () => recRef.current?.stop()

  return (
    <Modal open={open} onCancel={() => { if (recording) stop(); else onClose() }} footer={null}
      title={t('Record', 'ضبط')}>
      <Segmented
        value={mode}
        disabled={recording}
        onChange={(v) => setMode(v as 'screen' | 'camera')}
        options={[
          { value: 'screen', label: <span><Monitor size={14} /> {t('Screen', 'صفحه')}</span> },
          { value: 'camera', label: <span><Camera size={14} /> {t('Webcam', 'وبکم')}</span> },
        ]}
      />
      <p className="ce-hint" style={{ margin: '10px 0' }}>
        {t('Stays on your machine — saved to your recordings folder and imported.', 'روی دستگاه خودت می‌ماند — در پوشه‌ی ضبط‌ها ذخیره و وارد تایم‌لاین می‌شود.')}
      </p>
      <button className={`ce-btn ${recording ? 'ce-btn--primary' : ''}`} onClick={() => (recording ? stop() : void start())}>
        <Square size={14} /> {recording ? t('Stop & import', 'توقف و وارد کردن') : t('Start recording', 'شروع ضبط')}
      </button>
    </Modal>
  )
}
