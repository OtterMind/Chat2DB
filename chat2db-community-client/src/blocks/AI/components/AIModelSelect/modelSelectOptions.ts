import type { ReactNode } from 'react';

export interface ModelSelectOption {
  label: ReactNode;
  value: string;
  isDefault?: boolean;
  /** Ant Design Select option disabled flag for non-selectable snapshots. */
  disabled?: boolean;
  className?: string;
}

export const CUSTOM_MODEL_ENTRY_OPTION_VALUE = 'chat2db://action/custom-model';

export const isCustomModelEntryOption = (value: unknown): boolean => value === CUSTOM_MODEL_ENTRY_OPTION_VALUE;

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
