import { Select } from 'antd';
import HelpTooltip from '@/components/HelpTooltip';
import i18n from '@/i18n';
import type { ImportDuplicatePolicy, ImportExecutionMode, ImportFailurePolicy } from '@/typings/importExport';
import ImportModeControl from '../ImportModeControl';
import { useStyles } from './style';

interface Props {
  duplicatePolicy: ImportDuplicatePolicy;
  failurePolicy: ImportFailurePolicy;
  mode: ImportExecutionMode;
  canIgnore: boolean;
  showMode: boolean;
  disabled: boolean;
  onDuplicatePolicyChange: (value: ImportDuplicatePolicy) => void;
  onFailurePolicyChange: (value: ImportFailurePolicy) => void;
  onModeChange: (value: ImportExecutionMode) => void;
}

export default function ImportPolicyFields({
  duplicatePolicy, failurePolicy, mode, canIgnore, showMode, disabled,
  onDuplicatePolicyChange, onFailurePolicyChange, onModeChange,
}: Props) {
  const { styles } = useStyles();
  return (
    <section className={styles.policySection}>
      <h3 className={styles.sectionTitle}>{i18n('workspace.importExport.policy.title')}</h3>
      <div className={styles.policyRow}>
        <div className={styles.policyField}>
          <div className={styles.policyLabel}>
            {i18n('workspace.importExport.policy.duplicateLabel')}
            <HelpTooltip
              label={i18n('workspace.importExport.policy.duplicateLabel')}
              title={i18n(duplicatePolicy === 'IGNORE'
                ? 'workspace.importExport.policy.duplicateIgnoreHint'
                : 'workspace.importExport.policy.duplicateInsertHint')}
            />
          </div>
          <Select<ImportDuplicatePolicy>
            aria-label={i18n('workspace.importExport.policy.duplicateLabel')}
            value={duplicatePolicy}
            disabled={disabled}
            onChange={onDuplicatePolicyChange}
            options={[
              { value: 'INSERT', label: i18n('workspace.importExport.policy.duplicateInsert') },
              { value: 'IGNORE', label: i18n('workspace.importExport.policy.duplicateIgnore'), disabled: !canIgnore },
            ]}
          />
        </div>
        <div className={styles.policyField}>
          <div className={styles.policyLabel}>
            {i18n('workspace.importExport.policy.failureLabel')}
            <HelpTooltip
              label={i18n('workspace.importExport.policy.failureLabel')}
              title={i18n(failurePolicy === 'SKIP_ERROR_ROWS'
                ? 'workspace.importExport.policy.failureSkipHint' : 'workspace.importExport.policy.failureAbortHint')}
            />
          </div>
          <Select<ImportFailurePolicy>
            aria-label={i18n('workspace.importExport.policy.failureLabel')}
            value={failurePolicy}
            disabled={disabled}
            onChange={onFailurePolicyChange}
            options={[
              { value: 'ABORT', label: i18n('workspace.importExport.policy.failureAbort') },
              { value: 'SKIP_ERROR_ROWS', label: i18n('workspace.importExport.policy.failureSkip') },
            ]}
          />
        </div>
      </div>
      {showMode && (
        <div className={styles.policyModeRow}>
          <ImportModeControl value={mode} onChange={onModeChange} disabled={disabled} />
        </div>
      )}
    </section>
  );
}
