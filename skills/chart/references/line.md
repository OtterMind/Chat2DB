# Line

Trend across an ordered axis.

## When to use

Use for a metric over time or another meaningful ordered axis. A line implies an ordered progression; do not use an arbitrary row order as the sequence.

## Parameters

- Use the ordered time/category column as xField and the numeric metric as yField.
- Omit series. These two field names must differ.

Use the exact resultId and column names from a suitable successful db_query result in this conversation. Follow the [shared result and pagination rules](common.md).

## Example

Synthetic request shape, not live data: replace this example resultId and field names with the actual returned values. The title follows the user's language.

```json
{
  "resultId": "r-example-line-1",
  "chartType": "Line",
  "xField": "month",
  "yField": "revenue",
  "title": "月收入趋势"
}
```

## Data preparation and mistakes to avoid

- Group at the requested time grain and sort chronologically in SQL. Avoid lexicographic month sorting that changes the timeline.
- Missing records are not measured zeros. Fill gaps only when the metric definition justifies that choice.
- Do not add unsupported smoothing or styling parameters.

For other failures, consult [error recovery](errors.md). A successful render_chart call already displays and saves the chart; respond with the finding and any material scope limitation.
