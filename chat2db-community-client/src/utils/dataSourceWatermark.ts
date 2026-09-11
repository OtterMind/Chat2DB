import type { IBoundInfo } from '@/typings';
import type { EditorDataSourceState } from './editorDataSourceLifecycle';

export interface DataSourceWatermarkContent {
  title: string;
  subtitle?: string;
  connectionUnavailable?: boolean;
}

export interface DataSourceWatermarkLayout {
  itemCount: 1 | 2 | 4 | 9;
  columns: 1 | 2 | 3;
  rows: 1 | 2 | 3;
}

export const LARGE_WATERMARK_MIN_WIDTH = 720;
export const LARGE_WATERMARK_MIN_HEIGHT = 360;
export const DENSE_WATERMARK_MIN_HEIGHT = 480;

export function getDataSourceWatermarkLayout(width?: number, height?: number): DataSourceWatermarkLayout {
  const measuredWidth = width ?? 0;
  const measuredHeight = height ?? 0;
  if (!measuredWidth || !measuredHeight) {
    return { itemCount: 1, columns: 1, rows: 1 };
  }
  if (measuredWidth >= LARGE_WATERMARK_MIN_WIDTH && measuredHeight >= DENSE_WATERMARK_MIN_HEIGHT) {
    return { itemCount: 9, columns: 3, rows: 3 };
  }
  if (measuredWidth >= LARGE_WATERMARK_MIN_WIDTH && measuredHeight >= LARGE_WATERMARK_MIN_HEIGHT) {
    return { itemCount: 4, columns: 2, rows: 2 };
  }
  return measuredWidth >= measuredHeight
    ? { itemCount: 2, columns: 2, rows: 1 }
    : { itemCount: 1, columns: 1, rows: 1 };
}

function normalizeWatermarkPart(value?: string) {
  const normalizedValue = value?.trim();
  return normalizedValue || undefined;
}

export function getDataSourceWatermarkContent(
  boundInfo: Pick<
    IBoundInfo,
    | 'dataSourceName'
    | 'databaseName'
    | 'schemaName'
    | 'environment'
    | 'watermarkEnabled'
    | 'watermarkContent'
  >,
  dataSourceState?: EditorDataSourceState,
): DataSourceWatermarkContent | undefined {
  if (boundInfo.watermarkEnabled !== true) {
    return undefined;
  }

  const customContent = normalizeWatermarkPart(boundInfo.watermarkContent || undefined);
  if (customContent) {
    return {
      title: customContent,
      ...(dataSourceState === 'unavailable' ? { connectionUnavailable: true } : {}),
    };
  }

  const environmentShortName = normalizeWatermarkPart(boundInfo.environment?.shortName);
  const environmentName = normalizeWatermarkPart(boundInfo.environment?.name);
  const dataSourceName = normalizeWatermarkPart(boundInfo.dataSourceName);
  const databaseName = normalizeWatermarkPart(boundInfo.databaseName);
  const schemaName = normalizeWatermarkPart(boundInfo.schemaName);
  const environmentTitle = environmentShortName || environmentName;
  const title = environmentTitle || dataSourceName;
  if (!title) {
    return undefined;
  }

  const subtitleParts = environmentTitle ? [dataSourceName, databaseName, schemaName] : [databaseName, schemaName];
  const subtitle = subtitleParts.filter((value): value is string => !!value).join(' / ');
  return {
    title,
    subtitle: subtitle || undefined,
    ...(dataSourceState === 'unavailable' ? { connectionUnavailable: true } : {}),
  };
}
