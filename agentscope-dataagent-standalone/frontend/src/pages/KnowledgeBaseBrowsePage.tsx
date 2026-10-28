import React from 'react';
import { Navigate, useParams } from 'react-router-dom';
export default function KnowledgeBaseBrowsePage() {
  const { groupId = '' } = useParams();
  return <Navigate to={`/configure/datasets/${encodeURIComponent(groupId)}`} replace />;
}
