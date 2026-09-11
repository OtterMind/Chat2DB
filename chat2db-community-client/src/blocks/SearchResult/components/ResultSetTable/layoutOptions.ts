export const RESULT_TABLE_CONTENT_LAYOUT_OPTIONS = {
  autoWrapText: true,
  heightMode: 'standard',
  // Collapsed long cells use a bounded custom preview. Keep the full value available
  // for a row after the user explicitly resizes it.
  maxCharactersNumber: Number.MAX_SAFE_INTEGER,
} as const;
