import type { DraftSummary } from './DraftBar';
import Icon from '../Icon';

/** The next publish or query action at the end of the modeling conversation. */
export default function NextStepCard({ summary, nextVersion, firstPublishedVersion, preparing, onView, onPublish, onQuery, queryHeldHint }: {
  summary: DraftSummary;
  nextVersion: number;
  firstPublishedVersion: number | null;
  preparing: string | null | undefined;
  onView?: () => void;
  onPublish: () => void;
  onQuery?: () => void;
  /** Set while the guided tour will take the user to the query page itself. */
  queryHeldHint?: string;
}) {
  const released = summary.changes === 0 && !summary.publishing ? summary.publishedVersion ?? firstPublishedVersion : null;
  if (!released && !summary.publishing && summary.changes === 0) return null;

  const progressing = summary.publishing || (!!released && (preparing !== null || !onQuery));
  const complete = !!released && !progressing;
  const version = released ?? (summary.publishing ? summary.publishedVersion : null) ?? nextVersion;
  const firstRelease = version === 1;
  const draftTitle = firstRelease ? '模型已准备好' : '草稿待发布';
  const draftDetail = firstRelease ? '发布后，即可用这个知识库问数。' : `草稿有 ${summary.changes} 处改动，发布后问数将使用新版本。`;
  const title = progressing ? '模型正在发布' : complete ? '模型已发布' : draftTitle;
  const detail = summary.publishing ? '正在校验草稿并生成线上版本…'
    : progressing ? `${preparing ?? '正在确认问数服务是否就绪'}…`
      : complete ? queryHeldHint ?? '现在可以用这个知识库问数。' : draftDetail;

  return <section className={`mw-publish-card${complete ? ' is-published' : ''}`} aria-label="模型发布下一步">
    <span className="mw-publish-card-icon">
      {progressing ? <span className="da-spinner mw-publication-spinner" aria-hidden="true" /> : <Icon name={complete ? 'check' : 'model'} />}
    </span>
    <div className="mw-publish-card-copy" role="status">
      <h2>{title}<span>{complete ? `线上 v${version}` : progressing || !firstRelease ? `v${version}` : `待发布 v${version}`}</span></h2>
      <p>{detail}</p>
    </div>
    <div className="mw-publish-card-actions">
      {onView && <button type="button" className="da-btn" onClick={onView}>查看模型</button>}
      {complete
        ? <button type="button" className="da-btn da-btn-primary" disabled={!!queryHeldHint} title={queryHeldHint}
            onClick={onQuery}>去问数<Icon name="chevron" size="sm" /></button>
        : <button type="button" className={`da-btn da-btn-primary${progressing ? ' mw-publication-in-progress' : ''}`}
            disabled={progressing || !!summary.blocked} title={progressing ? undefined : summary.blocked ?? undefined} onClick={onPublish}>
            {firstRelease ? '发布模型' : '检查并发布'}{progressing ? <span className="da-spinner da-spinner-sm mw-publication-spinner" aria-hidden="true" /> : <Icon name="arrowUp" size="sm" />}
          </button>}
    </div>
    {summary.error && <p className="mw-publish-card-feedback" role="alert">{summary.error}</p>}
    {!summary.error && !released && !progressing && summary.blocked && <p className="mw-publish-card-feedback" role="status">{summary.blocked}</p>}
  </section>;
}
