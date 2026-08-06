import type { ITableHeaderItem } from '@/typings/database';

export type HeaderMetadataKey = 'fieldName' | 'fieldType' | 'fieldComment';

export interface HeaderMetadataRow {
  key: HeaderMetadataKey;
  value: string;
}

function formatFieldType(header: ITableHeaderItem): string {
  const fieldType = header.columnType?.trim() || header.dataType?.trim();
  if (!fieldType) {
    return '-';
  }
  return header.columnSize == null ? fieldType : `${fieldType}(${header.columnSize})`;
}

export function getHeaderMetadataRows(header: ITableHeaderItem): HeaderMetadataRow[] {
  return [
    { key: 'fieldName', value: header.name || '-' },
    { key: 'fieldType', value: formatFieldType(header) },
    { key: 'fieldComment', value: header.comment?.trim() || '-' },
  ];
}
