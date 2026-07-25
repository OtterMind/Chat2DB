import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    modelSelect: css`
      min-width: 108px;
      max-width: 190px;
      font-size: 11px;
      border-radius: 999px;
      background: transparent;
      transition: background-color 0.2s ease, color 0.2s ease;

      &:hover {
        background: ${token.colorFillTertiary};
      }

      & .ant-select-selector {
        height: 24px !important;
        padding: 0 10px !important;
        font-size: 12px !important;
      }

      & .ant-select-selection-item {
        max-width: 150px;
        color: ${token.colorText};
        font-weight: 500;
      }

      & .ant-select-selection-placeholder {
        color: ${token.colorTextSecondary};
        font-weight: 500;
      }

      &.ant-select-focused {
        background: ${token.colorFillTertiary};
      }
    `,
    popupSelect: css`
      min-width: 260px !important;
      padding: 6px !important;

      & .ant-select-item {
        font-size: 12px !important;
        min-height: 0px !important;
        padding: 4px 8px !important;
      }
    `,
    dropdownDivider: css`
      margin: 6px 0 !important;
    `,
    customModelEntry: css`
      width: calc(100% - 8px);
      margin: 4px;
      padding: 10px 12px;
      display: flex;
      align-items: center;
      gap: 10px;
      border: 1px solid transparent;
      border-radius: 12px;
      background: linear-gradient(135deg, ${token.colorPrimaryBg} 0%, ${token.colorFillQuaternary} 100%);
      color: ${token.colorText};
      font: inherit;
      text-align: left;
      cursor: pointer;
      transition: border-color 0.2s ease, background 0.2s ease, transform 0.2s ease, box-shadow 0.2s ease;

      &:hover {
        border-color: transparent;
        background: linear-gradient(135deg, ${token.colorPrimaryBg} 0%, ${token.colorFillTertiary} 100%);
        box-shadow: 0 8px 20px rgba(0, 0, 0, 0.08);
        transform: translateY(-1px);
      }

      &:active {
        transform: translateY(0);
      }
    `,
    customModelIcon: css`
      width: 28px;
      height: 28px;
      flex: 0 0 28px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border-radius: 9px;
      color: ${token.colorPrimary};
      background: ${token.colorBgElevated};
      box-shadow: inset 0 0 0 1px ${token.colorPrimaryBorder};
    `,
    customModelContent: css`
      min-width: 0;
      flex: 1;
      display: flex;
      flex-direction: column;
      gap: 2px;
    `,
    customModelTitle: css`
      color: ${token.colorText};
      font-size: 13px;
      font-weight: 600;
      line-height: 18px;
    `,
    customModelHint: css`
      color: ${token.colorTextSecondary};
      font-size: 11px;
      line-height: 16px;
      white-space: normal;
    `,
    customModelArrow: css`
      flex: 0 0 auto;
      color: ${token.colorPrimary};
      font-size: 11px;
    `,
  };
});
