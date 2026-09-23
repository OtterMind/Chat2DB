# Shared chart rules

Every render_chart call requires resultId and chartType. title is optional and should describe the metric and unit in the user's language. Omit unused fields rather than filling them with empty strings or empty arrays. Follow the current tool schema and returned errors if the implementation changes.

Read the reference for the selected chart type from the index in [SKILL.md](../SKILL.md). Type-specific parameters and examples live in those individual files.

## Multiple dimensions and stacking

- Column, Bar, Line, AreaLine, Scatter and Combo accept groupBy: up to three distinct category columns, separate from xField and all numeric metric fields. Each distinct tuple of group values becomes a series. For example, month / region / revenue uses xField=month, yField=revenue, groupBy=["region"]. month / region / provider / revenue uses groupBy=["region", "provider"].
- Keep results in long form; SQL does not need to pivot region values into separate columns. For category charts, aggregate to one row per xField and groupBy tuple and ORDER BY the intended axis order. Duplicate tuples produce DUPLICATE_CATEGORY; do not silently add, overwrite or drop rows in the renderer.
- Scatter keeps numeric x/y observations, including repeated x values, within each group.
- stack=true is supported by Column, Bar and AreaLine, and by the Column/AreaLine series in Combo. Omit stack or use false for side-by-side bars and separate lines. Line, Scatter and non-Cartesian charts do not support stacking.
- For grouped Combo, each metric is split by group. Only the groups of the same metric, axis and compatible chart type are stacked together; different metrics remain separate. Without groupBy, Combo stacks compatible series on the same axis. Use this only when adding the metrics is meaningful.
- Combo keeps its existing series array for numeric metrics, up to eight. Other chart types still use yField and omit series. groupBy splits categories; series selects metrics. The generated chart supports at most 32 series. If more are needed, agree on a useful scope or split the visualization instead of silently dropping groups.
- Missing category/group combinations and SQL NULL metrics stay missing, not zero. SQL NULL, an empty string and the text "NULL" are distinct group values. Do not use COALESCE merely to make a chart look complete.
- Pie, RingPie, RosePie, Funnel and Statistics keep their single-dimension parameter shapes. Facets, drill-down, bubble size, arbitrary ECharts options and supplied data arrays are not exposed by this tool.

Synthetic example (replace resultId and column names with the actual saved query):

```json
{
  "resultId": "r-example-grouped-1",
  "chartType": "Column",
  "xField": "month",
  "yField": "revenue",
  "groupBy": ["region"],
  "stack": true,
  "title": "各大区月收入构成"
}
```

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
