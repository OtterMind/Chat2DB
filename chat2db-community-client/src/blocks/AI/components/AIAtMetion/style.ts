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
    `,
    content: css``,
    dropdownLayout: css`
      display: flex;
      align-items: stretch;
      max-width: min(680px, calc(100vw - 32px));
      overflow: hidden;
    `,
    menuPane: css`
      position: relative;
      min-width: 280px;
      max-width: 340px;
      overflow: hidden;
    `,
    loadingMore: css`
      position: absolute;
      bottom: 8px;
      left: 50%;
      z-index: 1;
      display: flex;
      align-items: center;
      justify-content: center;
      width: 28px;
      height: 28px;
      border: 1px solid ${token.colorBorderSecondary};
      border-radius: 4px;
      background: ${token.colorBgElevated};
      transform: translateX(-50%);
    `,
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
      min-width: 0;
      gap: 6px;
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
      color: ${token.colorTextDescription};
      font-size: 11px;
    `,
    previewPane: css`
      width: 320px;
      min-width: 260px;
      max-height: 280px;
      padding: 14px 16px;
      overflow: auto;
      border-inline-start: 1px solid ${token.colorBorderSecondary};
      background: ${token.colorBgElevated};
    `,
    previewHeader: css`
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 8px;
      margin-bottom: 10px;
    `,
    previewType: css`
      display: inline-flex;
      align-items: center;
      min-height: 22px;
      padding: 2px 7px;
      border-radius: 4px;
      font-size: 11px;
      line-height: 18px;
      background: ${token.colorFillTertiary};
    `,
    previewTitle: css`
      color: ${token.colorText};
      font-size: 14px;
      line-height: 20px;
      word-break: break-word;
    `,
    previewContent: css`
      color: ${token.colorTextSecondary};
      font-size: 13px;
      line-height: 22px;
      white-space: pre-wrap;
      word-break: break-word;
    `,
    previewSql: css`
      padding: 10px;
      border-radius: 4px;
      background: ${token.colorFillQuaternary};
      color: ${token.colorText};
      font-family: ${token.fontFamilyCode};
      line-height: 20px;
    `,
    knowledgeTerm: css`
      color: ${token.colorInfo};
    `,
    businessLogic: css`
      color: ${token.colorSuccess};
    `,
    sqlTemplate: css`
      color: ${token.colorWarning};
    `,
  };
});
