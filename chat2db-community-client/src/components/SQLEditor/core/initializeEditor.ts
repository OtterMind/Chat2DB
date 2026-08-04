import * as monaco from 'monaco-editor';
import { registerThemes } from './registerThemes';
import { registerSQLSnippets } from './registerSnippets';
import { registerSQLFormat } from './registerSQLFormat';

let isInitialized = false;

const initializeMonacoEditor = () => {
  if (isInitialized) {
    return;
  }

  // Register themes.
  registerThemes();

  // Register languages.
  monaco.languages.register({ id: 'sql' });
  monaco.languages.setLanguageConfiguration('sql', {
    comments: {
      lineComment: '--',
      blockComment: ['/*', '*/'],
    },
  });
  // TODO: Define more detailed rules.
  // monaco.languages.setMonarchTokensProvider('sql', {});

  // Initialize snippets.
  registerSQLSnippets();

  // Register formatting.
  registerSQLFormat();

  isInitialized = true;
};

export { initializeMonacoEditor };
