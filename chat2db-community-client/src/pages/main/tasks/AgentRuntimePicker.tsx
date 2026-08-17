import i18n from '@/i18n';
import type { AgentRuntimeOption } from '@/service/agent';
import { Button, Skeleton } from 'antd';
import { Check, RefreshCw } from 'lucide-react';

import { CHAT2DB_AGENT_AVATAR, RuntimeProviderLogo, runtimeProviderName } from './RuntimeProviderLogo';
import { useStyles } from './style';

interface Props {
  runtimeType?: 'EMBEDDED_SPRING_AI' | 'EXTERNAL_AGENT';
  runtimeProfileId?: string;
  options: AgentRuntimeOption[];
  loading: boolean;
  error: boolean;
  onRefresh: () => void;
  onChange: (runtimeType: 'EMBEDDED_SPRING_AI' | 'EXTERNAL_AGENT', runtimeProfileId?: string) => void;
}

function runtimeState(option: AgentRuntimeOption) {
  if (!option.installed) return i18n('task.agent.runtimeUnavailable');
  if (option.online) return i18n('task.agent.runtimeOnline');
  return i18n('task.agent.runtimeOffline');
}

export default function AgentRuntimePicker({
  runtimeType,
  runtimeProfileId,
  options,
  loading,
  error,
  onRefresh,
  onChange,
}: Props) {
  const { styles } = useStyles();

  return (
    <div className={styles.runtimePicker}>
      <div className={styles.runtimePickerHeader}>
        <div>
          <span>{i18n('task.agent.runtime')}</span>
          <small>{i18n('task.agent.runtimeHint')}</small>
        </div>
        <Button
          type="text"
          size="small"
          icon={<RefreshCw size={13} />}
          loading={loading}
          onClick={onRefresh}
          aria-label={i18n('task.agent.runtimeRefresh')}
        >
          {i18n('task.agent.runtimeRefresh')}
        </Button>
      </div>
      <div className={styles.runtimeOptions} role="radiogroup" aria-label={i18n('task.agent.runtime')}>
        <button
          type="button"
          role="radio"
          aria-checked={runtimeType !== 'EXTERNAL_AGENT'}
          className={`${styles.runtimeOption} ${runtimeType !== 'EXTERNAL_AGENT' ? styles.runtimeOptionSelected : ''}`}
          onClick={() => onChange('EMBEDDED_SPRING_AI')}
        >
          <span className={styles.runtimeOptionLogo}>
            <img src={CHAT2DB_AGENT_AVATAR} alt="Chat2DB" className={styles.runtimeProviderLogo} />
          </span>
          <span className={styles.runtimeOptionCopy}>
            <strong>Spring AI</strong>
            <small>{i18n('task.agent.runtimeSpringHint')}</small>
          </span>
          {runtimeType !== 'EXTERNAL_AGENT' && <Check className={styles.runtimeOptionCheck} size={15} />}
        </button>

        {options.map((option) => {
          const selected = runtimeType === 'EXTERNAL_AGENT' && runtimeProfileId === option.profileId;
          return (
            <button
              key={option.profileId}
              type="button"
              role="radio"
              aria-checked={selected}
              disabled={!option.installed}
              className={`${styles.runtimeOption} ${selected ? styles.runtimeOptionSelected : ''}`}
              onClick={() => onChange('EXTERNAL_AGENT', option.profileId)}
            >
              <span className={styles.runtimeOptionLogo}>
                <RuntimeProviderLogo provider={option.provider} className={styles.runtimeProviderLogo} />
              </span>
              <span className={styles.runtimeOptionCopy}>
                <strong>{runtimeProviderName(option.provider)}</strong>
                <small>{option.providerVersion || i18n('task.agent.runtimeLocal')}</small>
              </span>
              <span className={styles.runtimeOptionState}>
                <i data-online={option.online} />
                {runtimeState(option)}
                {option.online && option.maxConcurrency ? (
                  <small>{option.activeRuns || 0}/{option.maxConcurrency}</small>
                ) : null}
              </span>
              {selected && <Check className={styles.runtimeOptionCheck} size={15} />}
            </button>
          );
        })}

        {loading && !options.length && [0, 1, 2].map((item) => (
          <div key={item} className={styles.runtimeOptionSkeleton}>
            <Skeleton.Avatar active size={34} shape="square" />
            <Skeleton active title={{ width: 72 }} paragraph={{ rows: 1, width: 120 }} />
          </div>
        ))}
      </div>
      {!loading && !options.length && (
        <div className={error ? styles.runtimePickerError : styles.runtimePickerEmpty} role="status">
          {i18n(error ? 'task.agent.runtimeLoadFailed' : 'task.agent.runtimeEmpty')}
        </div>
      )}
    </div>
  );
}
