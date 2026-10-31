import { useState } from 'react';
import { Button, Select } from 'antd';
import { Trash2 } from 'lucide-react';
import i18n from '@/i18n';
import type { ImportTemplate } from '@/blocks/ImportAndExport/utils/importTemplates';
import { useStyles } from './style';

interface IProps {
  templates: ImportTemplate[];
  /** File type the wizard is importing, used for the type label and the empty state. */
  fileType?: string;
  disabled?: boolean;
  onApply: (template: ImportTemplate) => void;
  onDelete: (template: ImportTemplate) => void;
}

/**
 * Applies a stored format preset from the wizard's format step.
 *
 * Only the presets of the file being imported are listed; the owner filters them, so a preset
 * of another type never reaches this component and the options carry no type suffix.
 * Storage access
 * stays in `utils/importTemplates`; this component only collects the user's intent.
 */
export default function ImportTemplateSelect({ templates, fileType, disabled, onApply, onDelete }: IProps) {
  const { styles } = useStyles();
  // The pick stays visible in the select: clearing it after apply looked like nothing happened,
  // and the selected name is the confirmation, so no extra label is rendered next to it.
  const [appliedName, setAppliedName] = useState<string | undefined>(undefined);

  return (
    <div className={styles.templateBar}>
      <Select
        className={styles.templateSelect}
        value={appliedName}
        size="small"
        showSearch
        optionFilterProp="label"
        disabled={disabled}
        aria-label={i18n('workspace.importExport.template.applyPlaceholder')}
        placeholder={i18n('workspace.importExport.template.applyPlaceholder')}
        notFoundContent={
          fileType
            ? i18n('workspace.importExport.template.emptyForType', fileType)
            : i18n('workspace.importExport.template.empty')
        }
        options={templates.map((template) => ({
          value: template.name,
          label: template.name,
        }))}
        optionRender={(option) => {
          const template = templates.find((item) => item.name === option.value);
          return (
            <div className={styles.templateOption}>
              <span className={styles.templateOptionName}>{option.label}</span>
              <Button
                type="text"
                danger
                size="small"
                className={styles.templateOptionDelete}
                aria-label={i18n('workspace.importExport.template.delete')}
                icon={<Trash2 size={14} />}
                onClick={(event) => {
                  // Deleting is a row action; it must not apply the template as well.
                  event.stopPropagation();
                  event.preventDefault();
                  if (template) {
                    onDelete(template);
                  }
                }}
              />
            </div>
          );
        }}
        onChange={(value) => {
          setAppliedName(value);
          const template = templates.find((item) => item.name === value);
          if (template) {
            onApply(template);
          }
        }}
      />
    </div>
  );
}
