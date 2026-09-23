---
name: skill-manager
description: Create, improve, inspect, or install reusable skills when the user asks to save a workflow, edit a skill, or install one from a local folder, archive, or GitHub repository.
---

# Skill Manager

Use the existing file tools to maintain standard skills. The user skill directory is given in the file tools' descriptions; its default is `~/.chat2db-skills/`. Use the actual absolute path supplied by the host. Do not change the conversation working directory to access it.

Bash/PowerShell is not part of the default skill-directory access. If an installation needs a remote download or archive command and the shell tool is not available, tell the user which shell tool must be enabled in Pi Agent settings and wait for that change. Enabling it only makes the command available; every shell command still requires the normal approval. Keep downloads in a temporary location, inspect them, and finish the installation with the file tools.

- For creating or editing a skill, read [creation guidance](references/creation.md).
- For importing a local skill or installing from GitHub, read [installation guidance](references/installation.md).
- To inspect installed user skills, use `ls`, `find`, `read`, or `grep` in the user skill directory. Bundled skills live outside it and are read-only.

Keep each user skill in its own named folder with `SKILL.md` and the resources it actually needs. User skill files can be read and edited without enabling general workspace file access. Bash/PowerShell remain separate capabilities and require the normal command approval. Do not substitute shell execution to bypass denied file access.

A user request to create, edit, or install a skill already supplies that intent. Ask only for information that materially affects the result and cannot be inferred. When the name is already taken and the content differs, ask the user whether to overwrite it or use a new name; never overwrite silently and never modify a bundled skill.

The host validates and loads changed skills before the next conversation turn. Finish referenced files before writing the new entrypoint. Re-read the result and report its name and intended use. Distinguish files saved from resources loaded and behavior tested; writing a file alone does not prove the skill has run successfully. Explicit invocation uses `/skill:<name>`.
