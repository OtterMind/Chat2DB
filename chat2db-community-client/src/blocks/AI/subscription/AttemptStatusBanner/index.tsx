import { Alert, Button, Modal } from 'antd';
import i18n from '@/i18n';
import type { AiAttemptView } from '@/typings/aiSubscription';
import { decideManualRetry, presentAttempt, providerBusyMessageI18nKey, toolRetryWarningI18nKey } from '../attemptUi';

interface AttemptStatusBannerProps {
  attempt?: AiAttemptView | null;
  providerBusy?: boolean;
  onRetry?: (params: { acknowledgeToolRerun: boolean }) => void;
}

export default function AttemptStatusBanner({ attempt, providerBusy, onRetry }: AttemptStatusBannerProps) {
  if (providerBusy) {
    return <Alert type="warning" showIcon message={i18n(providerBusyMessageI18nKey() as any)} />;
  }

  if (!attempt) {
    return null;
  }

  const presentation = presentAttempt(attempt);
  const retry = decideManualRetry(attempt);

  const handleRetry = () => {
    if (!onRetry || !retry.allowed) {
      return;
    }
    if (retry.requiresDuplicateToolWarning) {
      Modal.confirm({
        title: i18n('ai.subscription.attempt.retry'),
        content: i18n(toolRetryWarningI18nKey() as any),
        onOk: () => onRetry({ acknowledgeToolRerun: true }),
      });
      return;
    }
    onRetry({ acknowledgeToolRerun: false });
  };

  return (
    <Alert
      type={
        presentation.status === 'completed'
          ? 'success'
          : presentation.status === 'in_progress' || presentation.status === 'partial_visible'
          ? 'info'
          : 'warning'
      }
      showIcon
      message={i18n(presentation.statusI18nKey as any)}
      description={
        presentation.showPartialOutput && attempt.partialOutput
          ? attempt.partialOutput.slice(0, 280)
          : undefined
      }
      action={
        retry.allowed && onRetry ? (
          <Button size="small" onClick={handleRetry}>
            {i18n('ai.subscription.attempt.retry')}
          </Button>
        ) : undefined
      }
    />
  );
}
