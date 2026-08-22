import { createStyles } from 'antd-style';

export const useStyles = createStyles(({ css }) => ({
  filters: css`
    display: flex;
    flex: 1;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    min-width: 0;
  `,
  scopeFilter: css`
    min-width: 120px;
    flex: 1 1 140px;
  `,
  searchFilter: css`
    min-width: 160px;
    flex: 2 1 220px;
  `,
}));
