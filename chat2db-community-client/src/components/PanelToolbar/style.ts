import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  toolbar: css`
    display: flex;
    height: 36px;
    flex: 0 0 36px;
    align-items: center;
    gap: 8px;
    box-sizing: border-box;
    padding: 0 12px;
    border-bottom: 1px solid ${token.colorBorderLayout};
    background: ${token.colorBgContainer};
  `,
  leading: css`
    display: flex;
    min-width: 0;
    flex: 0 0 auto;
    align-items: center;
    font-weight: 600;
  `,
  trailing: css`
    display: flex;
    min-width: 0;
    flex: 1;
    align-items: center;
    justify-content: flex-end;
  `,
}));
