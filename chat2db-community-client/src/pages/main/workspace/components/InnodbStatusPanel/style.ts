import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    root: css`
      height: 100%;
      display: flex;
      flex-direction: column;
      min-height: 0;
    `,
    header: css`
      flex-shrink: 0;
      min-height: 44px;
      padding: 6px 10px;
      border-bottom: 1px solid ${token.colorBorder};
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      box-sizing: border-box;
    `,
    headerTitle: css`
      min-width: 0;
      display: flex;
      flex-direction: column;
      gap: 2px;
    `,
    title: css`
      font-weight: 600;
      line-height: 20px;
    `,
    subtitle: css`
      color: ${token.colorTextTertiary};
      font-size: 12px;
      line-height: 16px;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    `,
    body: css`
      flex: 1;
      min-height: 0;
      overflow: auto;
      padding: 10px;
      box-sizing: border-box;
    `,
    tabs: css`
      height: 100%;

      .ant-tabs-content,
      .ant-tabs-tabpane {
        height: 100%;
      }
    `,
    sectionText: css`
      margin: 0;
      padding: 8px;
      border: 1px solid ${token.colorBorderSecondary};
      background: ${token.colorFillQuaternary};
      border-radius: 4px;
      white-space: pre-wrap;
      overflow: auto;
      font-size: 12px;
      line-height: 18px;
    `,
    rawText: css`
      height: 100%;
      min-height: 320px;
    `,
    stack: css`
      display: flex;
      flex-direction: column;
      gap: 10px;
    `,
    transaction: css`
      border: 1px solid ${token.colorBorderSecondary};
      border-radius: 4px;
      padding: 8px;
      display: flex;
      flex-direction: column;
      gap: 6px;
    `,
    lockList: css`
      margin: 0;
      padding-left: 18px;
      color: ${token.colorTextSecondary};
    `,
    sql: css`
      margin: 0;
      padding: 6px;
      background: ${token.colorFillQuaternary};
      border-radius: 4px;
      white-space: pre-wrap;
      font-size: 12px;
      line-height: 18px;
    `,
  };
});
