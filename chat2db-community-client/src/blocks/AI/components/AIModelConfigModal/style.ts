import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    layout: css`
      display: flex;
      gap: 16px;
      min-height: 520px;
    `,
    sidebar: css`
      width: 280px;
      flex-shrink: 0;
      border-right: 1px solid ${token.colorBorderSecondary};
      padding-right: 12px;
      display: flex;
      flex-direction: column;
      gap: 12px;
      overflow: hidden;
    `,
    sidebarHeader: css`
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 8px;
    `,
    list: css`
      display: flex;
      flex-direction: column;
      gap: 8px;
      overflow: auto;
      min-height: 0;
      padding-right: 4px;
    `,
    listItem: css`
      border: 1px solid ${token.colorBorderSecondary};
      border-radius: 10px;
      padding: 10px 12px;
      cursor: pointer;
      transition: border-color 0.2s ease, background-color 0.2s ease;

      &:hover {
        border-color: ${token.colorPrimaryBorder};
        background: ${token.colorFillQuaternary};
      }
    `,
    listItemActive: css`
      border-color: ${token.colorPrimary};
      background: ${token.colorPrimaryBg};
    `,
    listItemTitle: css`
      font-weight: 600;
      color: ${token.colorText};
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 4px;
    `,
    listItemMeta: css`
      font-size: 12px;
      color: ${token.colorTextSecondary};
      display: flex;
      flex-direction: column;
      gap: 4px;
    `,
    listItemActions: css`
      margin-top: 10px;
      display: flex;
      gap: 8px;
    `,
    right: css`
      flex: 1;
      min-width: 0;
      display: flex;
      flex-direction: column;

      .ant-form-item {
        margin-bottom: 12px;
      }

      .ant-form-item-label {
        padding: 0 8px 0 0;
      }

      .ant-form-item-control-input {
        min-height: 32px;
      }

      .ant-form-item-label > label::after {
        content: none;
      }
    `,
    switchRow: css`
      display: flex;
      align-items: flex-start;
      gap: 24px;
      flex-wrap: wrap;
    `,
    switchField: css`
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 4px;
      flex: 0 0 120px;
    `,
    switchLabel: css`
      min-height: 22px;
      color: ${token.colorText};
      font-size: ${token.fontSize}px;
      line-height: 22px;
    `,
    formActions: css`
      display: flex;
      justify-content: flex-end;
      gap: 12px;
      margin-top: auto;
      padding-top: 12px;
    `,
    testResult: css`
      max-height: 320px;
      overflow-y: auto;
      margin: 0;
      white-space: pre-wrap;
      word-break: break-word;
      font-size: 12px;
      line-height: 1.5;
    `,
    empty: css`
      border: 1px dashed ${token.colorBorderSecondary};
      border-radius: 10px;
      padding: 20px 16px;
      color: ${token.colorTextSecondary};
      text-align: center;
    `,
    tagRow: css`
      display: flex;
      gap: 6px;
      flex-wrap: wrap;
    `,
  };
});
