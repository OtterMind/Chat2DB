# AreaLine

Magnitude over an ordered axis.

## When to use

Use when the magnitude of a metric over an ordered axis is the intended emphasis. Use the same time-grain and ordering discipline as a line chart.

## Parameters

- Use the ordered time/category column as xField and the numeric metric as yField.
- Omit series. These two field names must differ.
- Use groupBy to draw separate areas by category tuple. Set stack=true only when the grouped metric values form an additive total.

Use the exact resultId and column names from a suitable successful db_query result in this conversation. Follow the [shared result and pagination rules](common.md).

## Example

Synthetic request shape, not live data: replace this example resultId and field names with the actual returned values. The title follows the user's language.

```json
{
  "resultId": "r-example-area-line-1",
  "chartType": "AreaLine",
  "xField": "day",
  "yField": "request_count",
  "title": "每日请求量"
}
```

## Data preparation and mistakes to avoid

- Compute aggregation and chronological order in SQL. Do not invent values to fill gaps.
- Use the declared stack boolean for stacked areas; do not send arbitrary stacking options, colors, or data arrays.
- If signed values make the filled-area interpretation misleading, explain or choose a more suitable type when the user has not fixed it.

For other failures, consult [error recovery](errors.md). A successful render_chart call already displays and saves the chart; respond with the finding and any material scope limitation.
