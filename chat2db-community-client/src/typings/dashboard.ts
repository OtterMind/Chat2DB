import { ChartType } from '@/constants/dashboard';
import { IDatabaseBaseInfo, IManageResultData } from '.';
import { ChartSchema } from '@/blocks/BI/Chart/typings';
import { AUTO_REFRESH } from '@/blocks/BI/Chart/constants';

export interface ChartTypeItem {
  icon: string;
  name: string;
  type: ChartType;
  form: {
    label: string;
    name: string;
  }[];
}

export interface IDashboardItem {
  id?: number;
  name?: string;
  description?: string;
  chartIds?: number[];
  gmtModified?: number;
  gmtCreate?: number;
  // Chart layout
  schema?: string;
  refreshType?: AUTO_REFRESH;
  refreshCycle?: any;
}

export interface ChartTheme {
  themeColorCode?: string;
}

export interface IChartItem {
  id?: number;
  /** Chart parameters */
  chartSchema?: ChartSchema;
  /** sql returns data */
  metaData?: IManageResultData;
  /** Database information */
  databaseInfo?: IDatabaseBaseInfo;
  // Don’t worry about the type of refresh data now, it’s not used.
  refreshType?: any;
  // The period for refreshing data is a refresh expression
  refreshCycle?: string;
}

// export interface IOldChartItem {
//   id?: number;
//   /** Chart name */
//   name?: string;
//   /** Chart description */
//   description?: string;
//   /** Chart parameters */
//   schema?: string;
//   /** Chart type */
//   chartType?: ChartType;
//   /** Data source connection ID */
//   dataSourceId?: number;
//     /** Data source name */
//   dataSourceName?: string;
//   /** Database type */
//   databaseType?: DatabaseTypeCode;
//   /** db name */
//   databaseName?: string;
//   /** schema name */
//   schemaName?: string;
//   /** ddl content */
//   ddl?: string;
//   /** Whether to link */
//   connectable?: boolean;
// }

export interface DragLayoutItem {
  i: string;
  h: number;
  w: number;
  x: number;
  y: number;
  isBounded?: boolean;
  isDraggable?: boolean;
  isResizable?: boolean;
  maxH?: number;
  maxW?: number;
  minH?: number;
  minW?: number;
  moved?: false;
  resizeHandles?: any;
  static?: false;
}

// Normalized data format
export type INormalizedData = {
  [key: string]: string | number;
}[];
