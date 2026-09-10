import { memo, useMemo } from 'react';
import { Alert } from 'antd';
import { createStyles } from 'antd-style';
import ChartCard from '@/blocks/BI/ChartCard';
import i18n from '@/i18n';
import { AgentChart, agentChartDetail, isPartialChart } from '../../agentCharts';

const useStyles = createStyles(({ css, token }) => ({
  figure: css`margin: 10px 0; width: 100%; max-width: 720px;`,
  card: css`height: 340px; border: 1px solid ${token.colorBorder}; border-radius: 12px; overflow: hidden;`,
  details: css`
    margin-top: 8px; color: ${token.colorTextSecondary};
    summary { cursor: pointer; }
    table { width: 100%; border-collapse: collapse; }
    th, td { padding: 4px 8px; text-align: left; border-bottom: 1px solid ${token.colorBorderSecondary}; }
  `,
  rows: css`max-height: 240px; overflow: auto;`,
}));

export default memo(({ chart }: { chart: AgentChart }) => {
  const { styles } = useStyles();
  const detail = useMemo(() => agentChartDetail(chart), [chart]);
  const fields = Object.keys(chart.data[0] || {});
  return (
    <figure className={styles.figure} aria-label={chart.title} data-agent-chart-id={chart.id}>
      <ChartCard chartDetail={detail} className={styles.card} isEditPermission={false} />
      {isPartialChart(chart) && <Alert type="warning" showIcon message={i18n('stream.chart.partialResult')} />}
      {chart.warnings.map((warning) => <Alert key={warning} type="warning" message={warning} />)}
      <details className={styles.details}>
        <summary>{i18n('stream.chart.viewQueryData')} ({chart.data.length})</summary>
        <div className={styles.rows}>
          <table aria-label={i18n('stream.chart.queryData')}>
            <thead><tr>{fields.map((field) => <th key={field}>{field}</th>)}</tr></thead>
            <tbody>{chart.data.map((row, index) => (
              <tr key={index}>{fields.map((field) => <td key={field}>{row[field] === null ? 'NULL' : row[field]}</td>)}</tr>
            ))}</tbody>
          </table>
        </div>
      </details>
    </figure>
  );
});
