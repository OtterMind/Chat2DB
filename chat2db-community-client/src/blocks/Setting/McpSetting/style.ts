import { createStyles } from 'antd-style';

export const useMcpStyles = createStyles(({ css }) => ({
  serviceControls: css`
    width: 100%;
    max-width: 640px;
    display: flex;
    flex-direction: column;
    gap: 14px;
  `,
  actionRow: css`
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 12px;
  `,
}));
