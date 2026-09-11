import { DatabaseTypeCode } from '@/constants';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import { addIntelliSenseField } from './field';
import i18n from '@/i18n';
import { compatibleDataBaseName } from '../database';
import { SORT_TEXT } from '@/components/SQLEditor/type';

export const resetSenseTable = () => {
  intelliSenseTable.dispose();
};

/** Table under the current library */
let intelliSenseTable = monaco.languages.registerCompletionItemProvider('sql', {
  provideCompletionItems: () => {
    return { suggestions: [] };
  },
});

const handleInsertText = (keyword: string, tableName: string, databaseCode: DatabaseTypeCode) => {
  if (/^[A-Za-z]/.test(keyword)) {
    return tableName;
  }

  if (/^["`[]/.test(keyword)) {
    return tableName;
  }

  return compatibleDataBaseName(tableName, databaseCode);
};

const registerIntelliSenseTable = (
  tableList: Array<{ name: string; comment: string }>,
  databaseCode: DatabaseTypeCode,
  dataSourceId?: number,
  databaseName?: string | null,
  schemaName?: string | null,
) => {
  monaco.editor.registerCommand('addFieldList', (_: any, ...args: any[]) => {
    addIntelliSenseField(args[0]);
    return;
  });

  resetSenseTable();
  intelliSenseTable = monaco.languages.registerCompletionItemProvider('sql', {
    // triggerCharacters: ['.'],
    provideCompletionItems: (model, position) => {
      const lineContentUntilPosition = model.getValueInRange({
        startLineNumber: position.lineNumber,
        startColumn: 1,
        endLineNumber: position.lineNumber,
        endColumn: position.column,
      });

      // Get the character that triggered the prompt
      const match = lineContentUntilPosition.match(/\S+$/);
      const word = match ? match[0] : '';

      return {
        suggestions: (tableList || []).map((tableName) => ({
          label: {
            label: tableName.name,
            detail: databaseName ? `(${databaseName})` : null,
            description: i18n('sqlEditor.text.tableName'),
          },
          kind: monaco.languages.CompletionItemKind.Folder,
          insertText: handleInsertText(word, tableName.name, databaseCode),
          // range: monaco.Range.fromPositions(position),
          // documentation: tableName.comment,
          // sortText: isTableContext ? '01' : '08',
          sortText: SORT_TEXT.TABLE,
          command: {
            id: 'addFieldList',
            title: 'addFieldList',
            arguments: [
              {
                tableName: tableName.name,
                dataSourceId,
                databaseName,
                schemaName,
              },
            ],
          },
        })),
      };
    },
  });
};

export { intelliSenseTable, registerIntelliSenseTable };
