# Column

Vertical category comparison.

## When to use

Use for comparing one numeric metric across discrete categories. Respect a user-specified category order; otherwise choose an order that answers the comparison.

## Parameters

- Use one category column as xField and one numeric metric column as yField.
- Omit series. These two field names must differ.
- Use groupBy for additional category dimensions. stack=false (the default) gives grouped columns; stack=true gives stacked columns. See the shared grouping rules.

Use the exact resultId and column names from a suitable successful db_query result in this conversation. Follow the [shared result and pagination rules](common.md).

## Example

Synthetic request shape, not live data: replace this example resultId and field names with the actual returned values. The title follows the user's language.

```json
{
  "resultId": "r-example-column-1",
  "chartType": "Column",
  "xField": "category",
  "yField": "amount",
  "title": "各类别金额"
}
```

## Data preparation and mistakes to avoid

- Aggregate to the requested category grain in SQL and use ORDER BY for a stable order.
- Use groupBy to compare groups of one metric. Use Combo when the task requires multiple numeric metric columns or mixed chart types.

For other failures, consult [error recovery](errors.md). A successful render_chart call already displays and saves the chart; respond with the finding and any material scope limitation.
