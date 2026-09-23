import { memo, useMemo } from 'react';
import EChartsContainer from '@/blocks/BI/Chart/components/EChartsContainer';
import { useStyles } from '@/blocks/BI/ChartCard/style';
import type { AgentChart } from '../../agentCharts';
import { buildAgentChartOption } from './option';

export default memo(({ chart, className }: { chart: AgentChart; className: string }) => {
  const { styles, cx, theme } = useStyles();
  const option = useMemo(() => buildAgentChartOption(chart, {
    border: theme.colorBorder,
    text: theme.colorText,
  }), [chart, theme.colorBorder, theme.colorText]);

  return (
    <div className={cx(styles.chatCard, className)} style={{ height: 340 }}>
      <div className={styles.header}><div className={styles.title} title={chart.title}>{chart.title}</div></div>
      <div className={styles.body}><EChartsContainer option={option} /></div>
    </div>
  );
});
