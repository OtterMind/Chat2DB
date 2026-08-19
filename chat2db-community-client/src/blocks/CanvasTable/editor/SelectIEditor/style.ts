import { createStyles } from 'antd-style';
import type { SelectEditorTheme } from './index';

export const useStyles = createStyles(({ css }, { theme }: { theme: SelectEditorTheme }) => ({
  select: css`
    width: 100%;
    height: 100%;

    &.ant-select-single,
    &.ant-select-multiple {
      height: 100%;
    }

    .ant-select-selector {
      height: 100% !important;
      min-height: 0 !important;
      padding: 0 5px !important;
      border-radius: 0 !important;
      border-color: ${theme.colorBorder} !important;
      color: ${theme.colorText} !important;
      background: ${theme.colorBgContainer} !important;
      box-shadow: none !important;
    }

    &.ant-select-focused .ant-select-selector,
    &:hover .ant-select-selector {
      border-color: ${theme.colorPrimary || theme.colorBorder} !important;
      box-shadow: none !important;
    }

    .ant-select-selection-wrap,
    .ant-select-selection-overflow {
      min-width: 0;
      flex-wrap: nowrap;
      overflow: hidden;
    }

    .ant-select-selection-item {
      max-width: 100%;
      color: ${theme.colorText};
    }

    &.ant-select-multiple .ant-select-selection-item {
      height: 20px;
      margin-block: 1px;
      line-height: 18px;
      border-radius: 2px;
      border-color: ${theme.colorBorderSecondary};
      background: ${theme.colorFillSecondary};
    }

    .ant-select-selection-placeholder {
      color: ${theme.colorTextTertiary};
    }

    .ant-select-arrow,
    .ant-select-clear {
      color: ${theme.colorTextSecondary};
    }
  `,
  popup: css`
    z-index: 9999;
    padding: 4px;
    color: ${theme.colorText};
    background: ${theme.colorBgElevated || theme.colorBgContainer};
    border: 1px solid ${theme.colorBorderSecondary || theme.colorBorder};
    box-shadow: ${theme.boxShadowSecondary || theme.boxShadow};

    .ant-select-item {
      min-height: 28px;
      padding: 4px 8px;
      border-radius: ${Math.min(theme.borderRadius ?? 4, 4)}px;
      color: ${theme.colorText};
    }

    .ant-select-item-option-active:not(.ant-select-item-option-disabled) {
      background: ${theme.colorFillTertiary};
    }

    .ant-select-item-option-selected:not(.ant-select-item-option-disabled) {
      color: ${theme.colorText};
      background: ${theme.colorPrimaryBg};
    }
  `,
}));
