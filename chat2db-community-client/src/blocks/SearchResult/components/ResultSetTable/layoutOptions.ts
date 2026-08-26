export const RESULT_TABLE_MAX_AUTO_ROW_HEIGHT = 240;

export const RESULT_TABLE_CONTENT_LAYOUT_OPTIONS = {
  autoWrapText: true,
  heightMode: 'autoHeight',
  // VTable defaults to truncating every cell after 200 characters. Large values
  // are handled by the cell metadata renderer; ordinary result text remains complete.
  maxCharactersNumber: Number.MAX_SAFE_INTEGER,
} as const;
