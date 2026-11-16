let formatter: Promise<typeof import('sql-formatter')> | undefined;

/** Presentation only: callers retain the original SQL for editing, copying and requests. */
export async function formatReadableSql(source: string): Promise<string> {
  try {
    formatter ??= import('sql-formatter');
    const { formatDialect, mysql, trino } = await formatter;
    const options = { keywordCase: 'upper' as const, tabWidth: 2, expressionWidth: 70 };
    // Expanded Wren SQL may contain MySQL backticks or Trino double-quoted identifiers.
    try { return formatDialect(source, { ...options, dialect: source.includes('`') ? mysql : trino }); }
    catch { return formatDialect(source, { ...options, dialect: mysql }); }
  } catch {
    // An unsupported dialect must remain fully readable; never rewrite the underlying query.
    return source;
  }
}
