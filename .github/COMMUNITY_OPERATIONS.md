# Community Operations

This runbook defines how Chat2DB Community work moves from intake to a shipped
release. It is the maintainer contract behind the public
[Community Project](https://github.com/orgs/OtterMind/projects/3).

## Sources Of Truth

Each concern has one owner. Do not duplicate status in labels or comments.

| Concern | Source of truth |
| --- | --- |
| Submission evidence | Issue form and Issue body |
| Work type | GitHub Issue Type |
| Product classification | `area/*`, `db/*`, `platform/*`, and `edition/*` labels |
| Missing evidence or decision | `needs/*` labels |
| Urgency | Project Priority |
| Lifecycle | Project Status |
| Active contributor | Assignee and the claim bot comment |
| Delivery commitment | Milestone |
| Implementation and verification | Linked pull request |
| User delivery | GitHub Release |

## Ownership And Response Targets

The current primary Product, Triage, Review, and Release owner is
[@openai0229](https://github.com/openai0229). A Ready Issue must also name its
review maintainer in the Issue body. A backup reviewer is required before a
task is committed to a Milestone; do not invent a backup when no second
maintainer has accepted the responsibility.

| Event | Target |
| --- | --- |
| P0 Issue acknowledgement | Same day |
| P1 Issue triage | 2 business days |
| P2 Issue triage | 7 calendar days |
| P3 Issue review | Monthly backlog review |
| Question on a Ready Issue | 3 business days |
| First substantive pull request review | 5 business days |
| Follow-up review after contributor changes | 3 business days |

An automated acknowledgement is not a substantive response. If a target will
be missed, the owner must post the blocker and next review date. If review
capacity disappears, remove the `contribution/*` label and move the Issue to
Backlog instead of leaving an unsupported task published.

Sensitive security reports use the private route in [`SECURITY.md`](../SECURITY.md)
and never enter this public queue.

## End-To-End Flow

### 1. Intake

- Reproducible defects use a Bug form.
- Product improvements use the Feature form.
- Documentation gaps use the Documentation task form.
- Repository, test, build, and maintenance work uses the Maintainer task form.
- Questions and open-ended ideas use GitHub Discussions.
- Pro, Local, or other commercial-edition work is rerouted and is not
  published as a Community contribution task.

New public Issues enter Project Status `Inbox`.

### 2. Triage

The triage maintainer must set exactly one Issue Type and `edition/*` label, at
least one `area/*` or `db/*` label, one Priority, and one Project Status.
Evidence gaps use `needs/info`, `needs/reproduction`, or `needs/decision`.

Choose one outcome:

- close with a concrete duplicate, completion, support, or boundary reason;
- keep in `Backlog` while evidence or a decision is missing;
- assign for internal work;
- turn the Issue into a contributor-ready task.

### 3. Contribution-Fit Gate

Use [`contribution-boundaries.yml`](contribution-boundaries.yml) before
publishing a task.

- `open`: maintainers may scope and publish the task.
- `approval-required`: record the design or ownership decision before work.
- `closed`: do not accept public implementation; explain the reason and offer
  the listed alternative.

An `open` boundary does not make an Issue Ready by itself. Scope, verification,
and review capacity are still mandatory.

### 4. Ready Contract

Before applying a `contribution/*` label, append a `Maintainer Ready Contract`
to the Issue containing all of the following:

```markdown
## Maintainer Ready Contract

- User outcome:
- In scope:
- Non-goals:
- Suggested code or documentation area:
- Acceptance criteria:
- Exact verification:
- Dependencies or required environment:
- Review maintainer: @login
- First substantive review target: 5 business days
- Milestone: version or `Not release-committed`
```

Then set Project Status to `Ready`, set Priority, and apply exactly one of:

- `contribution/good-first-issue` for bounded work with a short setup path and
  an established implementation pattern;
- `contribution/help-wanted` for work that requires broader codebase or domain
  knowledge.

### 5. Claim And Implementation

The contributor comments `/claim`. The bot assigns one seven-day pre-PR lease;
`/claim status`, one `/renew`, and `/unclaim` are supported. The contributor
creates a focused branch, reproduces the baseline, and opens a linked draft or
regular pull request with `Closes #<issue>`.

A linked draft pull request moves the Project item to `In Progress`. A pull
request that is ready for maintainer review moves to `In Review`.

### 6. Review And Merge

Review in this order:

1. Issue scope and non-goals.
2. User-visible correctness and compatibility.
3. Tests and the exact verification contract.
4. Security, privacy, and Community/commercial boundaries.
5. Documentation, migration, and rollback needs.

All required checks and review conversations must pass before merge. When a
real second reviewer joins the rotation, enable a required approving review on
`main`; until then, do not claim that independent review is enforced.

### 7. Milestone And Release

Milestones are product delivery windows, never workflow columns. A Milestone
must state the user outcome, due date, release owner, inclusion rule, exit
criteria, and move-out rule. Only scoped work with an owner and executable
acceptance evidence is committed.

Before closing a Milestone, the release owner verifies:

- every included Issue is closed or moved with a public reason;
- release artifacts and checksums exist for the promised platforms;
- updater and Docker paths are checked when applicable;
- release notes link the delivered Issues and pull requests;
- post-release installation or smoke verification is recorded.

The Milestone closes only after the GitHub Release is published and verified.

## Project State Matrix

| Evidence | Project Status |
| --- | --- |
| New Issue awaiting triage | Inbox |
| Confirmed but not executable | Backlog |
| Ready contract complete, no linked PR | Ready |
| Linked draft or regular PR | In Progress |
| PR ready for maintainer review | In Review |
| Issue closed or PR merged | Done |

The maintainer checks the Project weekly for closed items outside `Done`, open
items in `Done`, Ready items without a `contribution/*` label, published tasks
without a review owner, and expired Milestones.

## Operating Cadence

### Daily

- Triage new P0/P1 Issues.
- Answer contributor questions and pull request reviews due that day.
- Release expired claims through the scheduled claim workflow.

### Weekly

- Empty `Inbox` or record the owner and next action for each remaining item.
- Keep at least six unassigned Ready tasks when review capacity allows: two
  good-first tasks and four help-wanted tasks.
- Reconcile Project status, contribution labels, assignees, linked pull
  requests, and Milestones.

### Monthly

- Review P3 and `needs/decision` backlog items.
- Revisit contribution boundaries whose `review_after` date is approaching.
- Publish counts for new Issues, triaged Issues, Ready tasks, claims, first-time
  contributor pull requests, first review time, merges, and releases.

Metrics describe observed events only. Do not report a successful external
contribution, elapsed response time, claim expiry, or release until that event
has actually occurred.
