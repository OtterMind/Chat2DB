import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => {
  return {
    personalBox: css`
      padding-top: 24px;
      display: flex;
      flex-direction: column;
      gap: 30px;
    `,
    avatarBox: css`
      display: flex;
      gap: 20px;
      align-items: center;
    `,
    avatar: css`
      border-radius: 8px !important;
    `,
    changeAvatarTips: css`
      font-size: 12px;
      color: ${token.colorTextSecondary};
      margin-top: 12px;
    `,
  };
});
