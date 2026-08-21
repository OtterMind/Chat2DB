import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css }) => ({
  switcher: css`
    display: flex;
    align-items: center;
    gap: 2px;
  `,
  buttonSlot: css`
    display: inline-flex;
  `,
}));
