import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useLocation, useNavigate } from 'react-router-dom';
import { useOnboarding } from '../api/onboarding';
import { adoptTour, pauseTour, TourSignal, TourState, TourStepId, tourSignal, useTour } from '../state/guidedTour';
import { toast } from './Toast';
import './GuidedTour.css';

/**
 * spec105: walks a new user through the real pages. The spotlight leaves the real control
 * clickable and blocks the rest; waiting steps never cover the page, they only show a status card.
 */
interface Copy {
  no: number;
  target?: string;
  head: string;
  text: string;
  /** Shown instead of the spotlight while the control is not on screen yet. */
  waiting?: string;
  next?: boolean;
}

const COPY: Record<TourStepId, Copy> = {
  create: { no: 2, target: 'kb-create', head: '名称已填好，直接创建', text: '知识库是一份数据的家。名称可以改，以后也能在知识库详情里修改。' },
  source: { no: 3, target: 'kb-source', head: '选一种方式放入数据', text: '两种方式都内置了示例，不需要自己准备文件或数据库。第一次体验建议选“上传文件”。' },
  sample: { no: 4, target: 'sample-use', head: '点一下，直接用内置示例', text: '示例数据是报表访问明细和用户列表，两张表通过账号关联，适合体验多表分析。', waiting: '点击“上传文件”，就能使用内置示例' },
  importing: { no: 4, head: '正在准备数据', text: '可以继续看看其他页面，完成后这里会带你进入下一步。' },
  screening: { no: 5, target: 'screening-confirm', head: '确认字段，告诉助手该看什么', text: '系统已给出建议，示例数据直接确认即可。真实数据可以在这里去掉敏感或无关的字段。', waiting: '正在读取字段' },
  window: { no: 6, target: 'window-modal', head: '确认事实表的时间范围', text: '为大表限定单次查询的天数，避免全表扫描。选一个范围后保存；这一步只有外部表才有。', waiting: '正在确认事实表的时间范围' },
  start: { no: 7, target: 'model-start', head: '让建模助手开始工作', text: '示例数据没有业务文档，直接开始。助手会在对话里边做边说明，你随时可以补充或纠正。', waiting: '正在准备建模' },
  modeling: { no: 7, head: '建模助手正在工作', text: '你可以看着它一步步整理模型，也可以在对话里补充。完成后继续下一步。' },
  review: { no: 8, target: 'turn-result', head: '看看助手改了什么', text: '每一条都能打开查看和编辑。觉得不对，就在对话里告诉助手。这些修改都在草稿里，发布前不影响问数。', next: true, waiting: '建模结果会显示在回复下方' },
  confirm: { no: 8, target: 'confirm-cards', head: '确定这几个口径', text: '助手拿不准的关系和口径需要你来定。选好后提交，助手修改完草稿后再去试问。', waiting: '助手正在按你的选择修改草稿' },
  trial_open: { no: 9, target: 'trial-open', head: '发布前先试问一次', text: '用一个业务问题检验模型：助手按草稿写出 SQL，由你判断对不对。' },
  trial_ask: { no: 9, target: 'trial-panel', head: '问一个业务问题', text: '点推荐问题，或输入你想问的，助手会写出 SQL。' },
  trial_confirm: { no: 9, target: 'trial-confirm', head: '核对 SQL', text: '结果对就确认，发布后问数时会参考这组问答；不对可以点“不正确”让助手修改。', waiting: '助手正在生成 SQL' },
  publish: { no: 10, target: 'draft-confirm,confirm-cards,draft-publish', head: '发布后才能问数', text: '发布时系统会再检查一遍模型。问数只用已发布的版本，之后改草稿不影响线上，直到再次发布。', waiting: '助手完成后可以发布' },
  preparing: { no: 10, head: '发布成功，正在准备问数', text: '系统正在建立检索索引并预热，大约半分钟。准备好后会带你去问第一个问题，这样提问时不用再等。' },
  ask: { no: 11, target: 'chat-send', head: '问第一个问题', text: '知识库已选好，有推荐问题时也已填好。发送后就能看到答案，回答下方还能继续深入分析。', waiting: '选好知识库并输入问题' },
};

function copyFor(tour: TourState): Copy {
  if (tour.step === 'sample' && tour.route === 'datasource') {
    return {
      ...COPY.sample,
      target: 'demo-associate',
      head: '示例库已选好，点击关联',
      text: '这是平台内置的只读示例库。真实使用时，这里选你自己的数据源，操作完全一样。',
      waiting: '点击“关联数据源”，就能选到内置示例库',
    };
  }
  return COPY[tour.step];
}

/** Publishing can stop on open questions; the words then follow what is highlighted. */
const FOR_TARGET: Record<string, Partial<Copy>> = {
  'draft-confirm': { head: '还有待确定的口径', text: '点“去处理”逐项确定，确定后再发布。' },
  'confirm-cards': { head: '确定这几个口径', text: '选好后提交，助手会按你的选择修改草稿，改完再发布。' },
};

/** Numbers follow the route: uploaded data has no time-range step. */
function stepNumber(no: number, tour: TourState) {
  const upload = tour.route === 'upload';
  return { no: upload && no > 6 ? no - 1 : no, total: upload ? 10 : 11 };
}

function pathFor(tour: TourState): string | null {
  const group = tour.groupId ? encodeURIComponent(tour.groupId) : null;
  switch (tour.step) {
    case 'create': return '/configure/datasets?create=1';
    case 'source': case 'sample': return group && `/configure/datasets/${group}`;
    case 'importing': return null;
    case 'screening': case 'window': case 'start': return group && `/configure/modeling/${group}?asset=modeling&start=1`;
    case 'ask': return group ? `/chat?groups=${group}` : '/chat';
    default: return group && `/configure/modeling/${group}?asset=modeling`;
  }
}

function onPage(tour: TourState, pathname: string) {
  const target = pathFor(tour);
  return !target || pathname === target.split('?')[0];
}

const signalFor: Partial<Record<TourStepId, TourSignal>> = {
  review: 'review-next',
};

/** The first mark on screen, last element of it: the newest modeling result sits at the bottom. */
function findTarget(names: string): HTMLElement | null {
  for (const name of names.split(',')) {
    const all = Array.from(document.querySelectorAll<HTMLElement>(`[data-tour="${name}"]`));
    const found = all.reverse().find(el => el.getBoundingClientRect().width > 0);
    if (found) return found;
  }
  return null;
}

type Box = { top: number; left: number; width: number; height: number };
const PAD = 6;
const CARD_WIDTH = 320;
const CARD_HEIGHT = 200;

function useTargetBox(name: string | undefined): { box: Box | null; matched: string | null } {
  const [box, setBox] = useState<Box | null>(null);
  const [matched, setMatched] = useState<string | null>(null);
  const scrolled = useRef<HTMLElement | null>(null);
  useEffect(() => {
    setBox(null);
    scrolled.current = null;
    if (!name) return;
    const measure = () => {
      const el = findTarget(name);
      setMatched(el?.getAttribute('data-tour') ?? null);
      if (!el) { setBox(b => (b ? null : b)); return; }
      if (scrolled.current !== el) { scrolled.current = el; el.scrollIntoView({ block: 'nearest' }); }
      const r = el.getBoundingClientRect();
      const top = Math.max(r.top - PAD, 0);
      const left = Math.max(r.left - PAD, 0);
      const next = {
        top, left,
        width: Math.min(r.width + PAD * 2, window.innerWidth - left),
        height: Math.min(r.height + PAD * 2, window.innerHeight - top),
      };
      setBox(b => (b && b.top === next.top && b.left === next.left && b.width === next.width && b.height === next.height ? b : next));
    };
    measure();
    const timer = window.setInterval(measure, 200);
    window.addEventListener('resize', measure);
    return () => { window.clearInterval(timer); window.removeEventListener('resize', measure); };
  }, [name]);
  return { box, matched };
}

/** Prefers below, then above, then beside the target; stays on screen. */
function placeCard(box: Box): React.CSSProperties {
  const gap = 14;
  const clampX = (x: number) => Math.min(Math.max(x, 16), window.innerWidth - CARD_WIDTH - 16);
  const clampY = (y: number) => Math.min(Math.max(y, 16), window.innerHeight - CARD_HEIGHT - 16);
  if (box.top + box.height + gap + CARD_HEIGHT < window.innerHeight) return { top: box.top + box.height + gap, left: clampX(box.left) };
  if (box.top - gap - CARD_HEIGHT > 0) return { top: box.top - gap - CARD_HEIGHT, left: clampX(box.left) };
  if (box.left + box.width + gap + CARD_WIDTH < window.innerWidth) return { top: clampY(box.top), left: box.left + box.width + gap };
  if (box.left - gap - CARD_WIDTH > 0) return { top: clampY(box.top), left: box.left - gap - CARD_WIDTH };
  return { top: 16, right: 16 };
}

function Progress({ no, total }: { no: number; total: number }) {
  return <div className="da-tour-progress">
    <span>{no} / {total}</span>
    <span className="da-tour-segs" aria-hidden="true">
      {Array.from({ length: total }, (_, i) => <span key={i} className={i < no ? 'is-on' : undefined} />)}
    </span>
  </div>;
}

function Hand({ box }: { box: Box }) {
  return <span className="da-tour-hand" aria-hidden="true" style={{ top: box.top + box.height - 16, left: box.left + box.width - 22 }}>
    <svg width="44" height="44" viewBox="0 0 24 24" fill="#fff" stroke="#312e81" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M18 11a2 2 0 1 1 4 0v3a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15V4a2 2 0 0 1 4 0v5.5V9a2 2 0 0 1 4 0v1a2 2 0 0 1 3 1Z" />
    </svg>
  </span>;
}

export default function GuidedTour() {
  const status = useOnboarding();
  const tour = useTour();
  const { pathname } = useLocation();
  const navigate = useNavigate();
  useEffect(() => { if (status) adoptTour(status.tour); }, [status]);

  const active = tour && !tour.paused ? tour : null;
  const base = active ? copyFor(active) : null;
  const here = !!active && onPage(active, pathname);
  const { box, matched } = useTargetBox(here ? base?.target : undefined);
  const copy = base && active?.step === 'publish' && matched ? { ...base, ...FOR_TARGET[matched] } : base;

  // The last step lives on another page; finishing publishing takes the user there.
  const arrived = useRef<string | null>(null);
  useEffect(() => {
    if (!active || active.step !== 'ask' || arrived.current === active.groupId) return;
    arrived.current = active.groupId;
    const target = pathFor(active);
    if (target) navigate(target);
  }, [active, navigate]);

  const finishedOnce = useRef(false);
  const wasActive = useRef(false);
  useEffect(() => {
    if (wasActive.current && !tour && !finishedOnce.current) {
      finishedOnce.current = true;
      toast('引导完成，你已经走通了从数据到问数的全过程', 'success');
    }
    wasActive.current = !!tour;
  }, [tour]);

  const number = useMemo(() => (active && copy ? stepNumber(copy.no, active) : null), [active, copy]);
  if (!active || !copy || !number) return null;
  const next = () => { const s = signalFor[active.step]; if (s) tourSignal(s); };
  const goBack = pathFor(active);
  const exit = <button type="button" className="da-tour-exit" onClick={pauseTour}>退出引导</button>;

  if (!box) {
    const text = !here ? '引导停在了另一个页面，回去就能继续。' : copy.waiting ?? copy.text;
    return createPortal(<section className="da-tour-status" role="status" aria-label="操作指引">
      <Progress {...number} />
      <strong>{copy.head}</strong>
      <p>{text}</p>
      <div className="da-tour-foot">
        {exit}
        <span className="da-tour-actions">
          {!here && goBack && <button type="button" className="da-btn da-btn-primary da-btn-sm" onClick={() => navigate(goBack)}>回到这一步</button>}
          {here && copy.next && <button type="button" className="da-btn da-btn-primary da-btn-sm" onClick={next}>下一步</button>}
        </span>
      </div>
    </section>, document.body);
  }

  const shade = { position: 'fixed' as const };
  return createPortal(<div className="da-tour-layer">
    <div className="da-tour-shade" style={{ ...shade, top: 0, left: 0, right: 0, height: box.top }} />
    <div className="da-tour-shade" style={{ ...shade, top: box.top + box.height, left: 0, right: 0, bottom: 0 }} />
    <div className="da-tour-shade" style={{ ...shade, top: box.top, left: 0, width: box.left, height: box.height }} />
    <div className="da-tour-shade" style={{ ...shade, top: box.top, left: box.left + box.width, right: 0, height: box.height }} />
    <div className="da-tour-ring" style={{ top: box.top - 3, left: box.left - 3, width: box.width + 6, height: box.height + 6 }} />
    <Hand box={box} />
    <section className="da-tour-card" role="dialog" aria-label="操作指引" style={placeCard(box)}>
      <Progress {...number} />
      <strong>{copy.head}</strong>
      <p>{copy.text}</p>
      <div className="da-tour-foot">
        {exit}
        <span className="da-tour-actions">
          {copy.next
            ? <button type="button" className="da-btn da-btn-primary da-btn-sm" onClick={next}>下一步</button>
            : <span className="da-tour-hint">点击高亮处继续</span>}
        </span>
      </div>
    </section>
  </div>, document.body);
}
