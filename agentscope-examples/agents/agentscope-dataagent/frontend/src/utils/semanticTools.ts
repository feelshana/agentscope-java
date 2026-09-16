/**
 * Parses structured semantic-tool JSON returned by the new backend tools
 * (list_models, get_semantic_context, describe_model, recall_similar_queries,
 * dry_plan, query_semantic) and provides Chinese stage summaries + status.
 */

export type SemanticStage =
  | 'models'
  | 'context'
  | 'model'
  | 'recall'
  | 'plan'
  | 'query';

export type SemanticStatus = 'success' | 'error';

export interface SemanticDiagnostic {
  code: string;
  message: string;
  hint: string;
}

export interface SemanticResult {
  type: 'semantic_tool';
  stage: SemanticStage;
  status: SemanticStatus;
  groupId?: string;
  summary: string;
  data: Record<string, unknown>;
  diagnostics: SemanticDiagnostic[];
}

/** Safely parses a tool result string. Returns null for non-semantic tools. */
export function parseSemantic(raw: string | undefined): SemanticResult | null {
  if (!raw) return null;
  try {
    let v: unknown = JSON.parse(raw);
    if (typeof v === 'string') v = JSON.parse(v);
    if (
      v &&
      typeof v === 'object' &&
      !Array.isArray(v) &&
      (v as Record<string, unknown>).type === 'semantic_tool'
    )
      return v as SemanticResult;
  } catch { /* not JSON or not semantic */ }
  return null;
}

/** Returns true when the tool name is one of the new semantic tools. */
export function isSemanticTool(name: string): boolean {
  const n = name.toLowerCase();
  return (
    n === 'list_models' ||
    n === 'get_semantic_context' ||
    n === 'describe_model' ||
    n === 'recall_similar_queries' ||
    n === 'dry_plan' ||
    n === 'query_semantic'
  );
}

/**
 * Chinese short summary for the Trace row.
 * Uses backend summary when available, falls back to a tool-name label.
 */
export function stageSummary(name: string, result?: SemanticResult | null): string {
  if (result?.summary) return result.summary;
  const n = name.toLowerCase();
  if (n === 'list_models') return '发现语义模型';
  if (n === 'get_semantic_context') return '读取语义上下文';
  if (n === 'describe_model') return '读取模型详情';
  if (n === 'recall_similar_queries') return '召回参考查询';
  if (n === 'dry_plan') return '校验语义 SQL';
  if (n === 'query_semantic') return '执行语义查询';
  return name;
}

/**
 * True when the tool has finished with a real failure (error status),
 * as opposed to running or a successful completion.
 */
export function isFailed(result: SemanticResult | null): boolean {
  return result?.status === 'error';
}

/**
 * Icon name for the semantic tool in the Trace row.
 * Returns an IconName-compatible string; falls back to 'table'.
 */
export function stageIcon(name: string): 'table' | 'database' | 'search' | 'code' | 'settings' {
  const n = name.toLowerCase();
  if (n === 'list_models' || n === 'describe_model' || n === 'get_semantic_context') return 'table';
  if (n === 'recall_similar_queries') return 'search';
  if (n === 'dry_plan' || n === 'query_semantic') return 'database';
  return 'settings';
}
