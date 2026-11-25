import { useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { listDocuments } from '../../api/draftReview';
import type { BusinessDocument } from '../../api/draftReview';
import Icon from '../Icon';
import type { IconName } from '../Icon';
import { WorkspaceMenu } from '../KnowledgeWorkspace';
import { brokenRefs, canonicalText, filterText, ruleWorkspaceTitle, ruleKey, rulePath, ruleRefs, SECTIONS, sectionOf, termText } from './ruleText';
import type { RuleItem, RuleSection } from './ruleText';
import type { SemanticSnapshot } from './semanticSnapshot';
import type { MarkTone } from './SemanticWorkbench';
import './ModelingWorkbenchTheme.css';
import './RulesWorkspace.css';

const starters: { section: RuleSection; icon: IconName; example: string }[] = [
  { section: SECTIONS[0], icon: 'filter', example: filterText({ scope: '全国', condition: "只取 province_name = '全国'", exception: '' }) },
  { section: SECTIONS[1], icon: 'terms', example: termText({ term: '悦看', synonyms: ['悦看视频'], meaning: "cmread_video = '悦看'" }) },
  { section: SECTIONS[2], icon: 'table', example: canonicalText({ scope: '会员消费', target: '客户订单明细' }) },
];
const anchor = (heading: string) => heading.replace(/ /g, '-');
const fileExample = `## ${SECTIONS[0].heading}\n- ${starters[0].example}\n\n## ${SECTIONS[2].heading}\n- ${starters[2].example}`;
const documentPrompt = '请阅读当前知识库已有的业务文档，列出候选的默认过滤、术语或指定取数表规则。注明来自哪份文档、原文依据及需要确认的口径。本轮只给提议，请不要写入草稿。';

export default function RulesWorkspace({ groupId, rules, models, selected, isDraft, isMarked, ready, editable, canEdit, hasInspector, viewActions,
  onSelectRule, onCreate, onMove, onAsk, onDocuments }: {
  groupId: string;
  rules: RuleItem[];
  models: SemanticSnapshot['models'];
  selected?: RuleItem;
  isDraft: (rule: RuleItem) => boolean;
  isMarked: (rule: RuleItem) => MarkTone;
  ready: boolean;
  editable: boolean;
  canEdit: boolean;
  hasInspector: boolean;
  viewActions?: ReactNode;
  onSelectRule: (rule: RuleItem) => void;
  onCreate: (heading: string) => void;
  onMove: (rules: RuleItem[], heading: string) => Promise<boolean>;
  onAsk?: (text: string) => void;
  onDocuments?: () => void;
}) {
  const [query, setQuery] = useState('');
  const [draftOnly, setDraftOnly] = useState(false);
  const [problemsOnly, setProblemsOnly] = useState(false);
  const [moving, setMoving] = useState(false);
  const [batchTarget, setBatchTarget] = useState<RuleSection>();
  const [moveMessage, setMoveMessage] = useState('');
  const [error, setError] = useState('');
  const [activeGroup, setActiveGroup] = useState('');
  const [documents, setDocuments] = useState<BusinessDocument[] | null>(null);
  const grid = useRef<HTMLDivElement>(null);
  const sections = useRef<HTMLDivElement>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const locatedScrollTop = useRef<number | null>(null);
  const location = useLocation();
  const navigate = useNavigate();
  const keyword = query.trim().toLocaleLowerCase();
  const showDraftOnly = editable && draftOnly;
  const annotated = useMemo(() => {
    const fieldNames = new Map<string, string>(models.flatMap(model => model.columns.map(column =>
      [`${model.name}.${column.name}`, column.displayName ?? ''] as const)));
    return rules.map(rule => {
      const section = sectionOf(rule.heading);
      const refs = ruleRefs(rule.text, models);
      const modelFields = refs.map(ref => fieldNames.get(ref));
      return { rule, section, anchor: ruleWorkspaceTitle(rule), hasProblem: !section || brokenRefs(rule.text, models).length > 0,
        searchable: [rule.text, rule.path, rule.heading, section?.label, ...refs, ...modelFields].join(' ').toLocaleLowerCase() };
    });
  }, [models, rules]);

  const items = annotated.map(item => ({ ...item, draft: isDraft(item.rule) }));
  const allCounts = new Map<string, number>();
  for (const item of items) if (item.section) allCounts.set(item.section.heading, (allCounts.get(item.section.heading) ?? 0) + 1);
  const unclassifiedCount = items.filter(item => !item.section).length;
  const filesCount = new Set(rules.map(rule => rule.path)).size;
  const draftCount = items.filter(item => item.draft).length;
  const issueCount = items.filter(item => item.hasProblem).length;
  const filtered = items.filter(item => item.searchable.includes(keyword) && (!showDraftOnly || item.draft) && (!problemsOnly || item.hasProblem));
  const filtering = !!keyword || showDraftOnly || problemsOnly;
  const empty = ready && rules.length === 0;
  const groups = SECTIONS.map(section => ({ section, rows: filtered.filter(item => item.section?.heading === section.heading) }));
  const unclassified = filtered.filter(item => !item.section);

  useEffect(() => {
    if (!ready || !editable) return;
    let active = true;
    void listDocuments(groupId).then(list => { if (active) setDocuments(list.filter(doc => doc.status !== 'DELETED')); })
      .catch(() => { if (active) setDocuments(null); });
    return () => { active = false; };
  }, [ready, editable, groupId]);

  const scrollToGroup = (id: string) => {
    const body = scroller.current;
    const target = document.getElementById(id);
    if (body && target && sections.current?.contains(target)) {
      const header = grid.current;
      const offset = header && getComputedStyle(header).position === 'sticky' ? header.offsetHeight : 0;
      body.scrollTop += target.getBoundingClientRect().top - body.getBoundingClientRect().top - offset - 8;
      locatedScrollTop.current = body.scrollTop;
    }
    setActiveGroup(id);
  };

  useEffect(() => {
    if (!ready || empty) return;
    const id = location.hash.slice(1);
    if (SECTIONS.some(section => anchor(section.heading) === id)) scrollToGroup(id);
  }, [ready, empty, location.hash]);

  useEffect(() => {
    const header = grid.current;
    const body = scroller.current;
    if (!header || !body) return;
    const sync = () => body.style.setProperty('--rw-sticky-top', `${header.offsetHeight}px`);
    const observer = new ResizeObserver(sync);
    sync();
    observer.observe(header);
    return () => observer.disconnect();
  }, []);

  const locate = (section: RuleSection) => {
    const id = anchor(section.heading);
    scrollToGroup(id);
    void navigate({ pathname: location.pathname, search: location.search, hash: id }, { replace: true, preventScrollReset: true });
  };
  const clearFilters = () => { setQuery(''); setDraftOnly(false); setProblemsOnly(false); setBatchTarget(undefined); setMoveMessage(''); setError(''); };
  const move = async (target: RuleSection) => {
    const candidates = unclassified.map(item => item.rule);
    setMoving(true); setError(''); setMoveMessage('');
    try {
      if (await onMove(candidates, target.heading)) {
        setBatchTarget(undefined);
        setMoveMessage(`已将 ${candidates.length} 条规则移到「${target.label}」，发布后生效。`);
      }
    }
    catch (reason) { setError(reason instanceof Error ? reason.message : String(reason)); }
    finally { setMoving(false); }
  };

  const active = activeGroup || anchor(groups.find(group => group.rows.length)?.section.heading ?? SECTIONS[0].heading);
  const askFromDocuments = () => onAsk?.(documentPrompt);
  const selectedKey = selected ? ruleKey(selected) : undefined;
  useEffect(() => {
    if (!selectedKey) return;
    const body = scroller.current;
    const row = sections.current?.querySelector('[data-selected]');
    if (!body || !row) return;
    const header = grid.current;
    const visibleTop = header && getComputedStyle(header).position === 'sticky'
      ? header.getBoundingClientRect().bottom : body.getBoundingClientRect().top;
    const bounds = row.getBoundingClientRect();
    const visibleBottom = body.getBoundingClientRect().bottom;
    if (bounds.top < visibleTop) body.scrollTop += bounds.top - visibleTop;
    if (bounds.bottom > visibleBottom) body.scrollTop += bounds.bottom - visibleBottom;
  }, [selectedKey, selected?.line]);
  const showBatch = canEdit && problemsOnly && unclassified.length > 1;
  const status = [keyword ? '搜索结果' : '', showDraftOnly ? '仅看草稿' : '', problemsOnly ? '仅看有问题' : ''].filter(Boolean).join(' · ');
  const renderRow = ({ rule, anchor: title, draft, hasProblem, section }: (typeof items)[number]) => {
    const key = ruleKey(rule);
    const highlighted = selectedKey === key && selected?.line === rule.line;
    return <li key={`${key}:${rule.line ?? ''}`} className="rw-row" data-unclassified={!section || undefined}
      data-selected={highlighted || undefined} data-tone={isMarked(rule) || undefined}>
      <button type="button" className="rw-rule-index" aria-current={highlighted || undefined} disabled={moving} onClick={() => onSelectRule(rule)}>
        {!section && <Icon name="circleAlert" size="sm" />}
        <span className="rw-row-copy"><strong title={rule.text}>{title}</strong>
          <small>{rule.path.split('/').pop()}{rule.line && ` · 第 ${rule.line} 行`}</small></span>
        {section && hasProblem && <span className="rw-tag rw-tag-warn">引用有误</span>}
        {draft && <span className="rw-tag rw-tag-draft">草稿</span>}
      </button>
      {!section && canEdit && <button type="button" className="rw-move" aria-label={`移动规则 ${title}`}
        disabled={moving} onClick={() => onSelectRule(rule)}>移到 <Icon name="chevron" size="sm" /></button>}
    </li>;
  };
  return <section className="rw" aria-label="业务规则工作区">
    <div className="rw-scroll" ref={scroller} onScroll={event => {
      if (!sections.current) return;
      const body = event.currentTarget;
      if (locatedScrollTop.current === body.scrollTop) return;
      locatedScrollTop.current = null;
      const header = grid.current;
      const offset = header && getComputedStyle(header).position === 'sticky' ? header.offsetHeight : 0;
      const top = body.getBoundingClientRect().top + offset + 12;
      const visible = Array.from(sections.current.querySelectorAll<HTMLElement>('[data-rule-group]'));
      const current = [...visible].reverse().find(element => element.getBoundingClientRect().top <= top) ?? visible[0];
      if (current) setActiveGroup(current.id);
    }}>
      <div className="rw-top" ref={grid}>
        <div className="rw-intro">
          <h2>业务规则</h2>{ready && <span>{rules.length} 条{rules.length > 0 ? ` · ${SECTIONS.length} 个小节（${allCounts.size} 个有内容）` : ''}{draftCount > 0 ? ` · ${draftCount} 条草稿待发布` : ''}</span>}
          {!empty && ready && editable && <button type="button" className="da-btn da-btn-sm rw-filter" aria-pressed={showDraftOnly} disabled={moving} onClick={() => setDraftOnly(value => !value)}>
            仅看草稿 <b>{draftCount}</b>
          </button>}
          {!empty && ready && <button type="button" className="da-btn da-btn-sm rw-filter rw-problem-filter" aria-pressed={problemsOnly} disabled={moving}
            title={unclassifiedCount ? `${unclassifiedCount} 条未归类，问数可能读不到` : undefined}
            onClick={() => { setProblemsOnly(value => !value); setBatchTarget(undefined); setMoveMessage(''); setError(''); }}>
            仅看有问题 <b>{issueCount}</b>
          </button>}
        </div>
        {ready && (!empty || editable) && <div className="rw-nav">{!empty && <><nav className="rw-nav-tabs" aria-label="六个官方小节">
          {groups.map(({ section, rows }) => {
            const count = allCounts.get(section.heading) ?? 0;
            return <button type="button" className="rw-jump" key={section.heading} data-empty={count === 0 || undefined}
              aria-current={active === anchor(section.heading) ? 'location' : undefined}
              disabled={moving || (filtering && !rows.length)}
              onClick={() => locate(section)}>{section.label} <b>{count}</b></button>;
          })}
        </nav><label className="rw-search"><Icon name="search" size="sm" />
          <input type="search" aria-label="搜索业务规则" placeholder="搜索规则、术语或字段" value={query}
            disabled={moving} onChange={event => setQuery(event.target.value)} />
        </label></>}
          <div className="rw-tools">
            {!empty && editable && (onAsk || onDocuments) && <button type="button" className="da-btn da-btn-sm" disabled={!canEdit || (!documents?.length && !onDocuments)} onClick={documents?.length && onAsk ? askFromDocuments : onDocuments}>
              <Icon name="compose" size="sm" />从资料提取
            </button>}
            {ready && editable && <WorkspaceMenu label="新增规则" triggerLabel="新增规则" triggerIcon="plus"
              items={SECTIONS.map(section => ({ label: section.label, icon: 'file', disabled: !canEdit || moving, onClick: () => onCreate(section.heading) }))} />}
          </div>
        </div>}
        {filtering && <div className="rw-filter-status" data-warning={problemsOnly || undefined}>
          <Icon name="filter" size="sm" /><b>{status}</b><span>{filtered.length} 条结果{filtered.length < rules.length ? ` · 其余 ${rules.length - filtered.length} 条已隐藏` : ''}</span>
          <button type="button" disabled={moving} className="sw-link" onClick={clearFilters}>{keyword && !showDraftOnly && !problemsOnly ? '清除搜索' : '清除筛选'}</button>
        </div>}
      </div>
      <div className="rw-content" ref={sections}>
        {error && <p className="da-alert da-alert-error" role="alert">{error}</p>}
        {moveMessage && <p className="rw-move-result" role="status"><Icon name="check" size="sm" />{moveMessage}</p>}
        {!ready ? <p className="rw-no-results" role="status">正在读取业务规则…</p> : empty ? <>
          {editable && <p className="rw-empty-lead">规则写在 <code>knowledge/rules/*.md</code> 的 <code>##</code> 小节下。问数助手按小节标题查找规则。</p>}
          <div className="rw-start" data-solo={!editable || hasInspector || undefined}>
            <div className="rw-start-main">
              <h3>还没有业务规则</h3>
              {editable && <>
                <p>从三个常用小节选一个，按示例填写第一条规则。</p>
                <div className="rw-starters">
                  {starters.map(({ section, example, icon }) => <div className="rw-starter" key={section.heading}>
                    <span className="rw-starter-icon"><Icon name={icon} size="sm" /></span>
                    <div><h4>{section.label}</h4><code>{example}</code><small>{section.hint.split('，例如')[0]}</small></div>
                    <button type="button" className="da-btn da-btn-sm" disabled={!canEdit} aria-label={`用${section.label}句式新建`}
                      onClick={() => onCreate(section.heading)}><Icon name="plus" size="sm" />新建</button>
                  </div>)}
                </div>
                {(onDocuments || onAsk && !!documents?.length) && <div className="rw-materials">
                  <span className="rw-starter-icon"><Icon name="file" size="sm" /></span>
                  <div><strong>{documents?.length ? `已有 ${documents.length} 份业务文档` : '从业务资料补充规则'}</strong>
                    <p>{documents?.length ? '让助手整理候选规则，再逐条核对依据。' : '可在资料页添加业务口径文档。'}</p></div>
                  {onDocuments && <button type="button" className="da-btn da-btn-sm" onClick={onDocuments}>去资料</button>}
                  {onAsk && !!documents?.length && <button type="button" className="da-btn da-btn-sm" disabled={!canEdit} onClick={askFromDocuments}>去提取</button>}
                </div>}
                <p className="rw-start-note">保存先进草稿，发布后问数助手才会读取。</p>
              </>}
            </div>
            {editable && !hasInspector && <section className="rw-file-outline" aria-label="规则文件骨架">
              <header><Icon name="folder" size="sm" /><code>knowledge/rules/</code><span>文件骨架</span></header>
              <ul>{SECTIONS.map(section => <li key={section.heading} data-advanced={section.kind === 'other' || undefined}>
                <code>## {section.heading}</code><span>{section.label}</span><small>{rulePath(section).split('/').pop()}</small>
                {section.kind !== 'other' && <button type="button" className="rw-icon-btn" aria-label={`新增${section.label}`}
                  disabled={!canEdit} onClick={() => onCreate(section.heading)}><Icon name="plus" size="sm" /></button>}
              </li>)}</ul>
              <div className="rw-file-example"><span>文件写法示例</span><pre>{fileExample}</pre></div>
              <footer>问数助手按这六个官方小节读取。后三个小节通常在有内容时才写入。</footer>
            </section>}
          </div>
        </> : <>
          {filtering && filtered.length === 0 && <div className="rw-no-results" role="status">
            <span>{keyword ? `没有匹配「${query.trim()}」的规则。` : '当前筛选下没有规则。'}</span>
          </div>}
          {groups.filter(({ rows }) => rows.length > 0 || !filtering).map(({ section, rows }) => (
            <section key={section.heading} id={anchor(section.heading)} data-rule-group className={`rw-group${rows.length ? '' : ' rw-group-empty'}`}>
              <header><h3>{section.label}</h3><span className="rw-count">{rows.length}</span><p>{section.kind === 'canonical' ? '某类问题固定从哪张表或视图取数' : section.hint}</p>
                {canEdit && <button type="button" className="sw-link" aria-label={`新增${section.label}`} disabled={moving} onClick={() => onCreate(section.heading)}>＋ 新增</button>}
              </header>
              {rows.length > 0 && <ul className="rw-list">{rows.map(renderRow)}</ul>}
            </section>
          ))}
          {unclassified.length > 0 && <section className="rw-group rw-unclassified" id="Unclassified" data-rule-group>
            <header><h3>未归类</h3><span className="rw-count">{unclassified.length}</span><p>不在官方小节，问数助手可能不会读到</p></header>
            <ul className="rw-list">{unclassified.map(renderRow)}</ul>
            {showBatch && <section className="rw-batch" aria-label="批量移动未归类规则">
              <div><strong>一起处理当前 {unclassified.length} 条未归类规则</strong><p>各条保留原文和所在文件，只移动到选定小节。</p></div>
              <WorkspaceMenu label="选择批量移动目的地" triggerLabel={batchTarget?.label ?? '选择目的地'} triggerIcon="folder"
                items={starters.map(({ section }) => ({ label: section.label, icon: 'file', disabled: moving, onClick: () => setBatchTarget(section) }))} />
              {batchTarget && <button type="button" className="da-btn da-btn-sm da-btn-primary" disabled={moving} onClick={() => void move(batchTarget)}>
                {moving ? '正在移动…' : `将 ${unclassified.length} 条移到${batchTarget.label}`}
              </button>}
              {batchTarget && <ul className="rw-batch-preview" aria-label="批量移动后的文件位置">
                {Array.from(new Set(unclassified.map(({ rule }) => rule.path))).map(path => <li key={path}>
                  <strong>{path.split('/').pop()}</strong><code>## {batchTarget.heading}</code>
                  <span>{unclassified.filter(({ rule }) => rule.path === path).length} 条</span>
                </li>)}
              </ul>}
            </section>}
            {problemsOnly && <details className="rw-problem-locations" open={unclassified.length <= 5 || undefined}>
              <summary><Icon name="file" size="sm" />未归类规则的位置</summary>
              <ul>{unclassified.map(({ rule, anchor: title }) => <li key={`${ruleKey(rule)}:${rule.line ?? ''}`}>
                <button type="button" disabled={moving} onClick={() => onSelectRule(rule)}>
                  <code>{rule.path.split('/').pop()}{rule.line ? ` · 第 ${rule.line} 行` : ''}</code>
                  <span>{rule.heading ? `## ${rule.heading}` : '无小节'}</span><strong>{title}</strong>
                </button>
              </li>)}</ul>
            </details>}
          </section>}
        </>}
      </div>
    </div>
    {ready && <footer className="rw-footer">
      <span>{filtering ? `${filtered.length} / ` : ''}{rules.length} 条{draftCount > 0 ? ` · ${draftCount} 条草稿待发布` : editable ? ' · 草稿发布后用于问数' : ''}</span>
      <div className="rw-view-actions">
        <span><code>knowledge/rules/</code>{filesCount > 0 ? ` · ${filesCount} 个文件` : ''}</span>
        {viewActions}
      </div>
    </footer>}
  </section>;
}
