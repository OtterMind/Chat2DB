# Pie

Parts of a meaningful whole.

## When to use

Use for nonnegative contributions to one meaningful whole. Category amounts must represent compatible units and a defensible denominator.

## Parameters

- xField is the category column; yField is the nonnegative numeric contribution.
- Omit series. These two field names must differ.

Use the exact resultId and column names from a suitable successful db_query result in this conversation. Follow the [shared result and pagination rules](common.md).

## Example

Synthetic request shape, not live data: replace this example resultId and field names with the actual returned values. The title follows the user's language.

```json
{
  "resultId": "r-example-pie-1",
  "chartType": "Pie",
  "xField": "category",
  "yField": "amount",
  "title": "各类别金额占比"
}
```

## Data preparation and mistakes to avoid

- Negative values are rejected by the backend. A nonzero meaningful total is also necessary to interpret proportions.
- Do not silently take absolute values, discard negative rows, or mix unrelated denominators.
- Aggregate categories in SQL. Do not invent an Other group unless it preserves an agreed grouping.

For other failures, consult [error recovery](errors.md). A successful render_chart call already displays and saves the chart; respond with the finding and any material scope limitation.
