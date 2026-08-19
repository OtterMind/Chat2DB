import { createStyles } from 'antd-style';

export const useStyles = createStyles(
  ({ css }, { orientation }: { orientation: 'vertical' | 'horizontal' }) => {
    return {
      workspaceExtendNav: css`
        display: flex;
        flex-direction: ${orientation === 'horizontal' ? 'row' : 'column'};
        align-items: center;
        ${orientation === 'horizontal' ? 'height: 100%; padding: 0 2px;' : 'width: 38px; padding: 8px 0;'}
      `,
      topBox: css`
        display: flex;
        flex-direction: ${orientation === 'horizontal' ? 'row' : 'column'};
        align-items: center;
        gap: ${orientation === 'horizontal' ? '2px' : '8px'};
        flex: ${orientation === 'horizontal' ? '0 0 auto' : '1'};
      `,
    };
  },
);
