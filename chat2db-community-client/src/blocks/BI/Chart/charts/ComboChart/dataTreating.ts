import { INormalizedData, ChartSchema } from '@/blocks/BI/Chart/typings';
import { ChartType, LineType, OrderByType } from '../../constants';
import { sortChartDataIndices } from '../sortChartData';

export interface DataTreatingProps {
  data?: INormalizedData;
  chartSchema: ChartSchema;
}

export const barDataTreating = (props: DataTreatingProps) => {
  const { data, chartSchema } = props;
  const { orderByType, orderByRule, comboYAxisData, lineType } = chartSchema;
  let { xField } = chartSchema;
  // dynamically searches for keys in data
  if (data && data.length > 0) {
    xField = Object.keys(data[0]).find((key) => key.toLowerCase() === xField?.toLowerCase()) || xField;
  }

  let xAxisData: (number | string)[] = [];
  let yAxisData: (number | string)[] = [];
  let xAxis: any = null;
  let series: any = [];

  if (xField) {
    data?.forEach((item) => {
      xAxisData.push(item[xField]);
    });
  }

  const getRenderType = (_chartType)=> {
    switch (_chartType) {
      case ChartType.Column:
        return 'bar';
      case ChartType.Scatter:
        return 'scatter';
      default:
        return 'line';
    }
  }


  series =
    comboYAxisData?.map((item) => {
      const field = item.field;
      if (!field) {
        return null;
      }

      return {
        roseType: 'radius',
        animationType: 'scale',
        animationEasing: 'elasticOut',
        label: {
          show: true,
          position: 'top',
        },
        ...(item.chartType === ChartType.AreaLine
          ? {
              areaStyle: {},
              xAxisIndex: 1,
            }
          : {}),
        ...(lineType === LineType.Smooth ? { smooth: true } : {}),
        ...(lineType === LineType.Step
          ? {
              step: 'end',
              xAxisIndex: 1,
            }
          : {}),
        name: field,
        type: getRenderType(item.chartType),
        yAxisIndex: item.axisPosition === 'right' ? 1 : 0,
        data: data?.map((d) => d[field]) || [],
      };
    }) || [];
  
  if (orderByType === OrderByType.X_AXIS || orderByType === OrderByType.Y_AXIS) {
    const isXAxis = orderByType === OrderByType.X_AXIS;
    const dataToSort = isXAxis ? xAxisData : yAxisData;

    const sortedIndices = sortChartDataIndices(dataToSort, orderByRule);

    xAxisData = sortedIndices.map((index) => xAxisData[index]);
    yAxisData = sortedIndices.map((index) => yAxisData[index]);
  }

  xAxis = {
    type: 'category',
    data: xAxisData,
  };

  return {
    xAxis,
    series,
  };
};
