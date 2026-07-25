import React, { useEffect } from 'react';
import { Select, Divider } from 'antd';
import { PlusOutlined, RightOutlined } from '@ant-design/icons';
import { useStyles } from './style';
import i18n from '@/i18n';
import { useAIStore } from '@/store/ai/store';
import { SelectedModelOption } from '@/store/ai/slices/model/initialState';

// Add props interface with onChange and value
interface AIModelSelectProps {
  onChange?: (value: SelectedModelOption | null) => void;
  options?: Array<{ label: string; value: string; isDefault?: boolean }>;
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

  // Handle select change
  const handleChange = (selectedValue: { value: string; label: React.ReactNode }) => {
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
    if (open && (!modelList || modelList.length === 0)) {
      if (options !== undefined) {
        return;
      }
      getModelList();
    }
  };

  const selectOptions = options !== undefined ? options : modelList;
  const customModelEntry =
    showCustomModelEntry && onCustomModelClick ? (
      <button
        type="button"
        className={styles.customModelEntry}
        onMouseDown={(e) => {
          e.preventDefault();
        }}
        onClick={(e) => {
          e.stopPropagation();
          onCustomModelClick();
        }}
      >
        <span className={styles.customModelIcon}>
          <PlusOutlined />
        </span>
        <span className={styles.customModelContent}>
          <span className={styles.customModelTitle}>{customModelText || i18n('setting.modelConfig.entry')}</span>
          <span className={styles.customModelHint}>{i18n('setting.modelConfig.entryHint')}</span>
        </span>
        <RightOutlined className={styles.customModelArrow} />
      </button>
    ) : null;

  return (
    <Select
      popupMatchSelectWidth={false}
      className={styles.modelSelect}
      popupClassName={styles.popupSelect}
      variant="borderless"
      labelInValue
      value={selectedModel && selectedModel.label ? selectedModel : undefined}
      onChange={handleChange}
      options={selectOptions}
      size="small"
      placeholder={i18n('ai.select.model')}
      // rc-select suppresses an empty popup unless it has explicit empty-state content.
      notFoundContent={customModelEntry}
      onDropdownVisibleChange={handleDropdownVisibleChange}
      dropdownRender={(menu) => {
        if (!customModelEntry || !selectOptions?.length) {
          return menu;
        }
        return (
          <div>
            {menu}
            <Divider className={styles.dropdownDivider} />
            {customModelEntry}
          </div>
        );
      }}
    />
  );
};

export default AIModelSelect;
