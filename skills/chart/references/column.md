# Column

Vertical category comparison.

## When to use

Use for comparing one numeric metric across discrete categories. Respect a user-specified category order; otherwise choose an order that answers the comparison.

## Parameters

- Use one category column as xField and one numeric metric column as yField.
- Omit series. These two field names must differ.

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
- Do not treat Column as a stacked or grouped multi-series API. Use Combo when multiple metrics and the user's intent call for it.

For other failures, consult [error recovery](errors.md). A successful render_chart call already displays and saves the chart; respond with the finding and any material scope limitation.
