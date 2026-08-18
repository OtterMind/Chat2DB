import type { Theme } from 'antd-style';
import type { ITableHeaderItem } from '@/typings/database';
import {
  getHeaderMetadataRows,
  type HeaderMetadataKey,
  type HeaderMetadataVisibility,
} from './headerMetadata';

const HEADER_HORIZONTAL_PADDING = 8;
const HEADER_ICON_SPACE = 58;

const estimateHeaderTextWidth = (value: string, fontSize: number) =>
  [...value].reduce(
    (width, character) => width + (character.charCodeAt(0) <= 0xff ? fontSize * 0.62 : fontSize),
    0,
  );

export const createResultHeaderCustomRender = (params: {
  data: ITableHeaderItem;
  theme: Omit<Theme, 'prefixCls'>;
  fontSize: number;
  visibility?: HeaderMetadataVisibility;
  availableWidth?: number;
}) => {
  const { data, theme, fontSize, visibility, availableWidth } = params;
  const rows = getHeaderMetadataRows(data, visibility);
  const lineHeight = fontSize + 9;
  const getRowFontSize = (key: HeaderMetadataKey) => (key === 'fieldName' ? fontSize + 1 : fontSize);
  const expectedHeight = rows.length * lineHeight + 16;
  const expectedWidth = Math.min(
    360,
    Math.max(
      120,
      ...rows.map((row) => estimateHeaderTextWidth(row.value, getRowFontSize(row.key)) + HEADER_ICON_SPACE),
    ),
  );
  const colors: Record<HeaderMetadataKey, string> = {
    fieldName: theme.colorText,
    fieldType: theme.colorPrimary,
    fieldComment: theme.colorTextSecondary,
  };
  const renderWidth =
    typeof availableWidth === 'number' && Number.isFinite(availableWidth) && availableWidth > 0
      ? availableWidth
      : expectedWidth;

  return {
    elements: rows.map((row, index) => ({
      type: 'text' as const,
      fill: colors[row.key],
      fontSize: getRowFontSize(row.key),
      fontFamily: theme.fontFamily,
      fontWeight: row.key === 'fieldName' ? 600 : 400,
      text: row.value,
      x: HEADER_HORIZONTAL_PADDING,
      y: 8 + lineHeight * index + lineHeight / 2,
      textBaseline: 'middle' as const,
      ellipsis: true,
      maxLineWidth: Math.max(24, renderWidth - HEADER_ICON_SPACE),
    })),
    expectedHeight,
    expectedWidth,
    renderDefault: true,
  };
};
