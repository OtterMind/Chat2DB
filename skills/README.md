# Skills in Pi Agent

Pi Agent includes `chart` and `skill-manager`. Use `/skill:skill-manager` or ask the agent to create, improve, or install a skill.

## Location

User skills live in `~/.chat2db-skills/` by default:

```text
.chat2db-skills/
└── my-skill/
    ├── SKILL.md
    └── references/
```

Put each custom skill folder directly in this directory. `SKILL.md` requires YAML frontmatter with a lowercase, hyphenated `name` and a nonempty `description`. Keep referenced files inside the skill folder. Names of bundled skills are reserved; use a new name for a customized copy.

The user directory is the running resource: no copy is made, and an edit applies to the next turn. Bundled skills are installed outside it, under the product data directory at `storage/agent-v2/skills/builtin/<name>/`, and are replaced in place whenever the packaged content changes. Paths recorded by older conversations that used per-content snapshots are resolved to the skill loaded now.

The backend setting `chat2db.agent.v2.skills.directory` overrides the root, for example for an isolated development deployment. On a remote web deployment, this is a server directory. File tools receive the resolved absolute path from the host.

## Permissions and updates

Existing file tools can read and edit user skills in this fixed directory without enabling general workspace access. Files outside the skill directory retain the usual workspace permissions. Bundled skills remain read-only.

Bash and PowerShell are omitted from the active tool set until the user enables the corresponding tool, and every command still requires approval. Setting their working directory does not confine shell commands to that directory. GitHub installation uses shell download/archive commands only when the user has enabled a shell tool; otherwise the agent asks for that setting and does not claim a download occurred. It does not require Python, and the agent reports missing commands or authentication rather than assuming they exist.

The backend validates sources and loads the current version before the next turn. A running turn keeps the version it selected, while the files it reads are the live ones. An invalid source is reported and not offered; other skills keep working. Removing a user source removes it from future discovery.

Slash completion refreshes after a turn and when reopened. Saving files, loading a skill, and successfully exercising its behavior are separate outcomes. A custom skill's own scripts can still have additional dependencies.

## Maintaining bundled skills

Each bundled skill has a directory matching its name. Add its name and complete relative file list to `catalog.json`; the start module packages the resources under classpath `skills/`. Keep workflow guidance in the skill and authoritative validation in the host tools. Chart guidance remains in `chart/references/`, with one reference per chart type; `/skill:chart` selects it explicitly.
