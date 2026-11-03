import { useEffect, useState } from 'react';
import { getMdlPreview } from '../../api/semanticModeling';
import type { MdlPreview } from '../../api/semanticModeling';
import { getModelingQuestions } from '../../api/modelingQuestions';
import type { QuestionReview } from '../../api/modelingQuestions';
import CompiledMdlPanel from './CompiledMdlPanel';
import ReadableCode from '../ReadableCode';

/** The rule headings the official enrich-context routes to; others are the assistant's own. */
const OFFICIAL_SECTIONS = new Set(['Default filters', 'Naming conventions', 'External identifiers', 'Currency', 'Canonical tables', 'Metrics']);

/**
 * spec114: what the query assistant actually works from — the compiled MDL (wren://mdl), the
 * business rules (get_instructions) and the confirmed examples (recall_queries) — for the draft
 * or the online version. Draft rule lines missing online are highlighted as new.
 */
export default function SemanticContextPanel({ groupId }: { groupId: string }) {
  const [version, setVersion] = useState<'draft' | 'published'>('draft');
  const [tab, setTab] = useState<'mdl' | 'rules' | 'examples'>('rules');
  const [preview, setPreview] = useState<MdlPreview | null>(null);
  const [reviews, setReviews] = useState<QuestionReview[]>([]);
  useEffect(() => {
    const load = () => {
      void getMdlPreview(groupId).then(setPreview).catch(() => setPreview(null));
      void getModelingQuestions(groupId).then(setReviews).catch(() => setReviews([]));
    };
    load();
    const update = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) load();
    };
    window.addEventListener('modeling:updated', update);
    return () => window.removeEventListener('modeling:updated', update);
  }, [groupId]);
  const isRule = (path: string) => path.startsWith('knowledge/rules/') && path.endsWith('.md');
  const files = ((version === 'draft' ? preview?.files : preview?.publishedFiles) ?? []).filter(f => isRule(f.path));
  const online = new Set((preview?.publishedFiles ?? []).filter(f => isRule(f.path)).flatMap(f => f.content.split('\n').map(l => l.trim())));
  const examples = reviews.filter(r => r.status === 'CONFIRMED' && (version === 'draft' || r.published?.current));
  return <section className="da-card qc-context" aria-label="问数助手依据的内容">
    <div className="qc-context-head">
      <strong>问数助手依据的内容</strong>
      <div role="group" aria-label="版本" className="sm-switch">
        {(['draft', 'published'] as const).map(v => <button key={v} type="button" aria-pressed={version === v}
          className={version === v ? 'sm-switch-on' : ''} onClick={() => setVersion(v)}>{v === 'draft' ? '草稿' : '线上'}</button>)}
      </div>
      <div role="tablist" aria-label="内容" className="sm-switch">
        {([['rules', '规则'], ['mdl', 'MDL'], ['examples', `问答示例 ${examples.length}`]] as const).map(([key, label]) =>
          <button key={key} type="button" role="tab" aria-selected={tab === key} className={tab === key ? 'sm-switch-on' : ''} onClick={() => setTab(key)}>{label}</button>)}
      </div>
    </div>
    {tab === 'mdl' && <CompiledMdlPanel groupId={groupId} version={version} />}
    {tab === 'rules' && (files.length === 0 ? <div className="da-small">{version === 'draft' ? '草稿中还没有业务规则。' : '线上版本还没有业务规则。'}</div>
      : files.map(f => <div key={f.path} className="qc-rule-file">
        <div className="da-small qc-rule-path">{f.path.replace('knowledge/rules/', '')}</div>
        {f.content.split('\n').map((line, i) => {
          const heading = /^##\s+(.+)$/.exec(line.trim());
          if (heading) return <div key={i} className="qc-rule-heading">{heading[1]}
            {!OFFICIAL_SECTIONS.has(heading[1].trim()) && <span className="sm-tag sm-tag-warn qc-own">自建小节</span>}</div>;
          if (!line.trim() || line.trim().startsWith('# ')) return null;
          const added = version === 'draft' && !online.has(line.trim());
          return <div key={i} className={`qc-rule-line ${added ? 'qc-added' : ''}`} title={added ? '草稿新增或修改，发布后生效' : undefined}>{line}</div>;
        })}
      </div>))}
    {tab === 'examples' && (examples.length === 0 ? <div className="da-small">还没有已确认的问答示例。</div>
      : examples.map(r => <div key={r.question.id} className="qc-example">
        <strong>{r.question.question}</strong>
        <ReadableCode text={r.question.sql} language="sql" title="SQL" />
      </div>))}
  </section>;
}
