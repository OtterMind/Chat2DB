import React, { forwardRef, useImperativeHandle, useMemo, useRef } from 'react';
import * as charts from 'echarts';
import ReactEcharts from 'echarts-for-react';
import './index.less';
type EChartsOption = charts.EChartsOption;

interface IProps {
  data?: {
    xAxis: string[];
    yAxis: any[];
  };
}

const BarChart = (props: IProps, ref) => {
  const barRef = useRef<any>(null);

  const option: EChartsOption = useMemo(
    () => ({
      xAxis: {
        type: 'category',
        data: props?.data?.xAxis ?? [],
      },
      yAxis: {
        type: 'value',
      },
      series: [
        {
          data: props?.data?.yAxis ?? [],
          type: 'bar',
        },
      ],
        tooltip: {},
    }),
    [props.data],
  );

  useImperativeHandle(ref, () => ({
    getEchartsInstance: () => barRef.current.getEchartsInstance(),
  }));
  return <ReactEcharts  ref={barRef} option={option} opts={{ renderer: 'svg' }} />;
};

export default forwardRef(BarChart);
