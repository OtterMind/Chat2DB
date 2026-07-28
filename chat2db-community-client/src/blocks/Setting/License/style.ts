import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    wrapper: css`
      padding-top: 24px;
      display: flex;
      flex-direction: column;
      gap: 16px;
    `,
    colWrapper: css`
      display: flex;
      justify-content: space-between;
      align-items: center;
      border-radius: 6px;
      border: 1px solid ${token.colorBorderSecondary};
      padding: 24px 22px;
    `,
    colTitle: css`
      font-size: 16px;
      font-weight: 500;
      color: ${token.colorText};
    `,
    colStatus: css`
      font-size: 14px;
      color: ${token.colorTextSecondary};
    `,
  };
});
