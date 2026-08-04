import { OrderByRule } from '../constants';

export type ChartSortValue = number | string;

export const compareChartValues = (left: ChartSortValue, right: ChartSortValue): number => {
  return left < right ? -1 : left > right ? 1 : 0;
};

export const sortChartDataIndices = (
  dataToSort: readonly ChartSortValue[],
  orderByRule: OrderByRule,
): number[] => {
  return dataToSort
    .map((_, index) => index)
    .sort((a, b) => {
      const compareResult = compareChartValues(dataToSort[a], dataToSort[b]);
      return orderByRule === OrderByRule.ASC ? compareResult : -compareResult;
    });
};
