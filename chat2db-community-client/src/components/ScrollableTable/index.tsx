import type { TableHTMLAttributes } from 'react';
import { createStyles } from 'antd-style';

const useStyles = createStyles(({ css, token }) => ({
  viewport: css`
    max-width: 100%;
    max-height: 340px;
    overflow: auto;
    margin: 10px 0;
    border: 1px solid ${token.colorBorderSecondary};
    border-radius: 8px;
    &:focus-visible { outline: 2px solid ${token.colorPrimary}; outline-offset: 2px; }
    > table {
      width: max-content;
      min-width: 100%;
      margin: 0;
      border-collapse: separate;
      border-spacing: 0;
      font-size: 13px;
    }
    th, td {
      padding: 8px 12px;
      text-align: left;
      white-space: nowrap;
      border: 0;
      border-bottom: 1px solid ${token.colorBorderSecondary};
    }
    th {
      position: sticky;
      top: 0;
      z-index: 1;
      background: ${token.colorBgElevated};
      color: ${token.colorText};
      font-weight: 600;
    }
    tr:last-child td { border-bottom: 0; }
    tbody tr:nth-child(even) { background: ${token.colorFillQuaternary}; }
  `,
}));

export default function ScrollableTable(props: TableHTMLAttributes<HTMLTableElement>) {
  const { styles } = useStyles();
  return (
    <div className={styles.viewport} tabIndex={0} role="region"
      aria-label={props['aria-label']} data-scrollable-table
    >
      <table {...props} />
    </div>
  );
}
