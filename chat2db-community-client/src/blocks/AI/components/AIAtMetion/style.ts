import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    container: css`
      &.ant-slide-up-leave,
      &.ant-slide-up-leave-active {
        visibility: hidden;
      }

      .ant-cascader-dropdown {
        min-width: 280px !important;
      }

      .ant-cascader-menu {
        min-width: 280px !important;
        width: min(340px, calc(100vw - 32px)) !important;
        max-width: min(340px, calc(100vw - 32px)) !important;
        height: auto !important;
        max-height: 280px !important;
        overflow-y: auto;
        border-inline-end: 0 !important;
      }

      .ant-cascader-menu-item {
        min-height: 36px;
        padding: 6px 10px !important;
        font-size: 13px;
      }

      .ant-cascader-menu-item-content {
        width: 100%;
        min-width: 0;
      }
    `,
    content: css``,
    optionRow: css`
      display: flex;
      align-items: center;
      justify-content: space-between;
      width: 100%;
      min-width: 0;
      gap: 12px;
    `,
    optionTitle: css`
      display: flex;
      align-items: center;
      flex: 1;
      min-width: 0;
      gap: 6px;
      overflow: hidden;
      color: ${token.colorText};
      font-weight: 500;
    `,
    optionLabel: css`
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    `,
    optionExtra: css`
      flex-shrink: 0;
      width: 36px;
      color: ${token.colorTextDescription};
      font-size: 11px;
    `,
  };
});
