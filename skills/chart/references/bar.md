# Bar

Horizontal ranking or long category labels.

## When to use

Use for one metric across categories, especially rankings or long category labels. Bar is horizontal; Column is vertical.

## Parameters

- xField is the category column and yField is the numeric metric even though the bars are horizontal.
- Omit series. These two field names must differ.

Use the exact resultId and column names from a suitable successful db_query result in this conversation. Follow the [shared result and pagination rules](common.md).

## Example

Synthetic request shape, not live data: replace this example resultId and field names with the actual returned values. The title follows the user's language.

```json
{
  "resultId": "r-example-bar-1",
  "chartType": "Bar",
  "xField": "class_name",
  "yField": "present_rate_pct",
  "title": "各班正常出勤率（%）"
}
```

## Data preparation and mistakes to avoid

- For rates, compute the agreed numerator and denominator in SQL. Keep rates numeric and put the unit in the title.
- Do not silently restrict a full ranking to Top N. Do not switch a requested Bar to Combo just to suppress UNEXPECTED_SERIES.

## Recovery: UNEXPECTED_SERIES

The user requested one horizontal bar series. A prior render_chart call used Bar plus a nonempty series array and returned UNEXPECTED_SERIES.

Expected: preserve the requested Bar, use the intended result's category and metric as xField/yField, and omit series, as in the Bar example above. Reuse the existing resultId when the query result is suitable. Do not re-run SQL or switch to Combo solely to suppress the validation error.

This is an anonymized version of an observed failure shape. The call above remains a synthetic example, not a claim that the model already passes this recovery case.

For other failures, consult [error recovery](errors.md). A successful render_chart call already displays and saves the chart; respond with the finding and any material scope limitation.
