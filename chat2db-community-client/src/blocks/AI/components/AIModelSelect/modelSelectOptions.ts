import type { ReactNode } from 'react';

export interface ModelSelectOption {
  label: ReactNode;
  value: string;
  isDefault?: boolean;
  className?: string;
}

export interface SelectedModelValue {
  label: string;
  value: string;
}

export const CUSTOM_MODEL_ENTRY_OPTION_VALUE = 'chat2db://action/custom-model';

export const isCustomModelEntryOption = (value: unknown): boolean => value === CUSTOM_MODEL_ENTRY_OPTION_VALUE;

export const isModelOptionAvailable = (
  options: readonly Pick<ModelSelectOption, 'value'>[] | undefined,
  selectedModel: Pick<SelectedModelValue, 'value'> | null,
): boolean => !!selectedModel && !!options?.some((option) => option.value === selectedModel.value);

export const shouldOpenCustomModelDirectly = (
  options: readonly ModelSelectOption[] | undefined,
  showCustomModelEntry: boolean,
): boolean => showCustomModelEntry && options !== undefined && options.length === 0;

export const resolveSelectedModel = (
  options: readonly (Pick<ModelSelectOption, 'value' | 'isDefault'> & { label: string })[],
  selectedModel: SelectedModelValue | null,
): SelectedModelValue | null => {
  const currentOption = selectedModel ? options.find((option) => option.value === selectedModel.value) : undefined;
  if (currentOption) {
    return {
      value: currentOption.value,
      label: currentOption.label,
    };
  }

  const fallbackOption = options.find((option) => option.isDefault) || options[0];
  if (!fallbackOption) {
    return null;
  }
  return {
    value: fallbackOption.value,
    label: fallbackOption.label,
  };
};

export const appendCustomModelEntryOption = (
  options: readonly ModelSelectOption[] | undefined,
  customModelEntry: ReactNode,
  className?: string,
): ModelSelectOption[] | undefined => {
  if (!customModelEntry) {
    return options ? [...options] : undefined;
  }

  return [
    ...(options || []),
    {
      label: customModelEntry,
      value: CUSTOM_MODEL_ENTRY_OPTION_VALUE,
      ...(className ? { className } : {}),
    },
  ];
};
