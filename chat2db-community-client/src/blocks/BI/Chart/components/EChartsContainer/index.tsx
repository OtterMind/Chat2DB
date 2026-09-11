import React, { useEffect, useRef, memo, useCallback } from 'react';
import * as echarts from 'echarts';
import 'echarts-wordcloud';
import { DivProps } from '@/typings/common';
import { useStyles } from '../../style';
import { useSize, useUpdateEffect } from 'ahooks';
import { throttle, debounce, omit } from 'lodash';
import { useChartTheme } from '../../hooks/useTheme';
import { ChartSchema } from '@/blocks/BI/Chart/typings';
import { isEqualMemo } from '@/utils';
import useLabelRotate from './hooks/useLabelRotate';

export interface ChartProps extends DivProps {
  className?: string;
  emptyComment?: React.ReactNode;
  option?: any;
  chartSchema?: ChartSchema;
  // Whether to refresh automatically when resize
  resizeRefresh?: boolean;
  // option is changed, whether to perform resize?
  optionsChangeRefresh?: boolean;
  resizeRefreshInterval?: number;
  resizeCallback?: (width: number, height: number) => void;
}

// import * as echarts from 'echarts/core';
// import { BarChart, PieChart } from 'echarts/charts';
// import { TitleComponent, TooltipComponent, GridComponent } from 'echarts/components';
// import { SVGRenderer } from 'echarts/renderers';

// // Chart type
// const chartTypesList = [BarChart, PieChart];
// // Register necessary components
// echarts.use([TitleComponent, TooltipComponent, GridComponent, SVGRenderer, ...chartTypesList]);

const EChartsContainer = (props: ChartProps) => {
  const {
    className,
    option,
    chartSchema,
    resizeRefresh = true,
    resizeRefreshInterval = 400,
    optionsChangeRefresh = false,
    resizeCallback,
    ...rest
  } = props;
  const chartContainerRef = useRef<HTMLDivElement>(null);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);
  const { styles, cx, theme: antdTheme } = useStyles();
  const { theme } = useChartTheme({ themeColorCode: chartSchema?.themeColorCode });
  const labelRotate = useLabelRotate(antdTheme);
  // monitors container size changes
  const wrapperSize = useSize(wrapperRef);

  useUpdateEffect(() => {
    if (!chartRef.current || !option?.xAxis?.data) return;
    labelRotate(chartRef.current, option);
  }, [option?.xAxis?.data, option?.xAxis?.axisLine]);

  useEffect(() => {
    if (!option) return;

    // Initialize the echarts instance based on the prepared dom
    if (!chartRef.current) {
      chartRef.current = echarts.init(chartContainerRef.current, theme);
    }

    option.textStyle = {
      fontFamily: antdTheme.fontFamily, // global font
      fontSize: 12, // global font size
    };

    // Set the configuration items and data of the chart
    chartRef.current.setOption(option, true);

    if (optionsChangeRefresh) {
      chartRef.current.resize();
    }
  }, [option, theme]);

  // specializes in theme changes
  useUpdateEffect(() => {
    if (chartRef.current) {
      chartRef.current.dispose();
      chartRef.current = echarts.init(chartContainerRef.current, theme);
      chartRef.current.setOption(option, true);
    }
  }, [theme]);

  useEffect(() => {
    // Destroy the instance when the component is unloaded
    return () => chartRef.current?.dispose();
  }, []);

  const debouncedResize = useCallback(
    resizeRefresh
      ? throttle((width, height, _option) => {
          chartRef.current?.resize();
          resizeCallback?.(width, height);
          labelRotate(chartRef.current, _option);
        }, resizeRefreshInterval)
      : debounce((width, height, _option) => {
          // chartRef.current?.resize();
          resizeCallback?.(width, height);
          labelRotate(chartRef.current, _option);
        }, resizeRefreshInterval),
    [resizeRefresh],
  );

  // Redraw chart when container size changes
  useUpdateEffect(() => {
    const { width, height } = wrapperSize as any;
    // containers have no width or height and do not need to be resized.
    if (width && height) {
      debouncedResize(width, height, option);
    }
  }, [wrapperSize]);

  return (
    <div ref={wrapperRef} className={cx(className, styles.wrapper)} {...rest}>
      <div ref={chartContainerRef} className={styles.chartContainer} />
    </div>
  );
};

export default memo(EChartsContainer, (prevProps, nextProps) => {
  return isEqualMemo(
    [prevProps.option, nextProps.option],
    [omit(prevProps.chartSchema, ['title', 'autoRefresh']), omit(nextProps.chartSchema, ['title', 'autoRefresh'])],
  );
});
