import { AUTO_REFRESH, ChartType, LineType, OrderByRule, OrderByType } from '@/blocks/BI/Chart/constants';
// import {AutoRefresh}  from '@/blocks/BI/Chart/typings';

// normalized data format
export type INormalizedData = {
  [key: string]: string | number | null;
}[];

// automatic refresh rules
export interface AutoRefresh {
  everydayRefresh: string;
  minutesRefresh: number;
  refreshRule: AUTO_REFRESH;
}

export interface IComboYAxisDataItem { 
  field?: string;
  chartType: ChartType;
  axisPosition: 'left' | 'right';
}

export interface ChartTheme {
  themeColorCode: string;
}

export type ChartOptionCheckbox = 'showLegend' | 'showLabel' | 'showAxisLine' | 'showSplitLine' | 'showSymbol'; 

export interface ChartSchema {

  /**
   * angle field
   */
  angleField?: null | string;
  /**
   * grouping field
   */
  binField?: null | string;
  /**
   * Group number
   */
  binNumber?: null | string;
  /**
   * grouping type
   */
  channel?: null | string;
  /**
   * report type
   */
  chartType?: null | ChartType;
  /**
   * color field
   */
  colorField?: null | string;
  /**
   * size field
   */
  textField?: null | string;
  /**
   * value field
   */
  valueField?: null | string;
  /**
   * x-axis field
   */
  xField?: null | string;
  /**
   * y-axis field
   */
  yField?: null | string;
  /**
   * y-axis data of combination chart
   */
  comboYAxisData?: IComboYAxisDataItem[];
  /**
   * line chart type
   */
  lineType: LineType;
  /**
   * icon title
   */
  title?: string;
  /**
   * icon
   */
  summary?: string;
  /**
   * themeColorCode
   */
  themeColorCode?: string;
  /**
   * automatic refresh
   */
  autoRefresh?: AutoRefresh;
  /**
   * Legend Data Label Coordinate Axis Grid Lines
   */
  chartOptionCheckbox: ChartOptionCheckbox[];
  /**
   * Is it full of word clouds?
   */
  bespreadWordCloud?: boolean;
  /**
   * Sort by
   */
  orderByType: OrderByType;
  /**
   * sorting rules
   */
  orderByRule: OrderByRule;
  /**
   * data
   */
  data: INormalizedData;
}
