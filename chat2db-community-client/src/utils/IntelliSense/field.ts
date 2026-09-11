import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import sqlService from '@/service/sql';
import i18n from '@/i18n';
import { SORT_TEXT } from '@/components/SQLEditor/type';

let fieldList: Record<string, Array<{ name: string; tableName: string }>> = {};

/** Table under the current library */
let intelliSenseField = monaco.languages.registerCompletionItemProvider('sql', {
  provideCompletionItems: () => {
    return {
      suggestions: [],
    };
  },
});

export const resetSenseField = () => {
  intelliSenseField.dispose();
};

const addIntelliSenseField = async (props: {
  tableName: string;
  dataSourceId: number;
  databaseName: string;
  schemaName?: string;
}) => {
  const { tableName, dataSourceId, databaseName, schemaName } = props;

  if (!fieldList[tableName]) {
    const data = await sqlService.getAllFieldByTable({
      dataSourceId,
      databaseName,
      schemaName,
      tableName,
    });
    fieldList[tableName] = data;
  }
};

const registerIntelliSenseField = (tableList: string[], dataSourceId, databaseName, schemaName) => {
  resetSenseField();
  fieldList = {};
  intelliSenseField = monaco.languages.registerCompletionItemProvider('sql', {
    // triggerCharacters: [' ', ',', '.', '('],
    provideCompletionItems: async (model, position) => {
      // Get the current line of text
      const textUntilPosition = model.getValueInRange({
        startLineNumber: position.lineNumber,
        startColumn: 1,
        endLineNumber: position.lineNumber,
        endColumn: position.column,
      });

      const match = textUntilPosition.match(/(\b\w+\b)[^\w]*$/);

      let word;
      if (match) {
        word = match[1];
      }

      if (!word) {
        return; // If no match is found, return directly
      }
      if (word && tableList.includes(word) && !fieldList[word]) {
        const data = await sqlService.getAllFieldByTable({
          dataSourceId,
          databaseName,
          schemaName,
          tableName: word,
        });
        fieldList[word] = data;
      }

      const suggestions: monaco.languages.CompletionItem[] = Object.keys(fieldList).reduce((acc, cur) => {
        const arr = fieldList[cur].map((fieldObj) => ({
          label: {
            label: fieldObj.name,
            detail: `(${fieldObj.tableName})`,
            description: i18n('sqlEditor.text.fieldName'),
          },
          kind: monaco.languages.CompletionItemKind.Field,
          insertText: fieldObj.name,
          // sortText: isFieldContext ? '01' : '08',
          sortText: SORT_TEXT.COLUMN,
        }));

        return [...acc, ...arr];
      }, []);

      return {
        suggestions,
      };
    },
  });
};

export { intelliSenseField, registerIntelliSenseField, addIntelliSenseField };
