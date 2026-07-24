# Issue Triage

Chat2DB uses five separate fields for issue management. Do not encode the same
meaning in more than one field.

| Field | Purpose |
| --- | --- |
| Template | Collect the required submission format and evidence |
| Issue Type | Define the primary nature: Task, Bug, or Feature |
| Label | Classify area, database, platform, edition, and evidence needs |
| Priority | Define urgency in the Community Project |
| Project Status | Track lifecycle from intake to completion |

The machine-readable taxonomy is [`issue-taxonomy.json`](issue-taxonomy.json).

## Templates And Types

| Template | Issue Type | Use |
| --- | --- | --- |
| Bug report | Bug | Reproducible Community behavior that is incorrect |
| Database compatibility bug | Bug | Database-specific connection, metadata, SQL, or editor behavior |
| Feature request | Feature | A new capability or product improvement |
| Documentation task | Task | Documentation corrections, additions, examples, or translations |
| Maintainer task | Task | Tests, refactoring, build, release, or repository maintenance |

Questions belong in GitHub Discussions. Sensitive security reports are outside
this public issue process and must be submitted through the [security policy](../SECURITY.md).

## Labels

Labels are multi-select classifications:

- `area/*`: the owning product or code area.
- `db/*`: the affected database, when applicable.
- `platform/*`: the affected operating or deployment platform.
- `edition/*`: Community, Local, Pro, or unknown.
- `needs/*`: evidence or product decisions still required.
- `contribution/*`: tasks explicitly ready for external contributors.

After triage, an active issue must have:

1. exactly one Issue Type;
2. exactly one `edition/*` label;
3. at least one `area/*` or `db/*` label;
4. one Priority value;
5. one Project Status.

Do not create `type/*`, `priority/*`, or workflow-status labels. Issue Type,
Priority, and Project Status already own those dimensions.

## Form Option Mapping

Form options use contributor-facing language only. During triage, apply labels as
follows:

| Form value | Label |
| --- | --- |
| Web | `platform/web` |
| Desktop | `area/jcef` plus the selected operating-system platform |
| Docker | `platform/docker` |
| Windows, macOS, Linux | `platform/windows`, `platform/macos`, `platform/linux` |
| Database selection | matching `db/*` label, or `db/other` |
| AI | `area/ai` |
| Connection | `area/connection` |
| Database tree and metadata | `area/database-tree` |
| SQL execution or DDL, Database plugin | `area/backend` |
| SQL editor | `area/sql-editor` |
| Data editor | `area/data-editor` |
| Import or export | `area/import-export` |
| Desktop packaging | `area/jcef` |
| Docker build or image | `area/docker` |
| Documentation | `area/docs` |
| Other | `needs/decision` |

`area/docker` owns Dockerfile, image-build, and Compose code. `platform/docker`
means the observed problem only occurs in a Docker runtime. `area/frontend`
owns frontend code; `platform/web` means a web-only runtime problem.

## Priority

Priority is a single-select field in the Community Project. Reporters do not
assign it.

| Priority | Criteria | Response target |
| --- | --- | --- |
| P0 Critical | Security, data loss, startup failure, or release blocker with no workaround | Same day |
| P1 High | Core workflow unavailable for many users with no reliable workaround | 2 business days |
| P2 Normal | Normal confirmed issue or feature with limited impact or a workaround | 7 calendar days |
| P3 Low | Edge case, minor experience issue, or low-priority improvement | Monthly backlog review |

New issues start without a Priority. A triage maintainer assigns it after
checking impact, affected scope, reproducibility, and workarounds. P0 is limited
to Bugs and release-blocking Tasks. Sensitive security details remain private.

## Project Status

Project Status is a single-select lifecycle field:

| Status | Meaning |
| --- | --- |
| Inbox | New public Issue awaiting maintainer triage |
| Backlog | Confirmed work that is not ready to start |
| Ready | Scoped and published; unassigned or claimed before a pull request is linked |
| In Progress | A linked draft or regular pull request is under active implementation |
| In Review | The linked pull request is ready for maintainer review |
| Done | The Issue is closed or the pull request is merged |

Evidence gaps remain `needs/*` labels; they are not duplicate workflow statuses.
Assignment represents an active claim and is not a separate Status value.

## Publishing Contribution Tasks

An Issue becomes publicly claimable only when a maintainer:

1. completes the taxonomy and Priority fields;
2. confirms the scope, acceptance criteria, verification, and non-goals;
3. confirms that a maintainer can review the resulting pull request;
4. sets Project Status to `Ready`; and
5. applies `contribution/help-wanted` or `contribution/good-first-issue`.

Large Features and Bugs should remain the parent context. Create a bounded Task
sub-issue for the contribution when the complete Issue is too broad for one pull
request. Never publish private vulnerability details or Enterprise work as a
Community contribution task.

External contributors claim published tasks with `/claim`. Claims are exclusive,
limited to one active task per contributor, and expire after seven days without
a linked draft or regular pull request. `/renew` extends a pre-PR claim once;
`/unclaim` releases it. Maintainer review time does not consume the contributor's
claim lease.

The `contribution/*` label is the claim bot's machine-readable publication
switch. Apply it only after setting Status to `Ready`, and remove it whenever a
task moves back to `Inbox` or `Backlog`; label removal automatically releases an
active claim. The source-controlled Project workflow adds new Issues as
`Inbox`, moves linked draft pull requests to `In Progress`, ready pull requests
to `In Review`, and closed Issues or merged pull requests to `Done`.

Before publishing, apply the contribution-fit gate in
[`contribution-boundaries.yml`](contribution-boundaries.yml). `open` work can be
scoped, `approval-required` work needs a recorded design or ownership decision,
and `closed` work must be declined with the listed reason and alternative.

Every published task must append the complete `Maintainer Ready Contract` from
[`COMMUNITY_OPERATIONS.md`](COMMUNITY_OPERATIONS.md). A contribution label
without that contract, a named review maintainer, or executable verification is
a publishing defect and must be removed during weekly reconciliation.

## Ownership And Cadence

The current primary Product, Triage, Review, and Release owner is `@openai0229`.
Each Ready Issue names its actual review maintainer. A backup reviewer is
required for Milestone-committed work; leave work outside the Milestone when no
second maintainer has accepted that responsibility.

- Daily: P0/P1 triage, due contributor responses, and claim automation health.
- Weekly: empty Inbox, replenish Ready inventory, and reconcile Project drift.
- Monthly: review P3 and decision backlog, boundaries, and observed funnel data.

Ready-Issue questions target three business days, first substantive pull
request reviews target five business days, and follow-up reviews target three
business days. Automated comments do not satisfy these targets.

## Triage Procedure

1. Confirm the issue is for Chat2DB Community or apply the correct `edition/*`
   label and reroute it.
2. Confirm the Issue Type set by the template.
3. Add the primary `area/*` label and any applicable `db/*` or `platform/*`
   labels.
4. Add `needs/info`, `needs/reproduction`, or `needs/decision` when evidence or
   a product decision is missing.
5. Assign Priority, owner, Milestone (target release), and Project Status.
6. Close duplicates or completed work with a concrete link and GitHub state
   reason.

Choose one explicit result after those fields are set: close or reroute, keep in
Backlog with the missing evidence or decision named, assign for internal work,
or publish through the Ready contract. Do not leave reviewed Issues in Inbox.

## Milestones And Release

Milestones are versioned product delivery windows, not workflow phases. Create
one only when there is a user outcome, due date, release owner, inclusion rule,
exit criteria, and move-out rule. Only scoped work with an owner and executable
acceptance evidence enters a Milestone.

Before closing a Milestone, move every incomplete Issue with a public reason,
publish and verify the GitHub Release, check promised artifacts and update paths,
and link the release notes. Project Status continues to describe workflow while
Milestone describes the delivery commitment.

The label sync script only creates or updates labels from the taxonomy. It
never deletes legacy labels.

## Legacy Label Migration

| Legacy label | New field |
| --- | --- |
| `bug` | Issue Type = Bug |
| `enhancement` | Issue Type = Feature |
| `AI-bug` | Type = Bug plus `area/ai` |
| Database `*-bug` labels | Type = Bug plus matching `db/*` |
| `connection`, `data editor`, `import/export`, `sql editor`, `ui`, `documentation` | matching `area/*` |
| `planned`, `Planning but not high priority` | Project Status and Priority |
| `wait for response`, `need testing`, `ambiguous`, `can't reproduce` | matching `needs/*` |
| `wait for review` | Project Status |
| `question` | GitHub Discussions Q&A |
| `Ch2DBPro` | `edition/pro` and the Pro support route |

Do not delete legacy labels until every attached issue has been migrated. During
migration, mark old labels as deprecated so maintainers do not apply them to new
issues.

## Rollout Order

1. Review the taxonomy and issue forms.
2. Run `script/github/sync-issue-labels.sh` without `--apply`.
3. Run the script with `--apply` to create the referenced labels.
4. Verify the labels, then merge and push the issue forms.
5. Run `script/github/configure-community-project.sh --project <number>` to
   review the target configuration, then add `--apply`. The script creates or
   validates Project metadata, Status, Priority, repository linkage, and saved
   views. It also creates a missing Project when `--project` is omitted.
6. In the Project UI, add the `Type` column where useful and rename or remove
   the default `View 1`. GitHub does not expose update APIs for those settings.
   Project lifecycle automation is source-controlled in
   `community-project-sync.yml` and uses the repository `ACCESS_TOKEN` secret.

Do not bulk-import the historical backlog. The Project auto-add workflow adds new
matching Issues and later re-activated historical Issues without backfilling all
existing open Issues. Migrate other legacy Issues only in reviewed cohorts.

Issue forms silently skip labels that do not exist, so label creation must
happen before the forms become active. Until the Project exists, do not replace
Priority with priority labels.
