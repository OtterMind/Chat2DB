import assert from 'node:assert/strict';
import {
  collectExpandedWorkspaceTreeNodeKeys,
  mergeWorkspaceTreeSearchExpandedKeys,
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
    children: [
      {
        key: 'database_app',
        children: [
          {
            key: 'tables',
            children: [
              {
                key: 'table_sms_course_sections',
                children: [{ key: 'columns', children: [] }],
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

console.log('Workspace tree search lifecycle tests passed');
