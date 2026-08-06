import type { ITableHeaderItem } from '@/typings/database';

export type HeaderMetadataKey = 'fieldName' | 'fieldType' | 'fieldComment';

export interface HeaderMetadataRow {
  key: HeaderMetadataKey;
  value: string;
}

export interface HeaderMetadataVisibility {
  showFieldType?: boolean;
  showFieldComment?: boolean;
}

function formatFieldType(header: ITableHeaderItem): string {
  const fieldType = header.columnType?.trim() || header.dataType?.trim();
  if (!fieldType) {
    return '--';
  }
  return header.columnSize == null ? fieldType : `${fieldType}(${header.columnSize})`;
}

export function getHeaderMetadataRows(
  header: ITableHeaderItem,
  visibility: HeaderMetadataVisibility = {},
): HeaderMetadataRow[] {
  const rows: HeaderMetadataRow[] = [{ key: 'fieldName', value: header.name?.trim() || '--' }];
  if (visibility.showFieldType ?? true) {
    rows.push({ key: 'fieldType', value: formatFieldType(header) });
  }
  if (visibility.showFieldComment ?? true) {
    rows.push({ key: 'fieldComment', value: header.comment?.trim() || '--' });
  }
  return rows;
}
