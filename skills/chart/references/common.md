# Shared chart rules

Every render_chart call requires resultId and chartType. title is optional and should describe the metric and unit in the user's language. Omit unused fields rather than filling them with empty strings or empty arrays. Follow the current tool schema and returned errors if the implementation changes.

Read the reference for the selected chart type from the index in [SKILL.md](../SKILL.md). Type-specific parameters and examples live in those individual files.

## Result selection and data quality

- db_query returns per-statement outcomes in data.results. Choose the intended result whose success is true and whose row data includes a resultId. A resultId is not a datasource ID, session ID, or run ID.
- Results are scoped to the current conversation and user. A result from another run in the same conversation may be reused when it still answers the request; another conversation's result must not be reused.
- Rows align with the returned column order. Resolve duplicate column names with distinct SQL aliases. Field names are case-sensitive exact matches.
- Numeric strings must parse as numbers. Format percentages as numeric SQL expressions, not strings containing a percent sign. Put units in the title or meaningful column aliases.
- SQL NULL remains missing. Do not convert it to zero without a justified business meaning. Every selected numeric field must contain at least one non-NULL value.
- Values that cannot be represented accurately by the chart require explicit scaling or rounding in SQL. Explain any precision change that affects interpretation.
- An empty result or shortened/unavailable cells cannot produce a chart. Query complete values or report the limitation.

## Pagination

A chart plots one saved query page. Check both the chosen statement's page and response warnings. page.number > 1 is a partial slice even when hasMore is false. hasMore=true, unknown completeness, or other warnings must not be described as the full population.

The current db_query defaults are page=1 and pageSize=50, with pageSize at most 200. Prefer SQL aggregation at the grain required by the question. Each query page reruns the SQL and gets its own resultId; render_chart cannot merge resultIds or accept a hand-built data array. Do not silently introduce Top N, a different denominator, or a narrower date range to fit a page.

