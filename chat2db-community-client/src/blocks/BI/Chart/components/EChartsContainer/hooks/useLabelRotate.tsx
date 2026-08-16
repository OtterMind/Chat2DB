import { useCallback } from 'react';
import _ from 'lodash';

type EChartsLabel = {
  formattedLabel?: string;
  rawLabel?: string;
};

export const getChartLabelText = (label: unknown): string => {
  if (label !== null && typeof label === 'object') {
    const { formattedLabel, rawLabel } = label as EChartsLabel;
    return formattedLabel || rawLabel || '';
  }
  return String(label);
};

export const calculateLabelRotation = (
  containerWidth: number,
  labelCount: number,
  longestLabelWidth: number,
): { rotate: number; interval: number } => {
  const availableWidth = containerWidth / labelCount;
  let rotate = 0;
  let interval = 0;

  if (availableWidth < longestLabelWidth) {
    rotate = 45;
    if (availableWidth < longestLabelWidth * Math.cos(Math.PI / 4)) {
      rotate = 90;
      if (containerWidth < 14 * labelCount) {
        interval = Math.ceil(labelCount / (containerWidth / 14));
      }
    }
  }

  return { rotate, interval };
};

const useLabelRotate = (antdTheme: any) => {
  const labelRotate = useCallback((chart, option) => {
    if (option?.xAxis?.axisLine?.show === false) {
      return;
    }

    try {
      if (!chart || !option?.xAxis?.data?.length) return;
      // const axisModel = chart.getModel().getComponent('xAxis', 0);
      // const labels = axisModel.axis.getViewLabels();
      const labels = option.xAxis.data;

      // Get the width of the container
      // const containerWidth = chart.getWidth();
      // Get the width of the container and subtract the left and right margins of the coordinate axis
      const containerAggregateWidth = chart.getWidth();
      const gridModel = chart.getModel().getComponent('grid', 0);
      const gridLeft = gridModel.get('left');
      const gridRight = gridModel.get('right');

      const leftPadding =
        typeof gridLeft === 'string' && gridLeft.endsWith('%')
          ? (parseFloat(gridLeft) / 100) * containerAggregateWidth
          : parseFloat(gridLeft || 0);

      const rightPadding =
        typeof gridRight === 'string' && gridRight.endsWith('%')
          ? (parseFloat(gridRight) / 100) * containerAggregateWidth
          : parseFloat(gridRight || 0);

      // Gets the label width of the y-axis
      const yAxisModel = chart.getModel().getComponent('yAxis', 0);
      const yAxisLabels = yAxisModel.axis.getViewLabels();

      // Gets the Y-axis label font style (preferably obtained from ECharts configuration)
      const font = `12px ${antdTheme.fontFamily}`;

      const yAxisLabelWidth = yAxisLabels.reduce((max, label) => {
        const labelText = getChartLabelText(label);
        const canvas = document.createElement('canvas');
        const context = canvas.getContext('2d');
        context!.font = font; // Assume a 12px font.
        const textWidth = context!.measureText(labelText).width;
        return Math.max(max, textWidth);
      }, 0);

      const containerWidth = containerAggregateWidth - leftPadding - rightPadding - yAxisLabelWidth;
      if (!option.xAxis?.data?.length) {
        return;
      }

      // Get the number of labels.
      const labelCount = option.xAxis?.data.length;

      // Measure the longest label in pixels.
      const longestLabelWidth = labels.reduce((max, label) => {
        const labelText = getChartLabelText(label);
        const canvas = document.createElement('canvas');
        const context = canvas.getContext('2d');
        context!.font = font; // Assume a 12px font.
        const textWidth = context!.measureText(labelText).width;
        return Math.max(max, textWidth);
      }, 0);

      const { rotate, interval } = calculateLabelRotation(containerWidth, labelCount, longestLabelWidth);
      _.set(option, 'xAxis.axisLabel.interval', interval);
      _.set(option, 'xAxis.axisLabel.rotate', rotate);
      chart.setOption(option, true);
    } catch (e) {
      console.error(e);
    }
  }, []);

  return labelRotate;
};

export default useLabelRotate;
