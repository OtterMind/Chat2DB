import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    consoleOptionsWrapper: css`
      flex-shrink: 0;
      height: 34px;
      display: flex;
      align-items: center;
      display: flex;
      padding: 0px 4px 0px 8px;
      justify-content: space-between;
      align-items: center;
    `,
    consoleOptionsLeft: css`
      flex-shrink: 0;
      display: flex;
      align-items: center;
      gap: 6px;
      color: ${token.colorTextSecondary};
    `,
    iconButtonPlay: css`
      color: ${token.colorSuccess};
      cursor: pointer;
      &:hover {
        color: ${token.colorSuccess};
      }
    `,
    partingLine: css`
      width: 1px;
      height: 16px;
      background-color: ${token.colorBorder};
    `,
    operatingButtonIcon: css`
     
    `,
    transactionControls: css`
      display: inline-flex;
      height: 28px;
      align-items: center;
      gap: 2px;
    `,
    transactionModeTrigger: css`
      height: 26px !important;
      padding: 0 6px !important;
      display: inline-flex;
      align-items: center;
      gap: 4px;
      border-radius: 4px !important;
      color: ${token.colorTextSecondary};
      font-size: 12px;
      font-weight: 500;

      &:hover:not(:disabled),
      &[aria-expanded='true'] {
        color: ${token.colorText};
        background: ${token.colorFillTertiary};
      }
    `,
    transactionModeTriggerManual: css`
      color: ${token.colorPrimary};
      background: ${token.colorPrimaryBg};

      &:hover:not(:disabled),
      &[aria-expanded='true'] {
        color: ${token.colorPrimaryHover};
        background: ${token.colorPrimaryBgHover};
      }
    `,
    transactionModePrefix: css`
      color: ${token.colorTextTertiary};
      font-size: 11px;
      font-weight: 600;
    `,
    transactionModeChevron: css`
      flex-shrink: 0;
      color: ${token.colorTextTertiary};
    `,
    transactionActionButton: css`
      width: 26px !important;
      min-width: 26px !important;
      height: 26px !important;
      padding: 0 !important;
      border-radius: 4px !important;
      color: ${token.colorTextSecondary};
    `,
    transactionCommitButton: css`
      &:not(:disabled) {
        color: ${token.colorSuccess};
      }

      &:hover:not(:disabled) {
        color: ${token.colorSuccess};
        background: ${token.colorSuccessBg};
      }
    `,
    transactionRollbackButton: css`
      &:not(:disabled) {
        color: ${token.colorError};
      }

      &:hover:not(:disabled) {
        color: ${token.colorError};
        background: ${token.colorErrorBg};
      }
    `,
    transactionModeDropdown: css`
      .ant-dropdown-menu {
        min-width: 184px;
        padding: 4px;
        border-radius: 6px;
      }

      .ant-dropdown-menu-item-group-title {
        box-sizing: border-box;
        height: 18px !important;
        padding: 0 8px !important;
        line-height: 18px;
      }

      .ant-dropdown-menu-item-group-list {
        margin: 0;
      }

      .ant-dropdown-menu-item {
        box-sizing: border-box;
        height: 26px !important;
        min-height: 26px;
        padding: 2px 8px !important;
        line-height: 22px;
        border-radius: 4px;
        font-size: 12px;
      }

      .ant-dropdown-menu-item-divider {
        margin: 4px 0;
      }

      .ant-dropdown-menu-item-icon {
        width: 14px;
        min-width: 14px;
        margin-inline-end: 6px;
      }
    `,
    transactionModeGroupLabel: css`
      color: ${token.colorTextTertiary};
      font-size: 11px;
      font-weight: 600;
      line-height: 18px;
    `,
    transactionModeCheckPlaceholder: css`
      display: block;
      width: 14px;
      height: 14px;
    `,
  };
});
