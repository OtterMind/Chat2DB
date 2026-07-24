#!/usr/bin/env node

const fs = require('node:fs');
const path = require('node:path');

const ELIGIBLE_LABELS = new Set([
  'contribution/good-first-issue',
  'contribution/help-wanted',
]);

function closingIssueNumbers(body) {
  const numbers = new Set();
  const pattern = /(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\s+#(\d+)/gi;
  for (const match of String(body || '').matchAll(pattern)) {
    numbers.add(Number(match[1]));
  }
  return [...numbers];
}

function pullRequestStatus(action, pullRequest) {
  if (action === 'closed' || pullRequest.state === 'closed' || pullRequest.merged) {
    return 'Done';
  }
  return pullRequest.draft ? 'In Progress' : 'In Review';
}

function fallbackIssueStatus(issue) {
  if (String(issue.state).toLowerCase() === 'closed') return 'Done';
  const labels = new Set((issue.labels || []).map((label) => label.name || label));
  return [...ELIGIBLE_LABELS].some((label) => labels.has(label)) ? 'Ready' : 'Backlog';
}

function issueEventStatus(action, issue) {
  if (action === 'closed') return 'Done';
  if (action === 'reopened') return fallbackIssueStatus(issue);
  return 'Inbox';
}

class GitHubClient {
  constructor({ token, repository, apiUrl = 'https://api.github.com' }) {
    if (!token) throw new Error('GH_PROJECT_TOKEN is required.');
    const [owner, repo] = String(repository || '').split('/');
    if (!owner || !repo) throw new Error('GITHUB_REPOSITORY must be owner/repository.');
    this.token = token;
    this.owner = owner;
    this.repo = repo;
    this.apiUrl = apiUrl.replace(/\/$/, '');
  }

  async request(endpoint, { method = 'GET', body } = {}) {
    const response = await fetch(`${this.apiUrl}${endpoint}`, {
      method,
      headers: {
        Accept: 'application/vnd.github+json',
        Authorization: `Bearer ${this.token}`,
        'Content-Type': 'application/json',
        'X-GitHub-Api-Version': '2022-11-28',
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const payload = response.status === 204 ? null : await response.json();
    if (!response.ok) {
      throw new Error(`GitHub API ${method} ${endpoint} failed (${response.status}): ${JSON.stringify(payload).slice(0, 800)}`);
    }
    return payload;
  }

  async graphql(query, variables) {
    const payload = await this.request('/graphql', {
      method: 'POST',
      body: { query, variables },
    });
    if (payload.errors?.length) {
      throw new Error(`GitHub GraphQL failed: ${JSON.stringify(payload.errors).slice(0, 1000)}`);
    }
    return payload.data;
  }

  getIssue(number) {
    return this.request(`/repos/${this.owner}/${this.repo}/issues/${number}`);
  }

  async getClosingIssues(pullRequestNumber) {
    const data = await this.graphql(`
      query ClosingIssues($owner: String!, $repo: String!, $number: Int!) {
        repository(owner: $owner, name: $repo) {
          pullRequest(number: $number) {
            closingIssuesReferences(first: 50) {
              nodes { id number state labels(first: 50) { nodes { name } } }
            }
          }
        }
      }
    `, { owner: this.owner, repo: this.repo, number: pullRequestNumber });
    return data.repository.pullRequest.closingIssuesReferences.nodes.map((issue) => ({
      ...issue,
      labels: issue.labels.nodes,
    }));
  }
}

class ProjectSync {
  constructor({ client, owner, projectNumber }) {
    this.client = client;
    this.owner = owner;
    this.projectNumber = projectNumber;
    this.itemsByContentId = new Map();
  }

  async load() {
    let cursor = null;
    let firstPage = true;
    let hasNextPage = false;
    do {
      const data = await this.client.graphql(`
      query CommunityProject($owner: String!, $number: Int!, $cursor: String) {
        organization(login: $owner) {
          projectV2(number: $number) {
            id
            fields(first: 100) {
              nodes {
                ... on ProjectV2SingleSelectField {
                  id name options { id name }
                }
              }
            }
            items(first: 100, after: $cursor) {
              nodes {
                id
                content {
                  ... on Issue { id number state }
                  ... on PullRequest { id number state isDraft merged }
                }
              }
              pageInfo { hasNextPage endCursor }
            }
          }
        }
      }
      `, { owner: this.owner, number: this.projectNumber, cursor });
      const project = data.organization?.projectV2;
      if (!project) throw new Error(`Project ${this.owner}/${this.projectNumber} was not found.`);
      if (firstPage) {
        this.projectId = project.id;
        const statusField = project.fields.nodes.find((field) => field?.name === 'Status');
        if (!statusField) throw new Error('Project Status field was not found.');
        this.statusFieldId = statusField.id;
        this.statusOptions = new Map(statusField.options.map((option) => [option.name, option.id]));
        firstPage = false;
      }
      for (const item of project.items.nodes) {
        if (item.content?.id) this.itemsByContentId.set(item.content.id, { ...item, content: item.content });
      }
      hasNextPage = project.items.pageInfo.hasNextPage;
      cursor = project.items.pageInfo.endCursor;
    } while (hasNextPage);
  }

  async ensureItem(content) {
    const existing = this.itemsByContentId.get(content.id);
    if (existing) return existing;
    const data = await this.client.graphql(`
      mutation AddCommunityProjectItem($project: ID!, $content: ID!) {
        addProjectV2ItemById(input: {projectId: $project, contentId: $content}) {
          item { id }
        }
      }
    `, { project: this.projectId, content: content.id });
    const item = { id: data.addProjectV2ItemById.item.id, content };
    this.itemsByContentId.set(content.id, item);
    return item;
  }

  async setStatus(content, status) {
    const optionId = this.statusOptions.get(status);
    if (!optionId) throw new Error(`Project Status option not found: ${status}`);
    const item = await this.ensureItem(content);
    await this.client.graphql(`
      mutation SetCommunityProjectStatus($project: ID!, $item: ID!, $field: ID!, $option: String!) {
        updateProjectV2ItemFieldValue(input: {
          projectId: $project,
          itemId: $item,
          fieldId: $field,
          value: {singleSelectOptionId: $option}
        }) { projectV2Item { id } }
      }
    `, {
      project: this.projectId,
      item: item.id,
      field: this.statusFieldId,
      option: optionId,
    });
    console.log(`set #${content.number} ${content.kind || 'item'} to ${status}`);
  }

  async reconcile() {
    for (const item of this.itemsByContentId.values()) {
      const content = item.content;
      if (content.state === 'CLOSED' || content.state === 'MERGED' || content.merged) {
        await this.setStatus(content, 'Done');
      } else if (content.isDraft !== undefined) {
        await this.setStatus(content, content.isDraft ? 'In Progress' : 'In Review');
      }
    }
  }
}

async function handleIssue(sync, payload) {
  const issue = { ...payload.issue, id: payload.issue.node_id, kind: 'Issue' };
  const status = issueEventStatus(payload.action, issue);
  await sync.setStatus(issue, status);
}

async function handlePullRequest(sync, client, payload) {
  const pullRequest = { ...payload.pull_request, id: payload.pull_request.node_id, kind: 'PullRequest' };
  const status = pullRequestStatus(payload.action, pullRequest);
  await sync.setStatus(pullRequest, status);

  const issueMap = new Map();
  for (const issue of await client.getClosingIssues(pullRequest.number)) issueMap.set(issue.number, issue);
  for (const number of closingIssueNumbers(pullRequest.body)) {
    if (!issueMap.has(number)) {
      const issue = await client.getIssue(number);
      issueMap.set(number, { ...issue, id: issue.node_id });
    }
  }

  for (const issue of issueMap.values()) {
    issue.kind = 'Issue';
    const issueStatus = payload.action === 'closed' && !pullRequest.merged
      ? fallbackIssueStatus(issue)
      : status;
    await sync.setStatus(issue, issueStatus);
  }
}

async function run() {
  const root = path.resolve(__dirname, '../..');
  const taxonomy = JSON.parse(fs.readFileSync(path.join(root, '.github', 'issue-taxonomy.json'), 'utf8'));
  const eventName = process.env.GITHUB_EVENT_NAME || 'workflow_dispatch';
  const eventPath = process.env.GITHUB_EVENT_PATH;
  const payload = eventPath && fs.existsSync(eventPath)
    ? JSON.parse(fs.readFileSync(eventPath, 'utf8'))
    : {};
  const client = new GitHubClient({
    token: process.env.GH_PROJECT_TOKEN,
    repository: process.env.GITHUB_REPOSITORY || taxonomy.project.repository,
    apiUrl: process.env.GITHUB_API_URL,
  });
  const sync = new ProjectSync({
    client,
    owner: taxonomy.project.owner,
    projectNumber: Number(process.env.COMMUNITY_PROJECT_NUMBER || 3),
  });
  await sync.load();

  if (eventName === 'issues') return handleIssue(sync, payload);
  if (eventName === 'pull_request_target' || eventName === 'pull_request') {
    return handlePullRequest(sync, client, payload);
  }
  return sync.reconcile();
}

module.exports = {
  ProjectSync,
  closingIssueNumbers,
  fallbackIssueStatus,
  issueEventStatus,
  pullRequestStatus,
};

if (require.main === module) {
  run().catch((error) => {
    console.error(error.message);
    process.exit(1);
  });
}
