import assert from 'node:assert/strict';
import {
  collectExpandedWorkspaceTreeNodeKeys,
  createWorkspaceTreeSearchQueryState,
  mergeWorkspaceTreeSearchExpandedKeys,
  resolveWorkspaceTreeSearch,
  transitionWorkspaceTreeSearchQuery,
  type WorkspaceTreeSearchEvent,
} from './lifecycle';
import type { TreeNodeData } from '@/typings';

const query = 'sms_course_sections';
const preservingEvents: WorkspaceTreeSearchEvent[] = [
  { type: 'tree-select' },
  { type: 'tree-expand' },
  { type: 'tree-context-menu' },
  { type: 'tree-blank-click' },
  { type: 'focus-change' },
  { type: 'refresh-start' },
  { type: 'refresh-success' },
  { type: 'refresh-failure' },
  { type: 'lazy-load' },
  { type: 'active-tab-change' },
];

preservingEvents.forEach((event) => {
  assert.equal(
    transitionWorkspaceTreeSearchQuery(query, event),
    query,
    `${event.type} must not terminate the active search session`,
  );
});

assert.equal(transitionWorkspaceTreeSearchQuery(query, { type: 'query-change', value: 'sms_courses' }), 'sms_courses');
assert.equal(transitionWorkspaceTreeSearchQuery(query, { type: 'exit' }), '');

assert.deepEqual(
  createWorkspaceTreeSearchQueryState('new.query', 4),
  {
    searchBarValue: 'new.query',
    regularSearchBarValue: 'new\\.query',
    searchResultKeys: null,
    searchResult: null,
    searchRequiredExpandedKeys: [],
    searchRevision: 5,
  },
  'a query change must synchronously retire pre-debounce expansion paths and advance its revision',
);

assert.deepEqual(
  mergeWorkspaceTreeSearchExpandedKeys(
    ['dataSource_1', 'database_app', 'tables', 'table_sms_course_sections'],
    ['dataSource_1', 'database_app', 'tables'],
  ),
  ['dataSource_1', 'database_app', 'tables', 'table_sms_course_sections'],
  'refresh must preserve an expanded directly matched table',
);

const tree = [
  {
    key: 'dataSource_1',
    originalTitle: 'primary',
    children: [
      {
        key: 'database_app',
        originalTitle: 'app',
        children: [
          {
            key: 'tables',
            originalTitle: 'tables',
            children: [
              {
                key: 'table_sms_course_sections',
                originalTitle: 'sms_course_sections',
                children: [{ key: 'columns', originalTitle: 'columns', children: [] }],
              },
            ],
          },
        ],
      },
    ],
  },
] as TreeNodeData[];
assert.deepEqual(
  collectExpandedWorkspaceTreeNodeKeys(tree, [
    'table_sms_course_sections',
    'dataSource_1',
    'columns',
    'database_app',
    'tables',
  ]),
  ['dataSource_1', 'database_app', 'tables', 'table_sms_course_sections', 'columns'],
  'active-search refresh targets must be ordered from ancestors to descendants',
);
assert.deepEqual(
  mergeWorkspaceTreeSearchExpandedKeys(['dataSource_1'], ['dataSource_1', 'database_app', 'tables']),
  ['dataSource_1', 'database_app', 'tables'],
  'search must add newly required ancestor paths without duplicates',
);

const currentSearch = resolveWorkspaceTreeSearch(tree, 'smscourses', null);
assert.deepEqual(
  currentSearch.requiredExpandedKeys,
  ['tables', 'database_app', 'dataSource_1'],
  'required expansion paths must be derived from the current query rather than historical expanded keys',
);
assert.deepEqual(
  resolveWorkspaceTreeSearch(tree, 'smscourses', null, ['tables']),
  { matchedNodes: [], matchedKeys: [], requiredExpandedKeys: [] },
  'search must not expose descendants from a logically invalidated cache',
);

const staleHistoryTree = [
  {
    key: 'old-root',
    children: [{ key: 'old-child', children: [{ key: 'old-grandchild', children: [] }] }],
  },
  {
    key: 'current-root',
    children: [{ key: 'current-child', children: [] }],
  },
] as TreeNodeData[];
assert.deepEqual(
  collectExpandedWorkspaceTreeNodeKeys(
    staleHistoryTree,
    ['old-child', 'old-grandchild'],
    ['current-root', 'current-child'],
  ),
  ['current-root', 'current-child'],
  'descendant keys from a collapsed historical branch must not preserve or refresh its caches',
);

console.log('Workspace tree search lifecycle tests passed');
