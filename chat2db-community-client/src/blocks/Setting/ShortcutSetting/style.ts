import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    container: css`
      padding-top: 24px;
      display: flex;
      flex-direction: column;
      gap: 20px;
    `,
    tableWrapper: css`
      .ant-table {
        background: ${token.colorBgContainer};
      }
    `,
    toolbar: css`
      display: flex;
      justify-content: flex-end;
    `,
    groupList: css`
      display: flex;
      flex-direction: column;
      gap: 24px;
    `,
    groupSection: css`
      display: flex;
      flex-direction: column;
      gap: 10px;
    `,
    groupHeader: css`
      width: fit-content;
      height: 28px;
      padding: 0;
      display: inline-flex;
      align-items: center;
      gap: 6px;
      color: ${token.colorText};

      &:hover {
        color: ${token.colorText};
        background: transparent;
      }
    `,
    groupArrow: css`
      align-items: center;
      color: ${token.colorTextSecondary};
      display: flex;
      flex: 0 0 14px;
      height: 14px;
      justify-content: center;
      width: 14px;
    `,
    groupTitle: css`
      margin: 0;
      color: ${token.colorText};
      font-size: ${token.fontSizeLG}px;
      font-weight: 600;
      line-height: ${token.lineHeightLG};
    `,
    actionGroup: css`
      display: flex;
      gap: 8px;
    `,
  };
});
