import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    confirmContent: css``,
    warningDesc: css`
      color: ${token.colorTextSecondary};
      font-size: 14px;
      line-height: 22px;
    `,
    sectionTitle: css`
      margin-top: 16px;
      margin-bottom: 8px;
      color: ${token.colorTextSecondary};
      font-size: 13px;
      line-height: 20px;
    `,
    sqlPreview: css`
      height: 92px;
      overflow: auto;
      border: 1px solid ${token.colorBorderSecondary};
      border-radius: 6px;
      background: ${token.colorBgContainer};
    `,
    occupyingTables: css`
      max-height: 112px;
      margin: 0;
      padding-left: 20px;
      overflow: auto;
      color: ${token.colorError};
      font-size: 13px;
      line-height: 22px;
    `,
  };
});
