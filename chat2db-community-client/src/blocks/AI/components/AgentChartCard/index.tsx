import { memo, useMemo, useState } from 'react';
import { Alert, Button } from 'antd';
import { createStyles } from 'antd-style';
import ChartCard from '@/blocks/BI/ChartCard';
import ScrollableTable from '@/components/ScrollableTable';
import i18n from '@/i18n';
import { AgentChart, agentChartDetail, isPartialChart } from '../../agentCharts';

const useStyles = createStyles(({ css, token }) => ({
  figure: css`margin: 10px 0; width: 100%; max-width: 720px;`,
  card: css`border: 1px solid ${token.colorBorder}; border-radius: 12px; overflow: hidden;`,
  switcher: css`
    display: inline-flex;
    gap: 4px;
    margin-bottom: 8px;
    padding: 2px;
    border-radius: 7px;
    background: ${token.colorFillTertiary};
  `,
}));

export default memo(({ chart }: { chart: AgentChart }) => {
  const { styles } = useStyles();
  const [view, setView] = useState<'chart' | 'table'>('chart');
  const detail = useMemo(() => agentChartDetail(chart), [chart]);
  const fields = [...new Set(chart.data.flatMap((row) => Object.keys(row)))];
  return (
    <figure className={styles.figure} aria-label={chart.title} data-agent-chart-id={chart.id}>
      <div className={styles.switcher} role="tablist" aria-label={chart.title}>
        <Button size="small" type={view === 'chart' ? 'primary' : 'text'}
          role="tab" aria-selected={view === 'chart'} onClick={() => setView('chart')}
        >
          {i18n('stream.chart.chartView')}
        </Button>
        <Button size="small" type={view === 'table' ? 'primary' : 'text'}
          role="tab" aria-selected={view === 'table'} onClick={() => setView('table')}
        >
          {i18n('stream.chart.tableView')}
        </Button>
      </div>
      {view === 'chart' ? <ChartCard chartDetail={detail} className={styles.card}
        style={{ height: 340 }} isEditPermission={false}
                          /> : (
        <ScrollableTable aria-label={i18n('stream.chart.queryData')}>
            <thead><tr>{fields.map((field) => <th key={field}>{field}</th>)}</tr></thead>
            <tbody>{chart.data.map((row, index) => (
              <tr key={index}>{fields.map((field) => <td key={field}>{row[field] == null ? 'NULL' : row[field]}</td>)}</tr>
            ))}</tbody>
        </ScrollableTable>
      )}
      {isPartialChart(chart) && <Alert type="warning" showIcon message={i18n('stream.chart.partialResult')} />}
      {chart.warnings.map((warning) => <Alert key={warning} type="warning" message={warning} />)}
    </figure>
  );
});
