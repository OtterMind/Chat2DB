import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    layout: css`
      display: flex;
      flex-direction: column;
      gap: 14px;
      max-height: min(78vh, 720px);
      overflow: auto;
      padding-right: 4px;
    `,
    section: css`
      display: flex;
      flex-direction: column;
      gap: 6px;
    `,
    sectionLabel: css`
      font-size: 11px;
      font-weight: 600;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      color: ${token.colorTextTertiary};
    `,
    subscriptionSection: css`
      border: 1px solid ${token.colorBorderSecondary};
      border-radius: 12px;
      padding: 12px 14px;
      background: ${token.colorFillQuaternary};
    `,
    apiKeyLayout: css`
      display: flex;
      gap: 12px;
      min-height: 300px;
      border: 1px solid ${token.colorBorderSecondary};
      border-radius: 12px;
      padding: 10px;
      background: ${token.colorBgContainer};
    `,
    sidebar: css`
      width: 220px;
      flex-shrink: 0;
      border-right: 1px solid ${token.colorBorderSecondary};
      padding-right: 12px;
      display: flex;
      flex-direction: column;
      gap: 10px;
      overflow: hidden;
    `,
    sidebarHeader: css`
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 8px;
      font-size: 13px;
      color: ${token.colorTextSecondary};
    `,
    sidebarEmpty: css`
      font-size: 12px;
      color: ${token.colorTextTertiary};
      padding: 8px 4px;
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
      transition:
        border-color 0.2s ease,
        background-color 0.2s ease;

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
    formTitle: css`
      font-weight: 600;
      margin-bottom: 10px;
      color: ${token.colorText};
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
      flex-wrap: wrap;
    `,
    testResult: css`
      max-height: 320px;
      margin: 0;
      white-space: pre-wrap;
      word-break: break-word;
      font-size: 12px;
      line-height: 1.5;
    `,
    empty: css`
      flex: 1;
      border: 1px dashed ${token.colorBorderSecondary};
      border-radius: 10px;
      padding: 32px 16px;
      color: ${token.colorTextSecondary};
      text-align: center;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
    `,
    emptyHint: css`
      margin-top: 6px;
      font-size: 12px;
      color: ${token.colorTextTertiary};
      max-width: 320px;
    `,
    tagRow: css`
      display: flex;
      gap: 6px;
      flex-wrap: wrap;
    `,
  };
});
