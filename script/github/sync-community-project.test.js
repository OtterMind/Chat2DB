const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const {
  ProjectSync,
  closingIssueNumbers,
  fallbackIssueStatus,
  issueEventStatus,
  pullRequestStatus,
} = require('./sync-community-project');

test('isolates workflow concurrency by content type and number', () => {
  const workflow = fs.readFileSync(
    path.resolve(__dirname, '../../.github/workflows/community-project-sync.yml'),
    'utf8',
  );
  assert.match(
    workflow,
    /group: community-project-sync-\$\{\{ github\.repository \}\}-\$\{\{ github\.event\.issue && format\('issue-\{0\}', github\.event\.issue\.number\) \|\| github\.event\.pull_request && format\('pull-request-\{0\}', github\.event\.pull_request\.number\) \|\| 'reconcile' \}\}/,
  );
  assert.match(workflow, /cancel-in-progress: false/);
});

test('extracts unique same-repository closing references', () => {
  assert.deepEqual(closingIssueNumbers('Closes #12\nfixes #12\nResolves #34'), [12, 34]);
  assert.deepEqual(closingIssueNumbers('Related to #99'), []);
});

test('maps pull request lifecycle to implementation states', () => {
  assert.equal(pullRequestStatus('opened', { state: 'open', draft: true }), 'In Progress');
  assert.equal(pullRequestStatus('opened', { state: 'open', draft: false }), 'In Review');
  assert.equal(pullRequestStatus('closed', { state: 'closed', draft: false }), 'Done');
  assert.equal(pullRequestStatus('opened', { state: 'open', draft: false, merged: true }), 'Done');
});

test('returns an unmerged issue to the correct executable state', () => {
  assert.equal(fallbackIssueStatus({ state: 'closed', labels: [] }), 'Done');
  assert.equal(fallbackIssueStatus({ state: 'open', labels: ['contribution/help-wanted'] }), 'Ready');
  assert.equal(fallbackIssueStatus({ state: 'open', labels: [{ name: 'area/docs' }] }), 'Backlog');
});

test('uses Inbox only for new issues and preserves reopened task eligibility', () => {
  assert.equal(issueEventStatus('opened', { state: 'open', labels: [] }), 'Inbox');
  assert.equal(
    issueEventStatus('reopened', { state: 'open', labels: ['contribution/good-first-issue'] }),
    'Ready',
  );
  assert.equal(issueEventStatus('reopened', { state: 'open', labels: [] }), 'Backlog');
  assert.equal(issueEventStatus('closed', { state: 'closed', labels: [] }), 'Done');
});

test('loads every Project item page before reconciliation', async () => {
  const cursors = [];
  const client = {
    async graphql(query, variables) {
      cursors.push(variables.cursor);
      const secondPage = variables.cursor === 'next-page';
      return {
        organization: {
          projectV2: {
            id: 'project-id',
            fields: {
              nodes: [{
                id: 'status-field',
                name: 'Status',
                options: [{ id: 'done-option', name: 'Done' }],
              }],
            },
            items: {
              nodes: [{
                id: secondPage ? 'item-2' : 'item-1',
                content: {
                  id: secondPage ? 'content-2' : 'content-1',
                  number: secondPage ? 2 : 1,
                  state: 'OPEN',
                },
              }],
              pageInfo: {
                hasNextPage: !secondPage,
                endCursor: secondPage ? null : 'next-page',
              },
            },
          },
        },
      };
    },
  };
  const sync = new ProjectSync({ client, owner: 'OtterMind', projectNumber: 3 });
  await sync.load();
  assert.deepEqual(cursors, [null, 'next-page']);
  assert.equal(sync.itemsByContentId.size, 2);
});
