import { Tag } from 'antd';
import i18n from '@/i18n';
import type { AiMessageModelSnapshot } from '@/typings/aiSubscription';
import { presentLegacyMessage } from '../legacyHistory';

interface LegacyMessageBadgeProps {
  message: AiMessageModelSnapshot;
  validGlobalDefaultModelRefKey?: string | null;
}

export default function LegacyMessageBadge({
  message,
  validGlobalDefaultModelRefKey,
}: LegacyMessageBadgeProps) {
  const presentation = presentLegacyMessage(message, validGlobalDefaultModelRefKey);
  if (!presentation.legacyUnknown || !presentation.badgeI18nKey) {
    return null;
  }
  return <Tag>{i18n(presentation.badgeI18nKey as any)}</Tag>;
}
