import { useParams } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, X, Download } from 'lucide-react'
import Page, { Card, Num } from '../components/Page'
import { jobsApi } from '../api/jobs'
import { backendOrigin } from '../api/runtime'
import { statusLabel, timecode } from '../lib/labels'
import { useI18n } from '../i18n'

export default function ClipReview() {
  const { id } = useParams<{ id: string }>()
  const queryClient = useQueryClient()
  const { t, lang } = useI18n()
  const { data: clips, isLoading } = useQuery({
    queryKey: ['clips', id],
    queryFn: () => jobsApi.clips(id!),
    enabled: Boolean(id),
  })

  const setStatus = async (clipId: string, status: 'selected' | 'rejected') => {
    await fetch(`${backendOrigin}/api/clips/${clipId}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status }),
    })
    queryClient.invalidateQueries({ queryKey: ['clips', id] })
  }

  return (
    <Page
      title={t('Review clips', 'بازبینی کلیپ‌ها')}
      subtitle={t('Approve or reject what the AI suggested', 'کلیپ‌های پیشنهادی هوش مصنوعی را تأیید یا رد کن')}
      back
      actions={
        <button className="ce-btn ce-btn--sm">
          <Download size={15} /> {t('Export selected', 'خروجی انتخاب‌شده‌ها')}
        </button>
      }
    >
      {isLoading && <div className="ce-empty">{t('Loading…', 'در حال بارگذاری…')}</div>}
      {!isLoading && (!clips || clips.length === 0) && (
        <div className="ce-empty">
          {t('No clips yet — come back when processing finishes.', 'هنوز کلیپی ساخته نشده — بعد از پایان پردازش دوباره سر بزن.')}
        </div>
      )}

      {clips?.map((clip) => (
        <Card key={clip.id} tone={clip.status === 'selected' ? 'success' : clip.status === 'rejected' ? 'danger' : undefined}>
          <div className="ce-clip">
            <div className="ce-clip__info">
              <div className="ce-clip__time">
                <Num>{timecode(clip.start_time)}</Num> — <Num>{timecode(clip.end_time)}</Num>
                <span className="ce-clip__score">{t('Score', 'امتیاز')} <Num>{clip.score.toFixed(1)}</Num></span>
              </div>
              {clip.ai_reasoning && <p className="ce-clip__reason" dir="auto">{clip.ai_reasoning}</p>}
            </div>
            <div className="ce-actions">
              {clip.status === 'pending' ? (
                <>
                  <button className="ce-btn ce-btn--sm" onClick={() => setStatus(clip.id, 'selected')}>
                    <Check size={15} /> {t('Approve', 'تأیید')}
                  </button>
                  <button className="ce-btn ce-btn--ghost ce-btn--sm" onClick={() => setStatus(clip.id, 'rejected')}>
                    <X size={15} /> {t('Reject', 'رد')}
                  </button>
                </>
              ) : (
                <span className={`ce-badge ${clip.status === 'selected' ? 'ce-badge--ok' : 'ce-badge--warn'}`}>
                  {statusLabel(clip.status, lang)}
                </span>
              )}
            </div>
          </div>
        </Card>
      ))}
    </Page>
  )
}
