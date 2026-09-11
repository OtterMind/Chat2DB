import type { ISQLEditorWithOperationRef } from '@/components/SQLEditor/editor/SQLEditorWithOperation';

export interface CurrentSqlEditorHandleRef {
  current: ISQLEditorWithOperationRef | null;
}

export function createLiveSqlEditorHandle(
  editorRef: CurrentSqlEditorHandleRef,
): ISQLEditorWithOperationRef {
  return {
    getId: () => editorRef.current?.getId() ?? '',
    getInstance: () => editorRef.current?.getInstance() ?? null,
    getValue: () => editorRef.current?.getValue() ?? '',
    setValue: (value, type) => editorRef.current?.setValue(value, type),
    getContentDiffBaseline: () => editorRef.current?.getContentDiffBaseline() ?? '',
    resetContentDiffBaseline: (value) => editorRef.current?.resetContentDiffBaseline(value),
    getSelectedContent: () => editorRef.current?.getSelectedContent() ?? '',
    getCursorSQL: () => editorRef.current?.getCursorSQL() ?? '',
    getCursorCurLineNearestSQL: () => editorRef.current?.getCursorCurLineNearestSQL() ?? '',
    handleSQLParser: (sql, dbInfo) => editorRef.current?.handleSQLParser(sql, dbInfo),
    handleQuickSQLParser: (sql, dbInfo) => editorRef.current?.handleQuickSQLParser(sql, dbInfo),
    getTableIdentifierAtPosition: (position) => editorRef.current?.getTableIdentifierAtPosition(position) ?? null,
    executeSQL: () => editorRef.current?.executeSQL(),
    hasUnsavedChangesBeforeClose: () => editorRef.current?.hasUnsavedChangesBeforeClose?.() ?? true,
    saveBeforeClose: () => editorRef.current?.saveBeforeClose?.() ?? Promise.resolve(false),
    waitForPendingSave: () => editorRef.current?.waitForPendingSave?.() ?? Promise.resolve(),
    get persistBeforeApplicationExit() {
      if (!editorRef.current?.persistBeforeApplicationExit) {
        return undefined;
      }
      return () => editorRef.current?.persistBeforeApplicationExit?.() ?? Promise.resolve(false);
    },
  };
}
