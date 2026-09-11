# Combo

Multiple metrics on a shared category/time axis.

## When to use

Use when the task requires multiple numeric metrics over one shared category or time axis. Match axes to units and make the comparison interpretable.

## Parameters

- Use xField and 1–8 series entries; omit the unused yField.
- Each series requires field, chartType, and axisPosition.
- Series chartType must be Column, Line, AreaLine, or Scatter. axisPosition must be left or right.
- Every series field must be numeric and differ from xField and all other series fields.

Use the exact resultId and column names from a suitable successful db_query result in this conversation. Follow the [shared result and pagination rules](common.md).

## Example

Synthetic request shape, not live data: replace this example resultId and field names with the actual returned values. The title follows the user's language.

```json
{
  "resultId": "r-example-combo-1",
  "chartType": "Combo",
  "xField": "class_name",
  "title": "各班正常出勤率与含迟到到课率（%）",
  "series": [
    {
      "field": "present_rate_pct",
      "chartType": "Column",
      "axisPosition": "left"
    },
    {
      "field": "including_late_rate_pct",
      "chartType": "Line",
      "axisPosition": "left"
    }
  ]
}
```

## Data preparation and mistakes to avoid

- Use the same axis for comparable units. Use two axes only when units require it and explain their meaning.
- Do not add label, unit, color, data, aggregation, or a secondary xField.
- The backend currently tolerates an unused yField for Combo; omitting it is the canonical request shape, not a claim that the backend rejects it.
- A single metric does not require Combo.

For other failures, consult [error recovery](errors.md). A successful render_chart call already displays and saves the chart; respond with the finding and any material scope limitation.
