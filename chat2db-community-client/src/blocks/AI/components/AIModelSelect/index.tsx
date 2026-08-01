import React, { useEffect, useMemo, useState } from 'react';
import { Select } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { ChevronDown, ChevronRight } from 'lucide-react';
import { useStyles } from './style';
import i18n from '@/i18n';
import { useAIStore } from '@/store/ai/store';
import { SelectedModelOption } from '@/store/ai/slices/model/initialState';
import {
  isSubscriptionConnectOption,
  parseSubscriptionConnectProvider,
  resolveChatGptConnectEntry,
  subscriptionConnectOptionValue,
} from '@/blocks/AI/subscription/modelSelectGroups';
import { useSubscriptionAiStore } from '@/store/aiSubscription';
import type { AiProviderId } from '@/typings/aiSubscription';
import { useGlobalStore } from '@/store/global';
import { isCommunityEnv, isDesktop } from '@/utils/env';
import { history } from 'umi';
import {
  appendCustomModelEntryOption,
  isCustomModelEntryOption,
  ModelSelectOption,
} from './modelSelectOptions';

interface AIModelSelectProps {
  onChange?: (value: SelectedModelOption | null) => void;
  options?: ModelSelectOption[];
  showCustomModelEntry?: boolean;
  onCustomModelClick?: () => void;
  customModelText?: string;
  openToken?: number;
  /** Optional hook when the dropdown opens (e.g. rebuild model option metadata). */
  onDropdownOpen?: () => void;
}

const AIModelSelect = ({
  onChange,
  options,
  showCustomModelEntry = false,
  onCustomModelClick,
  customModelText,
  openToken = 0,
  onDropdownOpen,
}: AIModelSelectProps) => {
  const { styles } = useStyles();
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const { modelList, selectedModel, setSelectedModel, getModelList } = useAIStore((state) => ({
    modelList: state.modelList,
    selectedModel: state.selectedModel,
    setSelectedModel: state.setSelectedModel,
    getModelList: state.getModelList,
  }));
  const {
    subscriptionHydrated,
    subscriptionSurfaceAvailable,
    subscriptionCapability,
    subscriptionProviders,
    subscriptionErrorCode,
    refreshSubscriptionSurface,
    startConnect,
  } = useSubscriptionAiStore((state) => ({
    subscriptionHydrated: state.hydrated,
    subscriptionSurfaceAvailable: state.surfaceAvailable,
    subscriptionCapability: state.capability,
    subscriptionProviders: state.providers,
    subscriptionErrorCode: state.lastErrorCode,
    refreshSubscriptionSurface: state.refreshSurface,
    startConnect: state.startConnect,
  }));
  const setSettingPageActiveTab = useGlobalStore((state) => state.setSettingPageActiveTab);
  const connectEntry = resolveChatGptConnectEntry({
    communityRuntime: isCommunityEnv,
    packagedJcefDesktop: isDesktop,
    hydrated: subscriptionHydrated,
    surfaceAvailable: subscriptionSurfaceAvailable,
    backendCapability: subscriptionCapability,
    lastErrorCode: subscriptionErrorCode,
    connections: subscriptionProviders,
  });

  useEffect(() => {
    if (openToken > 0) {
      setDropdownOpen(true);
    }
  }, [openToken]);

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
    if (isCustomModelEntryOption(selectedValue.value)) {
      onCustomModelClick?.();
      return;
    }

    if (isSubscriptionConnectOption(selectedValue.value)) {
      const provider = parseSubscriptionConnectProvider(selectedValue.value) as AiProviderId | null;
      // Same entry as API Key model config (product B): open the unified model modal when
      // provided; fall back to settings only if the host did not wire onCustomModelClick.
      if (provider && connectEntry?.action === 'CONNECT') {
        void startConnect(provider);
        return;
      }
      if (onCustomModelClick) {
        onCustomModelClick();
        return;
      }
      setSettingPageActiveTab('subscriptionAi');
      history.push('/settings');
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
    setDropdownOpen(open);
    if (open && isCommunityEnv && isDesktop) {
      void refreshSubscriptionSurface();
      onDropdownOpen?.();
    }
    if (open && (!modelList || modelList.length === 0)) {
      if (options !== undefined) {
        return;
      }
      getModelList();
    }
  };

  const selectOptions = options !== undefined ? options : modelList;
  const optionsWithSubscriptionEntry = useMemo(
    () =>
      connectEntry
        ? [
            {
              label:
                connectEntry.action === 'CONNECT'
                  ? i18n('ai.subscription.model.connectChatGpt')
                  : i18n('ai.subscription.model.openSettingsToRetry'),
              value: subscriptionConnectOptionValue(connectEntry.provider),
            },
            ...(selectOptions || []),
          ]
        : selectOptions,
    [connectEntry?.action, selectOptions],
  );
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
    optionsWithSubscriptionEntry,
    customModelEntry,
    selectOptions?.length ? styles.customModelOption : undefined,
  );

  return (
    <Select
      popupMatchSelectWidth={false}
      className={styles.modelSelect}
      popupClassName={styles.popupSelect}
      variant="borderless"
      labelInValue
      value={selectedModel && selectedModel.label ? selectedModel : undefined}
      onChange={handleChange}
      open={dropdownOpen}
      options={optionsWithCustomModelEntry}
      size="small"
      placeholder={i18n('ai.select.model')}
      suffixIcon={<ChevronDown size={14} />}
      onDropdownVisibleChange={handleDropdownVisibleChange}
    />
  );
};

export default AIModelSelect;
