import i18n from '@/i18n';
import type { AgentDataScope } from '@/service/agent';
import { Tag, Tooltip } from 'antd';
import { ShieldCheck } from 'lucide-react';

import { approvalModeColor, normalizeApprovalMode } from './approvalMode';

interface Props {
  mode?: AgentDataScope['approvalMode'];
  className?: string;
  label?: string;
}

export default function ApprovalModeTag({ mode, className, label }: Props) {
  const value = normalizeApprovalMode(mode);
  const description = i18n(
    `task.agent.approvalMode.${value.toLowerCase()}` as Parameters<typeof i18n>[0],
  );
  const color = approvalModeColor(value);
  return (
    <Tooltip title={description}>
      <Tag
        className={className}
        bordered={false}
        color={color === 'default' ? undefined : color}
        icon={<ShieldCheck size={11} aria-hidden="true" />}
      >
        {label || i18n('task.scope.approvalBadge', value)}
      </Tag>
    </Tooltip>
  );
}
