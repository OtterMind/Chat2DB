import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import { SORT_TEXT } from '@/components/SQLEditor/type';

export const resetSenseSchema = () => {
  intelliSenseSchema.dispose();
};

let intelliSenseSchema = monaco.languages.registerCompletionItemProvider('sql', {
  provideCompletionItems: () => {
    return { suggestions: [] };
  },
});

const registerIntelliSenseSchema = (schemaList: Array<{ name: string }>) => {
  resetSenseSchema();
  intelliSenseSchema = monaco.languages.registerCompletionItemProvider('sql', {
    // triggerCharacters: [' ', '.'],
    provideCompletionItems: (_model, _position) => {
      return {
        suggestions: (schemaList || []).map(({ name }) => ({
          label: {
            label: name,
            description: 'Schema',
          },
          insertText: name,
          sortText: SORT_TEXT.SCHEMA,
          kind: monaco.languages.CompletionItemKind.Property,
        })),
      };
    },
  });
};

export { intelliSenseSchema, registerIntelliSenseSchema };
