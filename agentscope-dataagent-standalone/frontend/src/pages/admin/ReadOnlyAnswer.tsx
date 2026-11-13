import { lazy, Suspense } from 'react';
import type { AdminConversationTurn } from '../../api/adminHistory';
import AnswerContent from '../../components/AnswerContent';
import Icon from '../../components/Icon';
import CopyAnswerButton from '../../components/CopyAnswerButton';
import { citedChartIds, normalizeChartReferences } from '../../components/answerRefs';

const EChartsBlock = lazy(() => import('../../components/EChartsBlock'));

export default function ReadOnlyAnswer({ turn, portalRoot }: { turn: AdminConversationTurn; portalRoot: Element | null }) {
  const text = turn.answer || '';
  const images = turn.visuals?.images ?? [];
  const resolveImage = (reference: string) => {
    let decoded: string;
    try { decoded = decodeURIComponent(reference); } catch { return null; }
    const normalized = decoded.replace(/^(?:\.\/)?outputs\//, '').replace(/^\.\//, '');
    const candidates = images.filter(i => i.reference === reference || i.name === normalized);
    return candidates.length === 1 ? candidates[0].url : null;
  };
  const charts = new Map((turn.visuals?.charts ?? []).map(c => [c.id,
    <Suspense key={c.url} fallback={<span role="status">正在加载图表…</span>}><EChartsBlock payload={{ chartId: c.id, title: c.title }} sourceUrl={c.url} /></Suspense>,
  ]));
  const cited = citedChartIds(normalizeChartReferences(text, charts.keys()));
  return <div className="ah-readonly-answer">
    {text ? <AnswerContent charts={charts} resolveImage={resolveImage} readOnly portalRoot={portalRoot}>{text}</AnswerContent> : <p className="da-small">暂无已保存的回复正文。</p>}
    {[...charts].filter(([id]) => !cited.has(id)).map(([id, visual]) => <div key={id}>{visual}</div>)}
    {images.filter(i => !text.includes(i.reference) && !text.includes(`](${i.name})`) && !text.includes(`](outputs/${i.name})`)).map(i =>
      <AnswerContent key={i.url} readOnly portalRoot={portalRoot} resolveImage={reference => reference === i.reference ? i.url : null}>{`![${i.name.replace(/[\[\]\\]/g, '')}](${i.reference})`}</AnswerContent>)}
    <div className="ah-answer-footer">
      {text && <CopyAnswerButton text={text} />}
      {turn.feedbackAvailable === false ? <span>用户反馈暂不可用</span> : turn.feedbackVote ?
        <span className={`ah-original-vote ${turn.feedbackVote === 'DOWN' ? 'negative' : ''}`}><Icon name={turn.feedbackVote === 'UP' ? 'thumbsUp' : 'thumbsDown'} size="sm" />{turn.feedbackVote === 'UP' ? '用户已赞' : '用户已踩'}</span> : null}
    </div>
  </div>;
}
