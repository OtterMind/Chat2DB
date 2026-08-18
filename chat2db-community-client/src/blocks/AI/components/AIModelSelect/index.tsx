import React, { useEffect } from 'react';
import { Select } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { useStyles } from './style';
import i18n from '@/i18n';
import { useAIStore } from '@/store/ai/store';
import { SelectedModelOption } from '@/store/ai/slices/model/initialState';
import {
  appendCustomModelEntryOption,
  isCustomModelEntryOption,
  isModelOptionAvailable,
  ModelSelectOption,
  shouldOpenCustomModelDirectly,
} from './modelSelectOptions';

interface AIModelSelectProps {
  onChange?: (value: SelectedModelOption | null) => void;
  options?: ModelSelectOption[];
  showCustomModelEntry?: boolean;
  onCustomModelClick?: () => void;
  customModelText?: string;
}

const AIModelSelect = ({
  onChange,
  options,
  showCustomModelEntry = false,
  onCustomModelClick,
  customModelText,
}: AIModelSelectProps) => {
  const { styles } = useStyles();
  const { modelList, selectedModel, setSelectedModel, getModelList } = useAIStore((state) => ({
    modelList: state.modelList,
    selectedModel: state.selectedModel,
    setSelectedModel: state.setSelectedModel,
    getModelList: state.getModelList,
  }));

  useEffect(() => {
    if (options !== undefined) {
      return;
    }
    if (!modelList || modelList.length === 0) {
      getModelList();
    }
  }, [options, modelList?.length]);

  const selectOptions = options !== undefined ? options : modelList;
  const openCustomModelDirectly =
    !!onCustomModelClick && shouldOpenCustomModelDirectly(selectOptions, showCustomModelEntry);

  // Handle select change
  const handleChange = (selectedValue: { value: string; label: React.ReactNode }) => {
    if (isCustomModelEntryOption(selectedValue.value)) {
      onCustomModelClick?.();
      return;
    }

    const nextValue = {
      value: selectedValue.value,
      label: String(selectedValue.label || ''),
    };
    setSelectedModel(nextValue);
    if (onChange) {
      onChange(nextValue);
    }
  };

  // handles the drop-down box opening event
  const handleDropdownVisibleChange = (open: boolean) => {
    if (open && openCustomModelDirectly) {
      return;
    }
    if (open && (!modelList || modelList.length === 0)) {
      if (options !== undefined) {
        return;
      }
      getModelList();
    }
  };

  const handleDirectCustomModelMouseDown = (event: React.MouseEvent<HTMLElement>) => {
    if (!openCustomModelDirectly) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    onCustomModelClick?.();
  };

  const handleDirectCustomModelKeyDown = (event: React.KeyboardEvent<HTMLElement>) => {
    if (!openCustomModelDirectly || !['Enter', ' ', 'ArrowDown'].includes(event.key)) {
      return;
    }
    event.preventDefault();
    event.stopPropagation();
    onCustomModelClick?.();
  };

  const customModelEntry =
    showCustomModelEntry && onCustomModelClick ? (
      <div className={styles.customModelEntry}>
        <span className={styles.customModelIcon}>
          <PlusOutlined />
        </span>
        <span className={styles.customModelContent}>
          <span className={styles.customModelTitle}>{customModelText || i18n('setting.modelConfig.entry')}</span>
          <span className={styles.customModelHint}>{i18n('setting.modelConfig.entryHint')}</span>
        </span>
        <ChevronRight className={styles.customModelArrow} size={14} />
      </div>
    ) : null;
  const optionsWithCustomModelEntry = appendCustomModelEntryOption(
    selectOptions,
    customModelEntry,
    selectOptions?.length ? styles.customModelOption : undefined,
  );
  const visibleSelectedModel =
    options === undefined || isModelOptionAvailable(selectOptions, selectedModel) ? selectedModel : undefined;
  const placeholder = showCustomModelEntry
    ? customModelText || i18n('setting.modelConfig.entry')
    : i18n('ai.select.model');

  return (
    <Select
      popupMatchSelectWidth={false}
      className={styles.modelSelect}
      popupClassName={styles.popupSelect}
      open={openCustomModelDirectly ? false : undefined}
      onMouseDown={handleDirectCustomModelMouseDown}
      onKeyDown={handleDirectCustomModelKeyDown}
      variant="borderless"
      labelInValue
      value={visibleSelectedModel?.label ? visibleSelectedModel : undefined}
      onChange={handleChange}
      options={optionsWithCustomModelEntry}
      size="small"
      placeholder={placeholder}
      suffixIcon={<ChevronDown size={14} />}
      onDropdownVisibleChange={handleDropdownVisibleChange}
    />
  );
};

export default AIModelSelect;
