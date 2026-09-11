# Shared workflow examples

These synthetic examples describe decision boundaries, not live database facts. They do not authorize extra execution or data changes. Each chart type's parameter example is in its own reference, linked from [SKILL.md](../SKILL.md).

## 1. Negative: SQL-only request

User request: only write SQL to compare monthly revenue; do not run it.

Expected: provide dialect-appropriate SQL from available schema evidence. Do not call db_query or render_chart for the chart task. If essential schema information is unavailable, explain what is needed.

Learn: the request's execution boundary overrides an apparent visualization topic. Do not learn: that merely mentioning a chart authorizes running a query.

## 2. Boundary: two plausible rate definitions

User request: show attendance rate. Inspected records distinguish present, late, absent, and leave; no established definition resolves whether late/leave count.

Expected: inspect existing definitions if available. If the metric remains ambiguous, ask one focused question via askUserQuestion about the numerator/denominator before calculating the rate. Do not ask the user for database objects already discoverable with tools.

Learn: resolve a material business definition, not an incidental technical detail. Do not learn a universal attendance formula from this example.

## 3. Boundary: a page is not the full dataset

User request: chart every category. The successful result reports hasMore=true.

Expected: inspect the result grain and choose SQL aggregation that preserves the question. If all categories still cannot be represented in one result page, explain the limitation and ask for an acceptable scope or representation. Do not describe the first page as all categories; do not silently choose Top N.

Learn: result completeness is part of correctness. Do not learn that every chart must fetch all detail rows.

## 4. Negative: empty result

Observed result: zero rows for the requested scope.

Expected: check an evident scope/filter error if one exists; otherwise state that no data was returned and no chart was generated. Do not add zero-valued rows to create a visual.

Learn: absence of records does not establish a measured value of zero.
