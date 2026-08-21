import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import i18n from '@/i18n';
import { SORT_TEXT } from '@/components/SQLEditor/type';

export const resetSenseDatabase = () => {
  intelliSenseDatabase.dispose();
};

let intelliSenseDatabase = monaco.languages.registerCompletionItemProvider('sql', {
  provideCompletionItems: () => {
    return { suggestions: [] };
  },
});

const registerIntelliSenseDatabase = (databaseName: Array<{ name: string; dataSourceName: string }>) => {
  resetSenseDatabase();
  intelliSenseDatabase = monaco.languages.registerCompletionItemProvider('sql', {
    // triggerCharacters: [' ', '.'],
    provideCompletionItems: (_model, _position) => {
      return {
        suggestions: (databaseName || []).map(({ name, dataSourceName }) => ({
          label: {
            label: name,
            detail: dataSourceName ? `(${dataSourceName})` : null,
            description: i18n('sqlEditor.text.databaseName'),
          },
          insertText: name,
          // sortText: isTableContext ? '01' : '08',
          sortText: SORT_TEXT.DATABASE,
          kind: monaco.languages.CompletionItemKind.Property,
        })),
      };
    },
  });
};

export { intelliSenseDatabase, registerIntelliSenseDatabase };
