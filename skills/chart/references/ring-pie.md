# RingPie

Parts of a whole in a ring layout.

## When to use

Use for parts of one meaningful whole when a ring/donut layout is appropriate or requested. The data requirements are the same as Pie.

## Parameters

- xField is the category column; yField is a nonnegative numeric contribution.
- Omit series. These two field names must differ.

Use the exact resultId and column names from a suitable successful db_query result in this conversation. Follow the [shared result and pagination rules](common.md).

## Example

Synthetic request shape, not live data: replace this example resultId and field names with the actual returned values. The title follows the user's language.

```json
{
  "resultId": "r-example-ring-pie-1",
  "chartType": "RingPie",
  "xField": "channel",
  "yField": "order_count",
  "title": "各渠道订单占比"
}
```

## Data preparation and mistakes to avoid

- Require meaningful parts and a nonzero total; never repair signed values by silently taking absolute values.
- Use chartType RingPie exactly. Do not send donut, innerRadius, or a separate total parameter.

For other failures, consult [error recovery](errors.md). A successful render_chart call already displays and saves the chart; respond with the finding and any material scope limitation.
