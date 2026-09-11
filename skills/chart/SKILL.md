---
name: chart
description: Create or revise database charts in Chat2DB using db_query results and render_chart. Use when the user asks to visualize database data, compare metrics in a chart, plot a trend or distribution, or change an existing database chart. Do not create a chart when the user asks only for SQL, a table, or a textual explanation.
---

# Chart

## Context

Use the user's request, the current Chat2DB context, inspected schemas, and actual query results. Tool definitions are the live API contract. This skill grants no additional permissions.

## Workflow

1. Identify the metric, grouping, time range, and intended chart. Resolve discoverable details with the available database tools. Ask only when an unresolved choice materially changes the answer.
2. Inspect unknown columns and choose the database's SQL dialect. Compute aggregation, ratios, ordering, and any agreed rounding in SQL. For rates, establish the denominator; do not average percentages with different denominators.
3. Obtain data with db_query, or reuse a suitable result already returned in this conversation. Re-query when the user requests fresh data or changes its scope. Use the exact resultId of the intended successful statement; inspect its columns, rows, page, and warnings.
4. Read the [shared result rules](references/common.md) once, then read only the selected chart type's file below before calling render_chart. If the selected type changes, read its file. Read [error recovery](references/errors.md) only when needed.
5. Call render_chart with the selected resultId and only the fields needed for that chart type. A successful response means the chart is already displayed and saved.

## Choose a chart reference

| chartType | Use | Reference |
| --- | --- | --- |
| Column | Vertical category comparison | [Column](references/column.md) |
| Bar | Horizontal ranking or long category labels | [Bar](references/bar.md) |
| Line | Trend across an ordered axis | [Line](references/line.md) |
| AreaLine | Magnitude over an ordered axis | [AreaLine](references/area-line.md) |
| Pie | Parts of a meaningful whole | [Pie](references/pie.md) |
| RingPie | Parts of a whole in a ring layout | [RingPie](references/ring-pie.md) |
| RosePie | Category magnitude in a polar layout | [RosePie](references/rose-pie.md) |
| Funnel | Values at defined process stages | [Funnel](references/funnel.md) |
| Scatter | Relationship between two numeric variables | [Scatter](references/scatter.md) |
| Statistics | One aggregate metric | [Statistics](references/statistics.md) |
| Combo | Multiple metrics on a shared category/time axis | [Combo](references/combo.md) |

Each file owns that type's parameter shape, data requirements, example, and common mistakes. Do not load every chart file. Copy field names exactly from the query result; never invent resultIds or data. Table is not a render_chart chartType; the chart UI already provides a table view.

For SQL-only, ambiguous metric, partial-page, and empty-result decisions, see [shared workflow examples](references/examples.md).

## Output

Respond in the user's language. State the finding, measurement scope, units, and any material limitation briefly. Do not repeat a chart code block or copy the full result table after rendering. If no chart was created, say so and explain the next useful step.

## Constraints and recovery

- For a SQL-only request, do not call db_query or render_chart. A table-only request does not need render_chart.
- Use read-only queries for the chart task; do not modify data to prepare a chart. Respect host approval and cancellation.
- Empty, incomplete, or all-NULL data is not a zero-valued result. Resolve the cause or report the limitation.
- A chart uses one saved result page. For an all-data question, aggregate appropriately in SQL or obtain the user's choice of scope; do not silently replace the request with a sample or Top N.
- On a specific parameter error, correct that parameter using the tool error and the reference. Do not repeat the same invalid parameter combination or change the requested chart merely to evade validation.
- If a required tool is unavailable, the result cannot be recovered, or the metric remains ambiguous, state what is missing. Never claim a chart exists without a successful render_chart result.
