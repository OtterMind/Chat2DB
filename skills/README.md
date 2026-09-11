# Built-in skills

Each directory is one runtime skill, with a SKILL.md entry and optional references, scripts, or assets. Add its name and relative file list to catalog.json. The start module packages these files under classpath skills/; the runtime prepares a content-versioned directory and loads each entry explicitly through Pi.

Skill names match their directory names. Keep workflow instructions in the skill and authoritative parameter validation in the tool. Chart-specific guidance is in chart/references/, one file per supported chart type.

Use /skill:chart followed by a request to select the chart skill explicitly. Natural-language chart requests can load it on demand through the available read tool (or Pi's bash fallback). Tool availability and host approvals still apply.
