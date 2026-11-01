import { Navigate, useSearchParams } from 'react-router-dom';

/** spec106: business terms live in the 业务知识 tab now; old links keep working. */
export default function SemanticConfigPage() {
  const [params] = useSearchParams();
  const group = params.get('group');
  return <Navigate to={group ? `/configure/modeling/${encodeURIComponent(group)}?asset=knowledge` : '/configure/modeling'} replace />;
}
