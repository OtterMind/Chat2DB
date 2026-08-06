import type { ITableHeaderItem } from '@/typings/database';
import { getHeaderMetadataRows, type HeaderMetadataVisibility } from '../headerMetadata';

export const getResultColumnTitle = (data: ITableHeaderItem, visibility?: HeaderMetadataVisibility): string =>
  getHeaderMetadataRows(data, visibility)
    .map((row) => row.value)
    .join('\n');
