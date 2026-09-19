# Error-directed recovery

Read this file when a query result or render_chart call fails. Use the selected chart type's reference for the corrected request shape.

| Error | Useful next action |
| --- | --- |
| UNEXPECTED_SERIES | Keep the intended non-Combo type; remove series and use xField/yField. |
| MISSING_SERIES | For an intended Combo, supply valid series entries. |
| UNSUPPORTED_GROUPING | Use groupBy only with Column, Bar, Line, AreaLine, Scatter or Combo. Keep the user's chart intent when choosing a supported shape. |
| UNSUPPORTED_STACK | stack=true requires Column, Bar, AreaLine, or a Combo containing Column/AreaLine. Do not stack unrelated units or non-additive metrics. |
| DUPLICATE_CATEGORY | Aggregate in SQL to one row per xField and groupBy tuple. The renderer will not choose an aggregation for you. |
| TOO_MANY_SERIES | The group/metric combinations exceed 32. Agree on scope or split the visualization; do not silently discard groups. |
| MISSING_FIELD / FIELD_NOT_FOUND | Inspect the selected result's columns and the chart's required fields. |
| AMBIGUOUS_FIELD / DUPLICATE_FIELD | Use distinct SQL aliases or distinct selected fields; re-query only when the data shape must change. |
| EXPECTED_SINGLE_ROW | Aggregate the requested metric to one row for Statistics. |
| NON_NUMERIC_FIELD / NO_NUMERIC_VALUES | Inspect values, SQL types, and NULLs; compute the correct numeric expression if possible. |
| NEGATIVE_PIE_VALUE | Explain why signed values do not form the requested parts-of-whole chart; choose a suitable alternative when the user has not fixed the type. |
| NUMERIC_PRECISION | Explicitly scale or round in SQL to appropriate units; disclose the interpretation change. |
| NO_DATA / INCOMPLETE_VALUES | Check scope and data quality; do not fabricate rows or zeros. |
| RESULT_NOT_FOUND | Find a suitable result in this conversation or perform the necessary read-only query. |
| RUN_CANCELLED | Stop; do not create another chart or restart the query. |

Correct the cause named in the error. Changing a title while repeating the same invalid Bar + series combination is not a correction. A renderer or transport failure is not proof of success; inspect available result/event evidence before retrying.
