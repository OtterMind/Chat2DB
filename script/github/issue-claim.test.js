'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const test = require('node:test');
const path = require('node:path');

const {
  GitHubClient,
  cleanupClaim,
  emptyState,
  isEligibleIssue,
  loadPolicy,
  parseCommand,
  parseStateMarker,
  processEvent,
  reconcileExpiredClaim,
  renderStateComment,
  serializeStateMarker,
  sweepExpiredClaims,
  transitionClaim,
} = require('./issue-claim');

const NOW = '2026-07-16T00:00:00.000Z';
const POLICY = {
  version: 1,
  eligibleLabels: ['contribution/help-wanted', 'contribution/good-first-issue'],
  leaseDays: 7,
  maxRenewals: 1,
  maxActiveClaimsPerUser: 1,
};

function issue(overrides = {}) {
  return {
    number: 42,
    state: 'open',
    labels: [{ name: 'contribution/help-wanted' }],
    assignees: [],
    ...overrides,
  };
}

function transition(command, overrides = {}) {
  return transitionClaim({
    state: emptyState(42, NOW),
    command,
    actor: 'contributor',
    now: NOW,
    policy: POLICY,
    issue: issue(),
    ...overrides,
  });
}

test('parses only supported standalone commands', () => {
  assert.equal(parseCommand('/claim'), 'claim');
  assert.equal(parseCommand('/UNCLAIM'), 'unclaim');
  assert.equal(parseCommand('/renew'), 'renew');
  assert.equal(parseCommand('/claim status'), 'status');
  assert.equal(parseCommand('/claim please'), null);
  assert.equal(parseCommand('please /claim'), null);
  assert.equal(parseCommand(' /claim'), null);
});

test('loads and validates the repository policy used in production', () => {
  const policy = loadPolicy(path.resolve(__dirname, '../../.github/claim-policy.json'));
  assert.deepEqual(policy, POLICY);
});

test('uses supported workflow concurrency configuration', () => {
  const workflow = fs.readFileSync(
    path.resolve(__dirname, '../../.github/workflows/issue-claim.yml'),
    'utf8',
  );

  assert.doesNotMatch(workflow, /^\s*queue:/m);
  assert.match(
    workflow,
    /group: issue-claim-\$\{\{ github\.repository \}\}-cleanup-\$\{\{ github\.event\.issue\.number \}\}/,
  );
});

test('requires an open issue with an eligible contribution label', () => {
  assert.equal(isEligibleIssue(issue(), POLICY), true);
  assert.equal(isEligibleIssue(issue({ state: 'closed' }), POLICY), false);
  assert.equal(isEligibleIssue(issue({ labels: [{ name: 'area/backend' }] }), POLICY), false);
  assert.equal(isEligibleIssue(issue({ pull_request: {} }), POLICY), false);
});

test('round-trips state through the hidden marker', () => {
  const state = emptyState(42, NOW);
  assert.deepEqual(parseStateMarker(serializeStateMarker(state)), state);
  assert.equal(parseStateMarker('<!-- chat2db-claim-state:not-json -->'), null);
  assert.equal(parseStateMarker('<!-- chat2db-claim-state:{"version":1,"issue":42,"status":"active","renewals":0} -->'), null);
});

test('claims an eligible unassigned issue for seven days', () => {
  const result = transition('claim');
  assert.equal(result.ok, true);
  assert.equal(result.code, 'claimed');
  assert.equal(result.state.status, 'active');
  assert.equal(result.state.claimant, 'contributor');
  assert.equal(result.state.expiresAt, '2026-07-23T00:00:00.000Z');
  assert.deepEqual(result.effects, [{ type: 'add-assignee', login: 'contributor' }]);
});

test('rejects claims for ineligible, assigned, or already-busy work', () => {
  assert.equal(transition('claim', { issue: issue({ state: 'closed' }) }).code, 'issue_not_eligible');
  assert.equal(transition('claim', {
    issue: issue({ assignees: [{ login: 'maintainer' }] }),
  }).code, 'issue_already_assigned');
  assert.equal(transition('claim', { actorHasOtherActiveClaim: true }).code, 'actor_has_other_claim');
});

test('prevents a second contributor from taking an active claim', () => {
  const active = transition('claim').state;
  assert.equal(transition('claim', { state: active }).code, 'already_claimed');
  assert.equal(transition('claim', { state: active, actor: 'other-user' }).code, 'claimed_by_other');
});

test('reports a manually assigned task as unavailable', () => {
  const result = transition('status', {
    issue: issue({ assignees: [{ login: 'maintainer' }] }),
  });
  assert.equal(result.code, 'status_assigned');
});

test('allows claimant or maintainer to release and removes the claimant assignment', () => {
  const active = transition('claim').state;
  const denied = transition('unclaim', { state: active, actor: 'other-user' });
  assert.equal(denied.code, 'not_claimant');

  const released = transition('unclaim', { state: active });
  assert.equal(released.state.status, 'released');
  assert.deepEqual(released.effects, [{ type: 'remove-assignee', login: 'contributor' }]);

  const maintainer = transition('unclaim', {
    state: active,
    actor: 'maintainer',
    canManage: true,
  });
  assert.equal(maintainer.code, 'released');
  assert.equal(maintainer.state.reason, 'released_by_maintainer');
});

test('allows one renewal and then enforces the renewal limit', () => {
  const active = transition('claim').state;
  const renewed = transition('renew', {
    state: active,
    now: '2026-07-20T00:00:00.000Z',
  });
  assert.equal(renewed.code, 'renewed');
  assert.equal(renewed.state.expiresAt, '2026-07-27T00:00:00.000Z');
  assert.equal(renewed.state.renewals, 1);
  assert.equal(transition('renew', { state: renewed.state }).code, 'renewal_limit_reached');
});

test('does not renew a lease already protected by a linked pull request', () => {
  const active = transition('claim').state;
  const result = transition('renew', { state: active, hasValidPullRequest: true });
  assert.equal(result.code, 'lease_paused_by_pr');
  assert.equal(result.state.expiresAt, active.expiresAt);
});

test('expires an overdue claim only when no valid pull request exists', () => {
  const active = transition('claim').state;
  const expired = reconcileExpiredClaim(active, '2026-07-24T00:00:00.000Z', false);
  assert.equal(expired.state.status, 'expired');
  assert.deepEqual(expired.effects, [{ type: 'remove-assignee', login: 'contributor' }]);

  const protectedClaim = reconcileExpiredClaim(active, '2026-07-24T00:00:00.000Z', true);
  assert.equal(protectedClaim.state.status, 'active');
  assert.deepEqual(protectedClaim.effects, []);
});

test('renders one machine-readable state marker in the bot comment', () => {
  const active = transition('claim').state;
  const body = renderStateComment(active, 'Claimed.', POLICY);
  assert.equal((body.match(/chat2db-claim-state/g) || []).length, 1);
  assert.deepEqual(parseStateMarker(body), active);
});

function event(body, actor = 'contributor') {
  return {
    issue: { number: 42 },
    comment: {
      body,
      author_association: 'NONE',
      user: { login: actor, type: 'User' },
    },
  };
}

test('processes a claim by assigning the user and creating one state comment', async () => {
  const calls = [];
  const client = {
    async getIssue() { return issue(); },
    async getStateRecord() { return null; },
    async actorHasOtherActiveClaim() { return false; },
    async addAssignee(number, login) { calls.push(['add', number, login]); },
    async upsertStateComment(number, record, body) {
      calls.push(['comment', number, record, parseStateMarker(body)]);
    },
  };

  const result = await processEvent({ client, policy: POLICY, event: event('/claim'), now: NOW });
  assert.equal(result, 'claimed');
  assert.deepEqual(calls[0], ['add', 42, 'contributor']);
  assert.equal(calls[1][0], 'comment');
  assert.equal(calls[1][3].status, 'active');
});

test('ignores commands on issues that are not eligible contribution tasks', async () => {
  let stateRead = false;
  const client = {
    async getIssue() { return issue({ labels: [{ name: 'area/backend' }] }); },
    async getStateRecord() { stateRead = true; },
  };

  const result = await processEvent({ client, policy: POLICY, event: event('/claim'), now: NOW });
  assert.equal(result, 'ignored');
  assert.equal(stateRead, true);
});

test('releases an expired assignee before allowing a new claim', async () => {
  const calls = [];
  const oldState = {
    ...transition('claim').state,
    claimant: 'old-user',
    expiresAt: '2026-07-15T00:00:00.000Z',
  };
  const currentIssue = issue({ assignees: [{ login: 'old-user' }] });
  const record = { comment: { id: 100 }, state: oldState };
  const client = {
    async getIssue() { return currentIssue; },
    async getStateRecord() { return record; },
    async findValidPullRequest() { return null; },
    async actorHasOtherActiveClaim() { return false; },
    async removeAssignee(number, login) {
      calls.push(['remove', number, login]);
      currentIssue.assignees = currentIssue.assignees.filter((item) => item.login !== login);
    },
    async addAssignee(number, login) {
      calls.push(['add', number, login]);
      currentIssue.assignees.push({ login });
    },
    async upsertStateComment(number, existing, body) {
      calls.push(['comment', number, existing.comment.id, parseStateMarker(body)]);
    },
  };

  const result = await processEvent({
    client,
    policy: POLICY,
    event: event('/claim', 'new-user'),
    now: NOW,
  });
  assert.equal(result, 'claimed');
  assert.deepEqual(calls.slice(0, 2), [
    ['remove', 42, 'old-user'],
    ['add', 42, 'new-user'],
  ]);
  assert.equal(calls[2][3].claimant, 'new-user');
});

test('reports an assignment failure without recording an active claim', async () => {
  let persistedState;
  const client = {
    async getIssue() { return issue(); },
    async getStateRecord() { return null; },
    async actorHasOtherActiveClaim() { return false; },
    async addAssignee() { throw new Error('assignment denied'); },
    async upsertStateComment(number, record, body) {
      persistedState = parseStateMarker(body);
    },
  };

  await assert.rejects(
    processEvent({ client, policy: POLICY, event: event('/claim'), now: NOW }),
    /assignment denied/,
  );
  assert.equal(persistedState.status, 'unclaimed');
  assert.equal(persistedState.reason, 'assignment_failed');
});

test('rolls back assignment when the claim state comment cannot be persisted', async () => {
  const calls = [];
  const client = {
    async getIssue() { return issue(); },
    async getStateRecord() { return null; },
    async actorHasOtherActiveClaim() { return false; },
    async addAssignee(number, login) { calls.push(['add', number, login]); },
    async removeAssignee(number, login) { calls.push(['remove', number, login]); },
    async upsertStateComment() { throw new Error('comment write failed'); },
  };

  await assert.rejects(
    processEvent({ client, policy: POLICY, event: event('/claim'), now: NOW }),
    /comment write failed/,
  );
  assert.deepEqual(calls, [
    ['add', 42, 'contributor'],
    ['remove', 42, 'contributor'],
  ]);
});

test('restores an assignee when releasing the claim cannot be persisted', async () => {
  const calls = [];
  const active = transition('claim').state;
  const client = {
    async getIssue() {
      return issue({ assignees: [{ login: 'contributor' }] });
    },
    async getStateRecord() { return { comment: { id: 100 }, state: active }; },
    async findValidPullRequest() { return null; },
    async removeAssignee(number, login) { calls.push(['remove', number, login]); },
    async addAssignee(number, login) { calls.push(['add', number, login]); },
    async upsertStateComment() { throw new Error('comment write failed'); },
  };

  await assert.rejects(
    processEvent({ client, policy: POLICY, event: event('/unclaim'), now: NOW }),
    /comment write failed/,
  );
  assert.deepEqual(calls, [
    ['remove', 42, 'contributor'],
    ['add', 42, 'contributor'],
  ]);
});

test('accepts only a post-claim open closing PR authored by the claimant', async () => {
  const client = new GitHubClient({ token: 'test', repository: 'OtterMind/Chat2DB' });
  client.graphql = async () => ({
    repository: {
      issue: {
        closedByPullRequestsReferences: {
          nodes: [
            {
              number: 1,
              url: 'https://github.com/OtterMind/Chat2DB/pull/1',
              state: 'OPEN',
              createdAt: '2026-07-15T00:00:00.000Z',
              isDraft: false,
              author: { login: 'contributor' },
            },
            {
              number: 2,
              url: 'https://github.com/OtterMind/Chat2DB/pull/2',
              state: 'OPEN',
              createdAt: '2026-07-17T00:00:00.000Z',
              isDraft: false,
              author: { login: 'someone-else' },
            },
            {
              number: 3,
              url: 'https://github.com/OtterMind/Chat2DB/pull/3',
              state: 'MERGED',
              createdAt: '2026-07-17T00:00:00.000Z',
              isDraft: false,
              author: { login: 'contributor' },
            },
            {
              number: 4,
              url: 'https://github.com/OtterMind/Chat2DB/pull/4',
              state: 'OPEN',
              createdAt: '2026-07-17T00:00:00.000Z',
              isDraft: true,
              author: { login: 'contributor' },
            },
          ],
        },
      },
    },
  });

  const pullRequest = await client.findValidPullRequest(42, 'contributor', NOW);
  assert.deepEqual(pullRequest, {
    number: 4,
    html_url: 'https://github.com/OtterMind/Chat2DB/pull/4',
    isDraft: true,
  });
});

test('limits the active-claim scan to issues assigned to the contributor', async () => {
  const endpoints = [];
  const client = new GitHubClient({ token: 'test', repository: 'OtterMind/Chat2DB' });
  client.paginate = async (endpoint) => {
    endpoints.push(endpoint);
    return [];
  };

  const hasClaim = await client.actorHasOtherActiveClaim(
    'contributor',
    42,
    POLICY,
    NOW,
  );
  assert.equal(hasClaim, false);
  assert.equal(endpoints.length, POLICY.eligibleLabels.length);
  assert.equal(endpoints.every((endpoint) => endpoint.includes('assignee=contributor')), true);
});

test('releases an active claim when its contribution label is removed', async () => {
  const calls = [];
  const active = transition('claim').state;
  const currentIssue = issue({
    labels: [{ name: 'area/backend' }],
    assignees: [{ login: 'contributor' }],
  });
  const record = { comment: { id: 100 }, state: active };
  const client = {
    async getIssue() { return currentIssue; },
    async getStateRecord() { return record; },
    async removeAssignee(number, login) { calls.push(['remove', number, login]); },
    async upsertStateComment(number, existing, body) {
      calls.push(['comment', number, existing.comment.id, parseStateMarker(body)]);
    },
  };

  const result = await cleanupClaim({
    client,
    policy: POLICY,
    event: { issue: { number: 42 } },
    now: NOW,
  });
  assert.equal(result, 'task_unpublished');
  assert.deepEqual(calls[0], ['remove', 42, 'contributor']);
  assert.equal(calls[1][3].status, 'released');
  assert.equal(calls[1][3].reason, 'task_unpublished');
});

test('restores an assignee when cleanup state cannot be persisted', async () => {
  const calls = [];
  const active = transition('claim').state;
  const currentIssue = issue({
    labels: [{ name: 'area/backend' }],
    assignees: [{ login: 'contributor' }],
  });
  const client = {
    async getIssue() { return currentIssue; },
    async getStateRecord() { return { comment: { id: 100 }, state: active }; },
    async removeAssignee(number, login) { calls.push(['remove', number, login]); },
    async addAssignee(number, login) { calls.push(['add', number, login]); },
    async upsertStateComment() { throw new Error('comment write failed'); },
  };

  await assert.rejects(
    cleanupClaim({
      client,
      policy: POLICY,
      event: { issue: { number: 42 } },
      now: NOW,
    }),
    /comment write failed/,
  );
  assert.deepEqual(calls, [
    ['remove', 42, 'contributor'],
    ['add', 42, 'contributor'],
  ]);
});

test('allows an existing claimant to unclaim after the task is unpublished', async () => {
  const active = transition('claim').state;
  const result = transition('unclaim', {
    state: active,
    issue: issue({ labels: [{ name: 'area/backend' }] }),
  });
  assert.equal(result.code, 'released');
  assert.equal(result.state.status, 'released');
});

test('daily sweep releases only expired claims without a valid closing PR', async () => {
  const calls = [];
  const expiredState = {
    ...transition('claim').state,
    expiresAt: '2026-07-15T00:00:00.000Z',
  };
  const protectedState = {
    ...expiredState,
    issue: 43,
    claimant: 'second-user',
  };
  const issues = [
    issue({ assignees: [{ login: 'contributor' }] }),
    issue({ number: 43, assignees: [{ login: 'second-user' }] }),
  ];
  const records = new Map([
    [42, { comment: { id: 100 }, state: expiredState }],
    [43, { comment: { id: 101 }, state: protectedState }],
  ]);
  const client = {
    async listEligibleIssues() { return issues; },
    async getStateRecord(number) { return records.get(number); },
    async findValidPullRequest(number) {
      return number === 43
        ? { number: 99, html_url: 'https://github.com/OtterMind/Chat2DB/pull/99' }
        : null;
    },
    async removeAssignee(number, login) { calls.push(['remove', number, login]); },
    async upsertStateComment(number, record, body) {
      calls.push(['comment', number, record.comment.id, parseStateMarker(body)]);
    },
  };

  const released = await sweepExpiredClaims({ client, policy: POLICY, now: NOW });
  assert.equal(released, 1);
  assert.deepEqual(calls[0], ['remove', 42, 'contributor']);
  assert.equal(calls[1][3].status, 'expired');
  assert.equal(calls.some((call) => call[1] === 43), false);
});

test('restores an expired assignee when sweep state cannot be persisted', async () => {
  const calls = [];
  const expiredState = {
    ...transition('claim').state,
    expiresAt: '2026-07-15T00:00:00.000Z',
  };
  const client = {
    async listEligibleIssues() {
      return [issue({ assignees: [{ login: 'contributor' }] })];
    },
    async getStateRecord() {
      return { comment: { id: 100 }, state: expiredState };
    },
    async findValidPullRequest() { return null; },
    async removeAssignee(number, login) { calls.push(['remove', number, login]); },
    async addAssignee(number, login) { calls.push(['add', number, login]); },
    async upsertStateComment() { throw new Error('comment write failed'); },
  };

  await assert.rejects(
    sweepExpiredClaims({ client, policy: POLICY, now: NOW }),
    /Claim sweep failed.*comment write failed/,
  );
  assert.deepEqual(calls, [
    ['remove', 42, 'contributor'],
    ['add', 42, 'contributor'],
  ]);
});
