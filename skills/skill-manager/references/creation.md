# Create or improve a skill

Adapted from OpenAI's skill-creator. Preserve user intent and scope: a reusable skill captures the requested capability without broadening permissions, adding unrelated configuration, or turning a one-off example into a universal requirement.

Assume the agent is already capable. Include guidance that changes useful decisions: relevant context, non-obvious steps, actual tool contracts, and verification. Remove repeated generic advice. Match specificity to the task; reserve rigid procedures for fragile operations.

## Structure and discovery

Every skill needs `SKILL.md` with YAML frontmatter containing `name` and `description`.

- Names contain lowercase letters, digits and single hyphens, with no leading/trailing hyphen, and at most 64 characters. Name the source folder accordingly.
- Describe the capability and when to use it, within 1024 characters. Keep automatic selection enabled unless the user requests explicit-only use. Pi supports `disable-model-invocation: true` for explicit-only skills.
- Keep the entrypoint concise. Put substantial task-specific detail in `references/`, reusable output files in `assets/`, and scripts in `scripts/` only when their concrete benefit warrants them. Link references where they are needed.
- The standard file structure is sufficient. Do not generate Codex-only `agents/openai.yaml` or require Python initialization/validation scripts.

## Work on actual files

Inspect the existing skill and understand what the user wants to reuse. Create or update files using `write` and `edit` inside the user skill directory. Before updating, read current contents so an edit does not overwrite unrelated or concurrent changes. Preserve useful resources and supported metadata.

Do not copy credentials, private query results, session identifiers, or unrelated personal information out of the conversation. Prefer inputs or configuration references for values that vary between uses.

Check that the description selects the intended requests, instructions preserve user intent, links resolve within the skill, and placeholders have been replaced. Use the file tools to inspect the resulting files. Do not invent a validation tool or run a non-existent helper.

If a skill needs executable scripts, state its actual environment dependencies and test them only within the user's authorized scope. Installation does not grant shell, network or database permissions. Improve the instructions from observed behavior, without adding a rule for every hypothetical failure.
