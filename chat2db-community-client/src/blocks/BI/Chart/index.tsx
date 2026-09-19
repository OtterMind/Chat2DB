import React, { memo, useMemo } from 'react';
import { ChartType } from './constants';
import BarChart from '@/blocks/BI/Chart/charts/BarChart';
import LineChart from '@/blocks/BI/Chart/charts/LineChart';
import PieChart from '@/blocks/BI/Chart/charts/PieChart';
import FunnelChart from '@/blocks/BI/Chart/charts/FunnelChart';
import WordCloudChart from '@/blocks/BI/Chart/charts/WordCloudChart';
import ValueChart from '@/blocks/BI/Chart/charts/ValueChart';
import ComboChart from '@/blocks/BI/Chart/charts/ComboChart';
import TableChart from '@/blocks/BI/Chart/charts/TableChart';
import { DivProps } from '@/typings/common';
import { IChartItem } from '@/typings/dashboard';
import { newFormattedSqlExecuteData } from '@/utils/dashboard';
import { completeSchema } from './form/ChartTypeAndDataForm/transform';

export interface ChartProps extends DivProps {
  className?: string;
  emptyComment?: React.ReactNode;
  chartDetail?: IChartItem;
}

const Chart = (props: ChartProps) => {
  const { chartDetail } = props;
  let { chartSchema } = chartDetail || {};
  const { metaData } = chartDetail || {};
  const { chartType } = chartSchema || {};

  chartSchema = completeSchema(chartSchema);

  // parameter normalization
  const data = useMemo(() => {
    return metaData ? newFormattedSqlExecuteData(metaData) : chartSchema?.data || [];
  }, [metaData, chartSchema?.data]);

  const dispatcher = () => {
    if (!chartSchema) {
      return null;
    }
    switch (chartType) {
      case ChartType.Bar:
        return <BarChart direction="horizontal" data={data} chartSchema={chartSchema} />;
      case ChartType.Column:
      case ChartType.Scatter:
        return <BarChart data={data} chartSchema={chartSchema} />;
      case ChartType.Line:
      case ChartType.AreaLine:
      case ChartType.SmoothLine:
      case ChartType.StepLine:
        return <LineChart data={data} chartSchema={chartSchema} />;
      case ChartType.Pie:
      case ChartType.RingPie:
      case ChartType.RosePie:
        return <PieChart data={data} chartSchema={chartSchema} />;
      case ChartType.Funnel:
        return <FunnelChart data={data} chartSchema={chartSchema} />;
      case ChartType.WordCloud:
        return <WordCloudChart data={data} chartSchema={chartSchema} />;
      case ChartType.Statistics:
        return <ValueChart data={data} chartSchema={chartSchema} />;
      case ChartType.Combo:
        return <ComboChart data={data} chartSchema={chartSchema} />;
      case ChartType.Table:
        return <TableChart data={data} headerList={metaData?.headerList || []} />;
      default:
        return null;
    }
  };

  return dispatcher();
};

export default memo(Chart);
