import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css, token }) => ({
  actionBar: css`
    display: flex;
    flex-direction: column;
    align-items: center;
    width: 44px;
    flex-shrink: 0;
    box-sizing: border-box;
    padding: 6px 0;
    border-right: 1px solid ${token.colorBorderLayout};
    background-color: ${token.colorBgBase};
    user-select: none;
    --community-main-action-gap: 8px;
  `,
  navigationActions: css`
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--community-main-action-gap);
  `,
  bottomActions: css`
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--community-main-action-gap);
    margin-top: auto;
  `,
}));
