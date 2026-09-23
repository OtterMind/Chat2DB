# RosePie

Category magnitude in a polar layout.

## When to use

Use when a rose/polar category-magnitude chart is appropriate or explicitly requested. Do not choose it solely because it looks decorative when precise comparison is the task.

## Parameters

- xField is the category column; yField is a nonnegative numeric contribution.
- Omit series. These two field names must differ.

Use the exact resultId and column names from a suitable successful db_query result in this conversation. Follow the [shared result and pagination rules](common.md).

## Example

Synthetic request shape, not live data: replace this example resultId and field names with the actual returned values. The title follows the user's language.

```json
{
  "resultId": "r-example-rose-pie-1",
  "chartType": "RosePie",
  "xField": "category",
  "yField": "amount",
  "title": "各类别金额玫瑰图"
}
```

## Data preparation and mistakes to avoid

- The current tool rejects negative values for RosePie. Use a meaningful nonzero total when describing shares.
- Do not add roseType, radius, or other chart-library options; the tool owns rendering.
- Do not equate visual area differences with a verified business conclusion.

For other failures, consult [error recovery](errors.md). A successful render_chart call already displays and saves the chart; respond with the finding and any material scope limitation.
