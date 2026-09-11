import { useRef, useState } from 'react';
import { Button, Input } from 'antd';
import { MessageCircleQuestion } from 'lucide-react';
import i18n from '@/i18n';
import type { QuestionAnswer, QuestionOption, QuestionResponse, QuestionStatus } from '@/types/question';
import { useStyles } from './style';

export interface QuestionCardProps {
  question: string;
  options: QuestionOption[];
  status: QuestionStatus;
  answer?: QuestionAnswer;
  onAnswer: (answer: QuestionResponse) => Promise<void>;
  onCancel: () => Promise<void>;
}

export default function QuestionCard({ question, options, status, answer, onAnswer, onCancel }: QuestionCardProps) {
  const { styles } = useStyles();
  const [text, setText] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const inFlight = useRef(false);
  const pending = status === 'pending';
  const run = async (action: () => Promise<void>) => {
    if (inFlight.current || !pending) return;
    inFlight.current = true;
    setSubmitting(true); setError('');
    try { await action(); }
    catch { setError(i18n('stream.question.failed')); }
    finally { inFlight.current = false; setSubmitting(false); }
  };
  const submit = (optionId?: string) => {
    const response = text.trim();
    if (!optionId && !response) return;
    void run(() => onAnswer({ optionId, text: response || undefined }));
  };

  return <section className={styles.card} aria-label={i18n('stream.question.title')} aria-busy={submitting}>
    <div className={styles.header}>
      <MessageCircleQuestion size={16} aria-hidden="true" />
      <strong>{i18n('stream.question.title')}</strong>
      <span className={styles.status} role="status">{i18n(`stream.question.${status}`)}</span>
    </div>
    <div className={styles.question}>
      <div className={styles.label}>{i18n('stream.question.prompt')}</div>
      {question}
    </div>
    {pending ? <>
      {options.length > 0 && <div className={styles.options}>
        {options.map((option) => (
          <Button key={option.id} className={styles.option} disabled={submitting}
            onClick={() => submit(option.id)}
          >
            <span className={styles.optionContent}>
              <span>{option.label}</span>
              {option.description && <span className={styles.description}>{option.description}</span>}
            </span>
          </Button>
        ))}
      </div>}
      <form className={styles.form} onSubmit={(event) => { event.preventDefault(); submit(); }}>
        <Input.TextArea value={text} onChange={(event) => setText(event.target.value)} disabled={submitting}
          maxLength={4000} autoSize={{ minRows: 2, maxRows: 5 }} aria-label={i18n('stream.question.input')}
          placeholder={i18n('stream.question.input')}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && (event.metaKey || event.ctrlKey) && !event.nativeEvent.isComposing) {
              event.preventDefault(); submit();
            }
          }}
        />
        <div className={styles.actions}>
          <Button disabled={submitting} onClick={() => void run(onCancel)}>{i18n('stream.question.cancel')}</Button>
          <Button type="primary" htmlType="submit" loading={submitting} disabled={!text.trim() || submitting}>
            {i18n('stream.question.submit')}
          </Button>
        </div>
      </form>
      {error && <div className={styles.error} role="alert">{error}</div>}
    </> : status === 'answered' && answer && <div className={styles.answer}>
      <div className={styles.label}>{i18n('stream.question.answer')}</div>
      {answer.optionLabel && <strong>{answer.optionLabel}</strong>}
      {answer.text && <div>{answer.text}</div>}
    </div>}
  </section>;
}
