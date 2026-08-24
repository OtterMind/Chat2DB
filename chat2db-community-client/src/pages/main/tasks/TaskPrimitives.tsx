import i18n from '@/i18n';
import type { AgentDefinition, AgentRun, AgentRunStatus } from '@/service/agent';
import { Tag, Tooltip } from 'antd';
import { Cable, CircleCheck, CircleDashed, CircleDot, Cloud, Cpu, PauseCircle, TriangleAlert, XCircle } from 'lucide-react';
import type { ReactNode } from 'react';

import { agentAvatarSource, avatarRuntimeProvider, RuntimeProviderLogo } from './RuntimeProviderLogo';
import { useStyles } from './style';

const activeRunStatuses: AgentRunStatus[] = ['QUEUED', 'DISPATCHED', 'RUNNING', 'WAITING_APPROVAL'];

export function AgentAvatar({ agent, size = 28 }: { agent?: AgentDefinition; size?: number }) {
  const { styles } = useStyles();
  const label = agent?.name?.trim() || '?';
  const avatar = agentAvatarSource(agent?.avatar);
  const avatarProvider = avatarRuntimeProvider(avatar);
  return (
    <span className={styles.agentAvatar} style={{ width: size, height: size, fontSize: Math.max(10, size * 0.36) }}>
      {avatarProvider ? (
        <RuntimeProviderLogo provider={avatarProvider} />
      ) : avatar ? (
        <img src={avatar} alt="" />
      ) : (
        label.slice(0, 2).toUpperCase()
      )}
    </span>
  );
}

export function AgentIdentity({
  agent,
  fallback,
  avatarSize = 22,
}: {
  agent?: AgentDefinition;
  fallback?: ReactNode;
  avatarSize?: number;
}) {
  const { styles } = useStyles();
  return (
    <span className={styles.agentIdentity} title={agent?.name || (typeof fallback === 'string' ? fallback : undefined)}>
      <AgentAvatar agent={agent} size={avatarSize} />
      <span>{agent?.name || fallback || '?'}</span>
    </span>
  );
}

export function RuntimeBadge({
  agent,
  run,
  compact = false,
  executionMode,
  externalRuntimeName,
}: {
  agent?: AgentDefinition;
  run?: AgentRun;
  compact?: boolean;
  executionMode?: 'EXTERNAL_RUNTIME_DELEGATION';
  externalRuntimeName?: string;
}) {
  const { styles } = useStyles();
  const delegated = executionMode === 'EXTERNAL_RUNTIME_DELEGATION';
  const external = agent?.runtimeType === 'EXTERNAL_AGENT' || run?.runtimeType === 'EXTERNAL_AGENT';
  const status = run?.status;
  const active = !!status && activeRunStatuses.includes(status);
  const failed = status === 'FAILED' || status === 'UNKNOWN';
  const Icon = delegated ? Cable : external ? Cloud : Cpu;
  const label = delegated
    ? i18n('task.runtime.delegatedExternal', externalRuntimeName || i18n('task.connector.externalRuntime'))
    : external ? i18n('task.runtime.external') : i18n('task.runtime.embedded');
  const stateLabel = status ? i18n(`task.status.${status.toLowerCase()}` as Parameters<typeof i18n>[0]) : label;
  return (
    <Tooltip title={`${label}${status ? ` / ${stateLabel}` : ''}`}>
      <span className={`${styles.runtimeBadge} ${active ? styles.runtimeBadgeActive : ''} ${failed ? styles.runtimeBadgeError : ''}`}>
        <Icon size={compact ? 12 : 13} />
        {!compact && <span>{label}</span>}
        {active && <span className={styles.runtimePulse} />}
      </span>
    </Tooltip>
  );
}

export function RunStatusMark({ status, withLabel = true }: { status: AgentRunStatus; withLabel?: boolean }) {
  const { styles } = useStyles();
  const Icon =
    status === 'COMPLETED'
      ? CircleCheck
      : status === 'FAILED' || status === 'UNKNOWN'
        ? XCircle
        : status === 'WAITING_APPROVAL'
          ? PauseCircle
          : status === 'CANCELLED'
            ? CircleDashed
            : status === 'RUNNING'
              ? CircleDot
              : status === 'QUEUED' || status === 'DISPATCHED'
                ? CircleDashed
                : TriangleAlert;
  return (
    <span className={`${styles.runStatus} ${styles[`runStatus${status}` as keyof typeof styles] || ''}`}>
      <Icon size={14} />
      {withLabel && <span>{i18n(`task.status.${status.toLowerCase()}` as Parameters<typeof i18n>[0])}</span>}
    </span>
  );
}

export function CapabilityChips({ values, limit = 3 }: { values: string[]; limit?: number }) {
  const visible = values.slice(0, limit);
  return (
    <>
      {visible.map((value) => (
        <Tag bordered={false} key={value}>
          {value}
        </Tag>
      ))}
      {values.length > visible.length && <Tag bordered={false}>+{values.length - visible.length}</Tag>}
    </>
  );
}
