# Scatter

Relationship between two numeric variables.

## When to use

Use to inspect the relationship between two numeric variables at a common observation grain. A pattern does not establish causation.

## Parameters

- Both xField and yField must be numeric columns and must differ.
- Omit series.

Use the exact resultId and column names from a suitable successful db_query result in this conversation. Follow the [shared result and pagination rules](common.md).

## Example

Synthetic request shape, not live data: replace this example resultId and field names with the actual returned values. The title follows the user's language.

```json
{
  "resultId": "r-example-scatter-1",
  "chartType": "Scatter",
  "xField": "order_count",
  "yField": "revenue",
  "title": "客户订单数与收入"
}
```

## Data preparation and mistakes to avoid

- Keep the observation grain consistent, for example one row per customer. Do not pair independently sorted columns.
- A text category cannot serve as the numeric X variable. Correct the SQL expression or explain the limitation.
- Do not add sizeField, bubble size, color groups, or a data array; they are not part of this tool contract.

For other failures, consult [error recovery](errors.md). A successful render_chart call already displays and saves the chart; respond with the finding and any material scope limitation.
