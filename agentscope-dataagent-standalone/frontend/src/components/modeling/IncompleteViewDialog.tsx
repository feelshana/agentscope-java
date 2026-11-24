import { useEffect, useState } from 'react';
import { inspectIncompleteView, removeIncompleteView } from '../../api/semanticAssets';
import type { IncompleteView } from '../../api/semanticAssets';
import ReadableCode from '../ReadableCode';
import EngineeringDialog from './EngineeringDialog';

export default function IncompleteViewDialog({ groupId, name, disabled, onClose, onRemoved }: {
  groupId: string;
  name: string;
  disabled: boolean;
  onClose: () => void;
  onRemoved: () => void;
}) {
  const [view, setView] = useState<IncompleteView | null>(null);
  const [selected, setSelected] = useState('');
  const [attempt, setAttempt] = useState(0);
  const [error, setError] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [removing, setRemoving] = useState(false);
  useEffect(() => {
    let active = true;
    void inspectIncompleteView(groupId, name).then(value => {
      if (active) {
        setView(value);
        setSelected(value.files[0]?.path ?? '');
      }
    }).catch(reason => {
      if (active) setError(reason instanceof Error ? reason.message : String(reason));
    });
    return () => { active = false; };
  }, [groupId, name, attempt]);

  const file = view?.files.find(item => item.path === selected);
  const remove = async () => {
    if (!view || disabled || removing || error) return;
    setRemoving(true);
    try {
      await removeIncompleteView(groupId, name, view.revision);
      onRemoved();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
      setRemoving(false);
      setConfirming(false);
    }
  };

  return <EngineeringDialog title="检查未完成视图" context={name} onClose={onClose} canClose={() => !removing}
    footer={<>
      <span className="eng-message">{confirming ? '将移除本草稿中的视图文件及上方列出的业务规则。' : '请先核对剩余文件与移除影响。'}</span>
      <div className="eng-buttons">
        <button type="button" className="da-btn da-btn-sm" disabled={removing} onClick={confirming ? () => setConfirming(false) : onClose}>
          {confirming ? '取消' : '返回草稿'}
        </button>
        <button type="button" className="da-btn da-btn-sm" disabled={!view || disabled || removing || !!error}
          onClick={confirming ? () => void remove() : () => setConfirming(true)}>
          {removing ? '正在移除…' : confirming ? '确认移除视图' : '移除未完成视图'}
        </button>
      </div>
    </>}>
    <div className="eng-doc-text">
      <p>视图「{name}」缺少 metadata.yml。你可以查看剩余文件，或确认后从当前草稿中移除这个视图。</p>
      {error && <div role="alert">{error} <button type="button" className="da-btn da-btn-sm"
        onClick={() => { setError(''); setView(null); setAttempt(value => value + 1); }}>重新读取</button></div>}
      {!view && !error && <p role="status">正在读取草稿文件及引用…</p>}
      {view && <>
        <h3>移除影响</h3>
        <p>将移除 {view.impact.rules.length} 条引用规则，涉及 {view.impact.questions.length} 道问答。</p>
        {!!view.impact.rules.length && <ul aria-label="将移除的业务规则">{view.impact.rules.map((rule, index) => <li key={index}>{rule}</li>)}</ul>}
        {!!view.impact.questions.length && <ul aria-label="引用该视图的问答">{view.impact.questions.map((question, index) => <li key={index}>{question}</li>)}</ul>}
        <h3>剩余草稿文件</h3>
        {file ? <>
          <select className="da-input" aria-label="选择草稿文件" value={selected} onChange={event => setSelected(event.target.value)}>
            {view.files.map(item => <option key={item.path} value={item.path}>{item.path.split('/').slice(2).join('/')}</option>)}
          </select>
          <ReadableCode key={file.path} text={file.content} title={file.path}
            language={file.path.endsWith('.yml') || file.path.endsWith('.yaml') ? 'yaml' : file.path.endsWith('.sql') ? 'sql' : 'text'} />
        </> : <p>目录中没有可显示的文件。</p>}
      </>}
    </div>
  </EngineeringDialog>;
}
