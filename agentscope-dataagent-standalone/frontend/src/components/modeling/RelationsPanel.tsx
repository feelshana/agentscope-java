import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { PointerEvent as ReactPointerEvent } from 'react';
import { CARD_WIDTH, layoutRelations } from './relationLayout';
import { joinLabel, joinMark, modelTag, relationsOf, unrelatedModels } from './semanticSnapshot';
import type { SemanticRelation, SemanticSnapshot } from './semanticSnapshot';

type Selection = { kind: 'model' | 'relation'; name: string } | null;
const MIN_ZOOM = 0.4;
const MAX_ZOOM = 1.5;
const clamp = (value: number) => Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, value));

/**
 * spec106 关系页: field-level ER cards drawn as React cards over SVG lines, so every card and line
 * stays a real keyboard-reachable control. Read-only; `focus` opens a relation from the model page.
 */
export default function RelationsPanel({ snapshot, focus, onOpenModel, onAsk, emptyText }: {
  snapshot: SemanticSnapshot;
  focus?: string | null;
  onOpenModel: (name: string) => void;
  onAsk?: (text: string) => void;
  emptyText: string;
}) {
  const [view, setView] = useState<'graph' | 'list'>('graph');
  const [selection, setSelection] = useState<Selection>(focus ? { kind: 'relation', name: focus } : null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [only, setOnly] = useState(false);
  const [zoom, setZoom] = useState(1);
  const [query, setQuery] = useState('');
  const canvas = useRef<HTMLDivElement>(null);
  const layout = useMemo(() => layoutRelations(snapshot, expanded), [snapshot, expanded]);
  const unrelated = useMemo(() => unrelatedModels(snapshot), [snapshot]);
  const loose = useMemo(() => new Set(unrelated.map(m => m.name)), [unrelated]);

  useEffect(() => { if (focus) { setSelection({ kind: 'relation', name: focus }); setView('graph'); } }, [focus]);

  const fit = useCallback(() => {
    const box = canvas.current;
    if (!box || !layout.width) return;
    setZoom(clamp(Math.min(1, box.clientWidth / layout.width, box.clientHeight / layout.height)));
    box.scrollTo(0, 0);
  }, [layout.width, layout.height]);
  // Fit once per snapshot; expanding a card must not move the view under the pointer.
  const models = snapshot.models.length;
  useEffect(() => { if (view === 'graph') fit(); }, [models, view]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    const box = canvas.current;
    if (!box) return undefined;
    const wheel = (event: WheelEvent) => {
      if (!event.ctrlKey && !event.metaKey) return;
      event.preventDefault();
      setZoom(z => clamp(z - event.deltaY * 0.001));
    };
    box.addEventListener('wheel', wheel, { passive: false });
    return () => box.removeEventListener('wheel', wheel);
  }, [view]);

  const drag = useRef<{ x: number; y: number; left: number; top: number } | null>(null);
  const startDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    const box = canvas.current;
    if (!box || (event.target as HTMLElement).closest('button')) return;
    drag.current = { x: event.clientX, y: event.clientY, left: box.scrollLeft, top: box.scrollTop };
    box.setPointerCapture(event.pointerId);
  };
  const moveDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!drag.current || !canvas.current) return;
    canvas.current.scrollLeft = drag.current.left - (event.clientX - drag.current.x);
    canvas.current.scrollTop = drag.current.top - (event.clientY - drag.current.y);
  };

  const relation = selection?.kind === 'relation' ? snapshot.relations.find(r => r.name === selection.name) ?? null : null;
  const model = selection?.kind === 'model' ? snapshot.models.find(m => m.name === selection.name) ?? null : null;
  const lit = (r: SemanticRelation) => relation ? r.name === relation.name : model ? r.left === model.name || r.right === model.name : false;
  const near = new Set(snapshot.relations.filter(lit).flatMap(r => [r.left, r.right]));
  if (model) near.add(model.name);
  const faded = (name: string) => only && !!selection && !near.has(name);
  const hotColumns = new Set(relation ? [...relation.sourceColumns.map(c => `${relation.left}.${c}`), ...relation.targetColumns.map(c => `${relation.right}.${c}`)] : []);

  const locate = (name: string) => {
    const card = layout.cards.find(c => c.model.name === name);
    const box = canvas.current;
    if (!card || !box) return;
    setSelection({ kind: 'model', name });
    box.scrollTo({ left: Math.max(0, card.x * zoom - 40), top: Math.max(0, card.y * zoom - 40), behavior: 'smooth' });
  };
  const search = () => {
    const keyword = query.trim().toLowerCase();
    const hit = keyword && snapshot.models.find(m => m.name.toLowerCase().includes(keyword) || m.columns.some(c => c.name.toLowerCase().includes(keyword)));
    if (hit) locate(hit.name);
  };

  if (!snapshot.models.length) return <div className="sm-empty">{emptyText}</div>;

  return <div className="sm-relations">
    <div className="sm-rel-bar">
      <div role="group" aria-label="关系视图" className="sm-switch">
        <button type="button" aria-pressed={view === 'graph'} className={view === 'graph' ? 'sm-switch-on' : ''} onClick={() => setView('graph')}>关系图</button>
        <button type="button" aria-pressed={view === 'list'} className={view === 'list' ? 'sm-switch-on' : ''} onClick={() => setView('list')}>关系列表</button>
      </div>
      <span className="da-small">{snapshot.relations.length} 条关系{unrelated.length ? ` · ${unrelated.length} 个模型未关联` : ''}</span>
      <span style={{ flex: 1 }} />
      {view === 'graph' && <>
        <input className="da-input sm-rel-search" aria-label="定位模型或字段" placeholder="定位模型或字段，回车" value={query} onChange={e => setQuery(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') search(); }} />
        <button type="button" className={`da-btn da-btn-sm ${only ? 'da-btn-primary' : ''}`} aria-pressed={only} disabled={!selection} title={selection ? '' : '先选中一个模型或关系'} onClick={() => setOnly(!only)}>只看相关</button>
        <button type="button" className="da-btn da-btn-sm" aria-label="缩小" onClick={() => setZoom(z => clamp(z - 0.1))}>−</button>
        <span className="da-small sm-zoom">{Math.round(zoom * 100)}%</span>
        <button type="button" className="da-btn da-btn-sm" aria-label="放大" onClick={() => setZoom(z => clamp(z + 0.1))}>＋</button>
        <button type="button" className="da-btn da-btn-sm" onClick={fit}>适应画布</button>
      </>}
    </div>
    {view === 'list' ? <div className="sm-rel-list">
      {snapshot.relations.length ? <div className="sm-table-box"><table className="sm-table">
        <thead><tr><th>从</th><th>到</th><th>类型</th><th>关联条件</th><th>说明</th></tr></thead>
        <tbody>{snapshot.relations.map(r => <tr key={r.name}>
          <td>{r.left}</td><td>{r.right}</td><td>{joinLabel(r.joinType)}</td><td className="sm-code">{r.condition}</td><td>{r.description || '—'}</td>
        </tr>)}</tbody>
      </table></div> : <div className="sm-empty">还没有关系。</div>}
      {unrelated.length > 0 && <p className="sm-warn sm-rel-note">未关联：{unrelated.map(m => m.name).join('、')}。这些模型问数时不能和其他表一起分析。</p>}
    </div> : <div className="sm-rel-body">
      <div ref={canvas} className="sm-canvas" onPointerDown={startDrag} onPointerMove={moveDrag} onPointerUp={() => { drag.current = null; }}>
        <div style={{ width: layout.width * zoom, height: layout.height * zoom }}>
          <div className="sm-stage" style={{ width: layout.width, height: layout.height, transform: `scale(${zoom})` }}>
            <svg width={layout.width} height={layout.height} aria-hidden="true">
              {layout.edges.map(e => <path key={e.key} d={e.path} fill="none" stroke={lit(e.relation) ? 'var(--da-primary)' : '#b9bcc9'} strokeWidth={lit(e.relation) ? 2.5 : 1.5} opacity={faded(e.relation.left) || faded(e.relation.right) ? 0.15 : 1} />)}
            </svg>
            {layout.edges.map(e => <button key={e.key} type="button" className={`sm-edge ${lit(e.relation) ? 'sm-edge-on' : ''}`} aria-label={`查看关系 ${e.relation.left} 到 ${e.relation.right}`}
              style={{ left: e.x, top: e.y, opacity: faded(e.relation.left) || faded(e.relation.right) ? 0.15 : 1 }} onClick={() => setSelection({ kind: 'relation', name: e.relation.name })}>{joinMark(e.relation.joinType)}</button>)}
            {layout.cards.map(card => {
              const tag = modelTag(card.model, loose.has(card.model.name));
              const open = expanded.has(card.model.name);
              const on = model?.name === card.model.name;
              return <div key={card.model.name} className={`sm-card ${on ? 'sm-card-on' : ''} ${tag.tone === 'warn' ? 'sm-card-warn' : ''}`}
                style={{ left: card.x, top: card.y, width: CARD_WIDTH, opacity: faded(card.model.name) ? 0.25 : 1 }}>
                <button type="button" className="sm-card-head" onClick={() => setSelection({ kind: 'model', name: card.model.name })}>
                  <span className="sm-card-name"><strong title={card.model.name}>{card.model.name}</strong><small title={card.model.description ?? ''}>{card.model.columns.length} 个字段</small></span>
                  <span className={`sm-tag sm-tag-${tag.tone}`}>{tag.label}</span>
                </button>
                {card.rows.map(row => <div key={row.name} className={`sm-card-row ${hotColumns.has(`${card.model.name}.${row.name}`) ? 'sm-card-row-on' : ''}`}><span title={row.name}>{row.name}</span>{row.mark && <em>{row.mark}</em>}</div>)}
                {card.hidden > 0 && <button type="button" className="sm-card-more" aria-expanded={open}
                  onClick={() => setExpanded(previous => { const next = new Set(previous); if (open) next.delete(card.model.name); else next.add(card.model.name); return next; })}>
                  {open ? '收起字段' : `＋ 其余 ${card.hidden} 个字段`}</button>}
              </div>;
            })}
          </div>
        </div>
      </div>
      <aside aria-label="详情" className="sm-rel-side">
        {relation ? <>
          <span className="da-small">关系</span><h3>{relation.left} → {relation.right}</h3>
          <dl>
            <dt>类型</dt><dd>{joinLabel(relation.joinType)}（{relation.joinType}）</dd>
            <dt>条件</dt><dd className="sm-code">{relation.condition}</dd>
            <dt>说明</dt><dd>{relation.description || '—'}</dd>
          </dl>
          {onAsk && <button type="button" className="sm-text-link" onClick={() => onAsk(`请调整关系 ${relation.left} → ${relation.right}（${relation.condition}）：`)}>让建模助手修改</button>}
        </> : model ? <>
          <span className="da-small">模型</span><h3>{model.name}</h3>
          <p>{model.description || '业务含义待补充'}</p>
          <p className="da-small">{model.columns.length} 个字段 · {relationsOf(snapshot, model.name).length} 条关系</p>
          <button type="button" className="da-btn da-btn-sm" onClick={() => onOpenModel(model.name)}>打开模型详情</button>
        </> : <>
          <span className="da-small">概览</span><h3>{snapshot.models.length} 个模型，{snapshot.relations.length} 条关系</h3>
          <p className="da-small">点卡片看模型，点 {joinMark('MANY_TO_ONE')} 看关系。按住空白处拖动画布，Ctrl + 滚轮缩放。</p>
          {unrelated.length > 0 && <p className="sm-warn">未关联：{unrelated.map(m => m.name).join('、')}</p>}
        </>}
      </aside>
    </div>}
  </div>;
}
