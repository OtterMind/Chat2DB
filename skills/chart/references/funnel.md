# Funnel

Values at defined process stages.

## When to use

Use for values at defined stages of a process. A funnel must have a meaningful stage interpretation; arbitrary categories do not establish conversion.

## Parameters

- Use the stage column as xField and the numeric stage metric as yField.
- Omit series. These two field names must differ.

Use the exact resultId and column names from a suitable successful db_query result in this conversation. Follow the [shared result and pagination rules](common.md).

## Example

Synthetic request shape, not live data: replace this example resultId and field names with the actual returned values. The title follows the user's language.

```json
{
  "resultId": "r-example-funnel-1",
  "chartType": "Funnel",
  "xField": "stage",
  "yField": "record_count",
  "title": "各阶段数量"
}
```

## Data preparation and mistakes to avoid

- Establish the cohort, stage definitions, and stage order in SQL. Do not sort only to manufacture a decreasing funnel.
- Explain conversion rates only when stages and denominators are comparable.
- The backend requires numeric values but has no dedicated Funnel nonnegative check; assess whether the chosen values make sense for the requested process.

For other failures, consult [error recovery](errors.md). A successful render_chart call already displays and saves the chart; respond with the finding and any material scope limitation.
