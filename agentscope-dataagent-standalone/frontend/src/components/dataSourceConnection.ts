import { ExternalDataSource } from '../api/datasources';

/** Extract display/edit fields without exposing credentials or arbitrary JDBC parameters. */
export function dataSourceConnection(source: ExternalDataSource) {
  try {
    const url = new URL(source.jdbcUrl.replace(/^jdbc:/, ''));
    if (!['mysql:', 'postgresql:'].includes(url.protocol) || !url.hostname) return null;
    const mysql = url.protocol === 'mysql:';
    return {
      host: url.hostname,
      port: url.port || (mysql ? '3306' : '5432'),
      database: decodeURIComponent(url.pathname.replace(/^\//, '')),
      sslMode: mysql
        ? url.searchParams.get('sslMode') || (url.searchParams.get('useSSL') === 'false' ? 'DISABLED' : 'PREFERRED')
        : url.searchParams.get('sslmode') || 'require',
    };
  } catch {
    return null;
  }
}

export function dataSourceKind(kind: string) {
  return kind === 'mysql' ? 'MySQL' : kind === 'postgresql' ? 'PostgreSQL' : kind.toUpperCase();
}
