# CONTRIBUTING

Thanks for your interest in contributing to Chat2DB.

Chat2DB is an open-source database client and SQL workspace for developers, DBAs, analysts, and data teams. We welcome contributions of all kinds, including bug reports, feature requests, documentation improvements, testing feedback, and pull requests.

This guide explains how to report issues, suggest improvements, and submit pull requests in a way that helps maintainers review and respond more efficiently.

## Before You Jump In

Looking for something to work on? Start by browsing open issues and discussions.

If you are new to the project, smaller bug fixes, documentation improvements, issue reproduction, and testing feedback are great ways to get started.

Before starting a larger change, please open an issue or leave a comment on an existing issue first. This helps us confirm the direction and avoid duplicated work.

If your pull request is related to an issue, please link it in the PR description.

## What The Community Queue Accepts

Focused Community bug fixes, tests, documentation, examples, and translations
are the easiest changes to publish for contribution. New database plugins,
public or stored-data contracts, AI-provider branches, and release packaging
need maintainer approval before implementation.

Private security work and Chat2DB Local, Pro, Enterprise, Gateway, licensing,
billing, or private-service implementation are not accepted through the public
Community contribution queue. See the maintained
[contribution boundary map](.github/contribution-boundaries.yml) for the exact
rules and an alternative path for each restricted area.

## Finding And Claiming Work

The public [Chat2DB Community Project](https://github.com/orgs/OtterMind/projects/3)
shows triage, contributor-ready tasks, active work, review, and releases. Issues
remain the source of truth; the Project is the shared workflow view.

Tasks that are ready for an external contribution have one of these labels:

- `contribution/good-first-issue`: bounded work suitable for a first contribution.
- `contribution/help-wanted`: scoped work where maintainers welcome a contribution.

A published task includes a maintainer Ready Contract with the user outcome,
scope, non-goals, acceptance criteria, exact verification, dependencies, review
maintainer, response target, and release commitment. Do not start from an
unscoped Issue merely because it appears in a Milestone. Milestones communicate
delivery windows; the Project's Available Tasks and Good First Issues views are
the task shelves.

To claim an available task, comment `/claim` on its Issue. A successful claim
assigns the Issue to you and gives you seven days to open a linked draft or
regular pull request. Each contributor may hold one active claim at a time.

The claim bot also supports:

- `/claim status`: show the claimant, deadline, and linked pull request.
- `/renew`: extend an active pre-PR claim once.
- `/unclaim`: release your claim immediately.

Once a pull request is linked, the pre-PR deadline no longer expires while the
maintainers owe review. Use `Closes #123` in the pull request description so the
Issue, pull request, and Project stay connected. Claims without a linked pull
request are released automatically after the deadline so another contributor
can continue the task.

Maintainers target a substantive answer to questions on Ready Issues within
three business days, a first substantive pull request review within five
business days, and a follow-up review within three business days. When a target
cannot be met, the responsible maintainer posts the blocker and next review
date. The complete operating contract is in
[Community Operations](.github/COMMUNITY_OPERATIONS.md).

## Bug Reports

Please search existing issues before opening a new bug report. Someone may already have reported the same problem.

> [!IMPORTANT]
> Please include enough information for maintainers to reproduce and understand the problem.

- A clear and descriptive title
- Chat2DB version
- How are you using Chat2DB: desktop app, Docker, or local source build
- Operating system
- Database type and version
- Steps to reproduce the problem
- Expected behavior
- Actual behavior
- Logs, screenshots, or screen recordings if available

For database connection or SQL execution issues, it is also helpful to include:

- The database you are using
- The connection method, without passwords or private information
- A minimal SQL example, if it is safe to share

> [!CAUTION]
> Please remove passwords, tokens, private hostnames, customer data, and other sensitive information before posting logs or screenshots.

## Feature Requests

Please search existing issues and discussions before opening a new feature request.

> [!NOTE]
> Feature requests with clear use cases are easier for maintainers and the community to discuss.

- A clear and descriptive title
- The problem or workflow you want to improve
- The feature you would like to see
- Example use cases
- Screenshots, mockups, or references if useful

## Questions and Discussions

Please use GitHub Discussions for:

- Usage questions
- Setup help
- Ideas and open-ended feedback
- Community support
- General product discussions

Please use GitHub Issues for:

- Reproducible bugs
- Clear feature requests
- Documentation problems
- Technical tasks that can be worked on

This keeps Issues focused and easier to manage.

## Submitting Your Pull Request

We welcome focused pull requests.

> [!TIP]
> Smaller pull requests are easier to review and merge.

Before opening a pull request:

1. Fork the repository.
2. Create or comment on a related issue. If it is a published contribution task, claim it before starting.
3. Create a new branch for your work.
4. Keep your pull request focused on one topic.
5. Update documentation if your change affects user behavior or setup.
6. Add or update tests when practical.
7. Verify your change locally.
8. Link the related issue in your pull request description, if there is one.

You can link issues with:

```text
Fixes #123
```

or:

```text
Related to #123
```

## Pull Request Description

A good pull request description should include:

- What changed
- Why the change is needed
- Related issue link
- How you tested it
- Screenshots or screen recordings for UI changes
- Any known limitations or follow-up work

Please avoid mixing unrelated changes in one pull request.

## Local Setup

Please refer to the README for the latest setup instructions.

If you run into setup issues, please include the command you ran, the error output, and your local environment details when asking for help.

## Getting Help

If you get stuck while contributing, you can ask for help in:

- the related GitHub issue
- GitHub Discussions
- the Chat2DB community channels listed in the README

## Contributor Recognition

We appreciate every helpful contribution to Chat2DB, including code, documentation, testing, bug reports, issue reproduction, pull request reviews, and community support.

Thank you for helping improve Chat2DB.

## License

By contributing to Chat2DB, you agree that your contributions will be licensed
under the project's current [LICENSE](./LICENSE). You also agree that Chat2DB
may use your contributions for commercial purposes and may include them in
future releases made available under different license terms.

Please review the [LICENSE](./LICENSE) before submitting a contribution.
