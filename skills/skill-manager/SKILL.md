---
name: skill-manager
description: Create, improve, inspect, or install reusable skills when the user asks to save a workflow, edit a skill, or install one from a local folder, archive, or GitHub repository.
---

# Skill Manager

Use the existing file and shell tools to maintain standard skills. The user skill directory is given in the file tools' descriptions; its default is `~/.chat2db-skills/`. Use the actual absolute path supplied by the host. Do not change the conversation working directory to access it.

- For creating or editing a skill, read [creation guidance](references/creation.md).
- For importing a local skill or installing from GitHub, read [installation guidance](references/installation.md).
- To inspect installed user skills, use `ls`, `find`, `read`, or `grep` in the user skill directory. Hidden runtime resources are read-only and are not user installation destinations.

Keep each user skill in its own named folder with `SKILL.md` and the resources it actually needs. User skill files can be read and edited without enabling general workspace file access. Bash/PowerShell remain separate capabilities and require the normal command approval. Do not substitute shell execution to bypass denied file access.

A user request to create, edit, or install a skill already supplies that intent. Ask only for information that materially affects the result and cannot be inferred. Do not silently overwrite an unrelated skill or modify a bundled skill. Offer a differently named user copy when adapting a bundled skill.

The host validates and loads changed skills before the next conversation turn. Finish referenced files before writing the new entrypoint. Re-read the result and report its name and intended use. Distinguish files saved from resources loaded and behavior tested; writing a file alone does not prove the skill has run successfully. Explicit invocation uses `/skill:<name>`.
