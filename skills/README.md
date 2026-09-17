# Skills in Pi Agent

Pi Agent includes `chart` and `skill-manager`. Use `/skill:skill-manager` or ask the agent to create, improve, or install a skill.

## Location

All skill resources use `~/.chat2db-skills/` by default:

```text
.chat2db-skills/
├── my-skill/
│   ├── SKILL.md
│   └── references/
└── .resources/
    └── <content-digest>/<skill-name>/
```

Put each custom skill folder directly in this directory. `SKILL.md` requires YAML frontmatter with a lowercase, hyphenated `name` and a nonempty `description`. Keep referenced files inside the skill folder. Names of bundled skills are reserved; use a new name for a customized copy.

The hidden `.resources` directory contains immutable copies of bundled and user skills. Multiple conversations share these copies. Edit the user source folder, never a snapshot. New resources are no longer written beneath the conversation history directory. Paths to unchanged bundled resources in older conversations are resolved to the shared location; existing history is not deleted.

The backend setting `chat2db.agent.v2.skills.directory` overrides the root, for example for an isolated development deployment. On a remote web deployment, this is a server directory. File tools receive the resolved absolute path from the host.

## Permissions and updates

Existing file tools can read and edit user skills in this fixed directory without enabling general workspace access. Files outside the skill directory retain the usual workspace permissions. Bundled resources and snapshots remain read-only.

Bash and PowerShell are omitted from the active tool set until the user enables the corresponding tool, and every command still requires approval. Setting their working directory does not confine shell commands to that directory. GitHub installation uses shell download/archive commands only when the user has enabled a shell tool; otherwise the agent asks for that setting and does not claim a download occurred. It does not require Python, and the agent reports missing commands or authentication rather than assuming they exist.

The backend validates sources and loads complete snapshots before the next turn. Running turns keep their selected versions. An invalid edit does not replace a valid snapshot already known by the running backend. Removing a user source removes it from future discovery, but does not delete historical snapshots or conversations.

Slash completion refreshes after a turn and when reopened. Saving files, loading a skill, and successfully exercising its behavior are separate outcomes. A custom skill's own scripts can still have additional dependencies.

## Maintaining bundled skills

Each bundled skill has a directory matching its name. Add its name and complete relative file list to `catalog.json`; the start module packages the resources under classpath `skills/`. Keep workflow guidance in the skill and authoritative validation in the host tools. Chart guidance remains in `chart/references/`, with one reference per chart type; `/skill:chart` selects it explicitly.
