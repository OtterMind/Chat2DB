# Statistics

One aggregate metric.

## When to use

Use for a single numeric metric card. Obtain the intended aggregate in SQL before rendering.

## Parameters

- Use yField from a result containing exactly one row.
- Omit xField and series.

Use the exact resultId and column names from a suitable successful db_query result in this conversation. Follow the [shared result and pagination rules](common.md).

## Example

Synthetic request shape, not live data: replace this example resultId and field names with the actual returned values. The title follows the user's language.

```json
{
  "resultId": "r-example-statistics-1",
  "chartType": "Statistics",
  "yField": "total_orders",
  "title": "订单总数"
}
```

## Data preparation and mistakes to avoid

- Do not take an arbitrary row from a multi-row query to satisfy the one-row condition.
- SQL NULL does not mean zero. An empty or all-NULL metric must be resolved or reported.
- For a rate, aggregate the underlying counts correctly before calculating it; do not average percentages with different denominators.

For other failures, consult [error recovery](errors.md). A successful render_chart call already displays and saves the chart; respond with the finding and any material scope limitation.
