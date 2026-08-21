import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import { DatabaseTypeCode } from '@/constants';
import intelliSense from '@/constants/IntelliSense';
import i18n from '@/i18n';
import { SORT_TEXT } from '@/components/SQLEditor/type';

/** Keywords */
const getSQLKeywords = (keywords: string[]) => {
  return keywords.map((key: any) => ({
    label: {
      label: key,
      detail: '',
      description: i18n('sqlEditor.text.keyword'),
    },
    kind: monaco.languages.CompletionItemKind.Text,
    insertText: key,
    sortText: SORT_TEXT.KEYWORD,
  }));
};

/** function */
const getSQLFunctions = (functions: string[]) => {
  return functions.map((key: any) => ({
    label: {
      label: key,
      detail: '',
      description: i18n('sqlEditor.text.function'),
      sortText: SORT_TEXT.FUNCTION,
    },
    // kind: monaco.languages.CompletionItemKind.Method,
    kind: monaco.languages.CompletionItemKind.Function,
    insertText: key,
  }));
};

export const resetSenseKeyword = () => {
  intelliSenseKeyword.dispose();
};

let intelliSenseKeyword = monaco.languages.registerCompletionItemProvider('sql', {
  provideCompletionItems: () => {
    return { suggestions: [] };
  },
});

const registerIntelliSenseKeyword = (databaseCode?: DatabaseTypeCode) => {
  resetSenseKeyword();
  intelliSenseKeyword = monaco.languages.registerCompletionItemProvider('sql', {
    // triggerCharacters: [' ', '('], // Characters that trigger prompts
    provideCompletionItems: (_model, _position) => {
      // Get all editor instances
      const commonIntelliSense = Object.values(intelliSense).find((v) => v.type === databaseCode);

      if (commonIntelliSense) {
        return {
          suggestions: [
            ...getSQLKeywords(commonIntelliSense?.keywords),
            ...getSQLFunctions(commonIntelliSense?.functions),
          ],
        };
      } else {
        const intelliSenseMySQL = Object.values(intelliSense).find((v) => v.type === DatabaseTypeCode.MYSQL);
        if (!intelliSenseMySQL) return { suggestions: [] };
        return {
          suggestions: [
            ...getSQLKeywords(intelliSenseMySQL?.keywords),
            ...getSQLFunctions(intelliSenseMySQL?.functions),
          ],
        };
      }
    },
  });
};

export { intelliSenseKeyword, registerIntelliSenseKeyword };
