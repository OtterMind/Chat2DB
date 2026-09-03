export type ExplainPlanMode = 'json' | 'analyze';

export interface ExplainPlanMetric {
  label: string;
  value: string;
}

export interface ExplainPlanNode {
  key: string;
  title: string;
  sourcePath: string;
  metrics: ExplainPlanMetric[];
  children: ExplainPlanNode[];
}

export interface ParsedExplainPlan {
  nodes: ExplainPlanNode[];
  rawText: string;
  formattedRawText: string;
  parseError?: string;
}

const EMPTY_VALUE = '-';

export function parseExplainPlan(mode: ExplainPlanMode, rawPlan?: string | null): ParsedExplainPlan {
  const rawText = rawPlan || '';
  if (mode === 'analyze') {
    return parseAnalyzePlan(rawText);
  }
  return parseJsonPlan(rawText);
}

export function displayMetric(value: unknown): string {
  if (value === null || value === undefined || value === '') {
    return EMPTY_VALUE;
  }
  if (Array.isArray(value)) {
    return value.length ? value.join(', ') : EMPTY_VALUE;
  }
  if (typeof value === 'object') {
    return JSON.stringify(value);
  }
  return String(value);
}

function parseJsonPlan(rawText: string): ParsedExplainPlan {
  if (!rawText.trim()) {
    return { nodes: [], rawText, formattedRawText: rawText, parseError: 'empty' };
  }

  try {
    const json = JSON.parse(rawText);
    return {
      nodes: collectJsonNodes(json, '$'),
      rawText,
      formattedRawText: JSON.stringify(json, null, 2),
    };
  } catch (error: any) {
    return {
      nodes: [unknownNode('$', rawText)],
      rawText,
      formattedRawText: rawText,
      parseError: error?.message || 'invalid JSON',
    };
  }
}

function collectJsonNodes(value: unknown, path: string): ExplainPlanNode[] {
  if (!isRecord(value)) {
    return [];
  }

  if (isRecord(value.table)) {
    return [tableNode(value.table, `${path}.table`)];
  }

  if (isRecord(value.query_block)) {
    return [queryBlockNode(value.query_block, `${path}.query_block`)];
  }

  const children = collectJsonChildren(value, path);
  return [genericJsonNode(value, path, children)];
}

function queryBlockNode(block: Record<string, unknown>, path: string): ExplainPlanNode {
  const children = collectJsonChildren(block, path);
  return {
    key: path,
    title: `Query block ${displayMetric(block.select_id)}`,
    sourcePath: path,
    metrics: compactMetrics([
      ['Type', 'query_block'],
      ['Cost', readNested(block, 'cost_info', 'query_cost')],
      ['Estimated rows', block.rows_examined_per_scan || block.rows_produced_per_join || block.rows],
    ]),
    children,
  };
}

function tableNode(table: Record<string, unknown>, path: string): ExplainPlanNode {
  const children = collectJsonChildren(table, path);
  return {
    key: path,
    title: `${displayMetric(table.table_name || table.message || table.access_type || 'Table')}`,
    sourcePath: path,
    metrics: compactMetrics([
      ['Node', table.table_name || table.message || 'table'],
      ['Access', table.access_type],
      ['Object', table.table_name],
      ['Index', table.key || table.possible_keys],
      ['Cost', readNested(table, 'cost_info', 'prefix_cost') || readNested(table, 'cost_info', 'query_cost')],
      ['Estimated rows', table.rows_examined_per_scan || table.rows_produced_per_join || table.rows],
      ['Filtered', table.filtered],
      ['Condition', table.attached_condition || table.using_where],
    ]),
    children,
  };
}

function genericJsonNode(value: Record<string, unknown>, path: string, children: ExplainPlanNode[]): ExplainPlanNode {
  const title =
    displayMetric(value.operation || value.message || value.select_id || value.table_name || value.access_type) ||
    'Unknown plan node';
  return {
    key: path,
    title: title === EMPTY_VALUE ? 'Unknown plan node' : title,
    sourcePath: path,
    metrics: compactMetrics([
      ['Node', value.operation || value.message || value.table_name || value.access_type || 'unknown'],
      ['Cost', readNested(value, 'cost_info', 'query_cost') || readNested(value, 'cost_info', 'prefix_cost')],
      ['Estimated rows', value.rows_examined_per_scan || value.rows_produced_per_join || value.rows],
    ]),
    children,
  };
}

function collectJsonChildren(value: Record<string, unknown>, path: string): ExplainPlanNode[] {
  const children: ExplainPlanNode[] = [];
  Object.entries(value).forEach(([key, child]) => {
    const childPath = `${path}.${key}`;
    if (key === 'cost_info') {
      return;
    }
    if (Array.isArray(child)) {
      child.forEach((item, index) => children.push(...collectJsonNodes(item, `${childPath}[${index}]`)));
      return;
    }
    if (isRecord(child)) {
      children.push(...collectJsonNodes(child, childPath));
    }
  });
  return children;
}

function parseAnalyzePlan(rawText: string): ParsedExplainPlan {
  const roots: ExplainPlanNode[] = [];
  const stack: Array<{ indent: number; node: ExplainPlanNode }> = [];

  rawText.split(/\r?\n/).forEach((line, index) => {
    if (!line.trim()) {
      return;
    }
    const arrowIndex = line.indexOf('->');
    const indent = arrowIndex >= 0 ? arrowIndex : line.search(/\S/);
    const text = (arrowIndex >= 0 ? line.slice(arrowIndex + 2) : line).trim();
    const node = analyzeNode(text, `line:${index + 1}`);

    while (stack.length && indent <= stack[stack.length - 1].indent) {
      stack.pop();
    }

    const parent = stack[stack.length - 1]?.node;
    if (parent) {
      parent.children.push(node);
    } else {
      roots.push(node);
    }
    stack.push({ indent, node });
  });

  return { nodes: roots, rawText, formattedRawText: rawText };
}

function analyzeNode(text: string, sourcePath: string): ExplainPlanNode {
  const actual = text.match(/actual time=([\d.]+)\.\.([\d.]+)\s+rows=([\d.]+)\s+loops=([\d.]+)/i);
  const estimated = text.match(/cost=([\d.]+)(?:\.\.([\d.]+))?\s+rows=([\d.]+)/i);
  const title = text.split(/\s+\(/)[0] || 'Unknown plan node';
  return {
    key: sourcePath,
    title,
    sourcePath,
    metrics: compactMetrics([
      ['Node', title],
      ['Cost', estimated ? estimated[2] || estimated[1] : undefined],
      ['Estimated rows', estimated?.[3]],
      ['Actual rows', actual?.[3]],
      ['Loops', actual?.[4]],
      ['First row time', actual?.[1]],
      ['Total time', actual?.[2]],
    ]),
    children: [],
  };
}

function compactMetrics(entries: Array<[string, unknown]>): ExplainPlanMetric[] {
  return entries.map(([label, value]) => ({ label, value: displayMetric(value) }));
}

function readNested(value: Record<string, unknown>, objectKey: string, nestedKey: string): unknown {
  const nested = value[objectKey];
  return isRecord(nested) ? nested[nestedKey] : undefined;
}

function unknownNode(path: string, rawText: string): ExplainPlanNode {
  return {
    key: path,
    title: 'Unknown plan node',
    sourcePath: path,
    metrics: [
      { label: 'Node', value: 'unknown' },
      { label: 'Raw length', value: String(rawText.length) },
    ],
    children: [],
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}
