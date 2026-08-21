import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';

const sourceRoot = path.resolve('src');
const retiredFiles = [
  'src/service/aiDataCollection.ts',
  'src/service/chat.ts',
  'src/service/magicStick.ts',
  'src/components/ChangeAiTableInfo/index.tsx',
  'src/components/SelectDataCollection/index.tsx',
  'src/blocks/NewTree/functions/createAiDataCollection.tsx',
  'src/blocks/NewTree/functions/ai.ts',
  'src/store/ai/slices/dataCollection/action.ts',
  'src/store/workspace/slices/ai/action.ts',
];

for (const retiredFile of retiredFiles) {
  assert.equal(existsSync(retiredFile), false, `${retiredFile} must stay retired`);
}

const retiredMarkers = [
  '/api/ai/data/collection',
  '/api/v1/ai/embedding',
  '/api/ai/chat',
  '/api/v1/ai/chat',
  '/api/v2/ai/chat',
  '/api/v2/ai/stream/chat',
  'dataSourceCollectionId',
  'AI_DATA_COLLECTION',
  'defaultDataCollectionList',
  'needAiDataCollections',
];

const sourceFiles: string[] = [];
const collectSourceFiles = (directory: string) => {
  for (const entry of readdirSync(directory)) {
    const absolutePath = path.join(directory, entry);
    const relativePath = path.relative(sourceRoot, absolutePath);
    if (
      relativePath.startsWith(`i18n${path.sep}`) ||
      relativePath === '.umi' ||
      relativePath.startsWith(`.umi${path.sep}`) ||
      relativePath === '.umi-production' ||
      relativePath.startsWith(`.umi-production${path.sep}`) ||
      absolutePath.endsWith('.test.ts')
    ) {
      continue;
    }
    if (statSync(absolutePath).isDirectory()) {
      collectSourceFiles(absolutePath);
    } else if (/\.(ts|tsx)$/.test(entry)) {
      sourceFiles.push(absolutePath);
    }
  }
};
collectSourceFiles(sourceRoot);

for (const sourceFile of sourceFiles) {
  const source = readFileSync(sourceFile, 'utf8');
  for (const marker of retiredMarkers) {
    assert.equal(source.includes(marker), false, `${marker} must stay retired from ${sourceFile}`);
  }
}

const aiSource = readFileSync('src/blocks/AI/index.tsx', 'utf8');
const inputSource = readFileSync('src/blocks/AI/components/AIChatInput/index.tsx', 'utf8');
const mentionSource = readFileSync('src/blocks/AI/components/AIAtMetion/index.tsx', 'utf8');
const treeStoreSource = readFileSync('src/store/tree/index.tsx', 'utf8');
const i18nSource = readdirSync('src/i18n')
  .filter((locale) => statSync(path.join('src/i18n', locale)).isDirectory())
  .flatMap((locale) =>
    readdirSync(path.join('src/i18n', locale))
      .filter((file) => file.endsWith('.ts'))
      .map((file) => readFileSync(path.join('src/i18n', locale, file), 'utf8')),
  )
  .join('\n');
const requestSource = readFileSync(
  '../chat2db-community-server/chat2db-community-web/src/main/java/ai/chat2db/community/web/api/model/request/ai/ChatRequest.java',
  'utf8',
);
assert.match(aiSource, /baseURL: '\/api\/v3\/ai\/chat\/stream'/);
assert.match(aiSource, /selectedKnowledge: toKnowledgeSelectionReferences\(params\.selectedKnowledge\)/);
assert.match(inputSource, /clientExtension\.knowledgeMentions/);
assert.match(inputSource, /normalizeMentionInput/);
const knowledgeRequestSource = inputSource.slice(
  inputSource.indexOf('const fetchKnowledgeList'),
  inputSource.indexOf('const handleSend'),
);
assert.doesNotMatch(knowledgeRequestSource, /debounce/);
assert.match(inputSource, /pageSize: KNOWLEDGE_PAGE_SIZE/);
assert.match(inputSource, /loadMoreKnowledge/);
assert.match(mentionSource, /onScrollCapture/);
assert.match(requestSource, /List<SelectedKnowledgeRequest> selectedKnowledge/);
assert.doesNotMatch(requestSource, /private String key/);
assert.doesNotMatch(requestSource, /private String value/);
assert.match(treeStoreSource, /import \{ clientRuntime \} from '@client-runtime';/);
assert.doesNotMatch(i18nSource, /aiDataCollection|databaseOrDataCollection/);

console.log('Retired AI surface tests passed.');
