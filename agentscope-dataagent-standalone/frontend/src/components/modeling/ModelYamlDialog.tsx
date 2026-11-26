import { useDeferredValue, useMemo, useState } from 'react';
import { editDraftFile } from '../../api/draftReview';
import { diffLines } from '../../utils/diff';
import ReadableCode from '../ReadableCode';
import EngineeringDialog from './EngineeringDialog';
import SourceFileEditor from './SourceFileEditor';

export default function ModelYamlDialog({ groupId, name, path, source, publishedSource, editable, canEdit, startEditing, onClose, onChanged }: {
  groupId: string; name: string; path: string; source: string; publishedSource?: string;
  editable: boolean; canEdit: boolean; startEditing: boolean; onClose: () => void; onChanged: () => void;
}) {
  const [edit, setEdit] = useState(() => startEditing && canEdit ? { base: source, value: source } : null);
  const [saved, setSaved] = useState<{ base: string; value: string } | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const current = saved?.base === source ? saved.value : source;
  const changed = !!edit && edit.value !== edit.base;
  const stale = !!edit && edit.base !== current;
  const errorLine = Number(/第\s*(\d+)\s*行/.exec(error)?.[1]) || undefined;
  const confirmLeave = () => !busy && (!changed || window.confirm('放弃尚未保存的 YAML 修改？'));
  const before = edit ? edit.base : publishedSource;
  const after = useDeferredValue(edit?.value ?? current);
  const difference = useMemo(() => {
    if (before === undefined || before === after) return [];
    const original = before.replace(/\r\n/g, '\n');
    const revised = after.replace(/\r\n/g, '\n');
    const originalCount = original.split('\n').length;
    const revisedCount = revised.split('\n').length;
    if (originalCount * revisedCount > 2_000_000 || originalCount + revisedCount > 4_000) return null;
    let oldLine = 0, newLine = 0;
    return diffLines(original, revised).flatMap(line => {
      if (line.type !== 'add') oldLine++;
      if (line.type !== 'del') newLine++;
      return line.type === 'context' ? [] : [{ ...line, number: line.type === 'add' ? newLine : oldLine }];
    });
  }, [before, after]);
  const save = async () => {
    if (!edit || !changed || stale || !canEdit || busy) return;
    setBusy(true); setError('');
    try {
      await editDraftFile(groupId, path, edit.value);
      setSaved({ base: source, value: edit.value });
      setEdit(null);
      onChanged();
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  };
  return <EngineeringDialog title="模型 YAML" context={name} onClose={onClose} canClose={confirmLeave} initiallyExpanded
    footer={<><span className="eng-message" role="status">{edit ? 'Wren 校验通过后保存到草稿。'
      : saved ? '已通过 Wren 校验并保存到草稿。' : editable ? '当前草稿' : '线上版本 · 只读'}</span>
      {editable && <div className="eng-buttons">{edit
        ? <><button type="button" className="da-btn da-btn-sm" disabled={busy} onClick={() => { if (confirmLeave()) { setEdit(null); setError(''); } }}>取消编辑</button>
          <button type="button" className="da-btn da-btn-primary da-btn-sm" disabled={!canEdit || busy || !changed || stale} onClick={() => void save()}>
            {busy ? 'Wren 校验中…' : '校验并保存到草稿'}</button></>
        : <button type="button" className="da-btn da-btn-primary da-btn-sm" disabled={!canEdit} onClick={() => setEdit({ base: current, value: current })}>编辑草稿</button>}
      </div>}
    </>}>
    <div className="eng-compare sw-yaml-compare">
      <div className="eng-document">
        {edit ? <><div className="eng-filebar"><strong title={path}>{path}</strong></div>
          <SourceFileEditor label="编辑 YAML" value={edit.value} onChange={value => {
            setEdit({ ...edit, value }); setError('');
          }} errorLine={errorLine} disabled={busy || !canEdit} /></>
          : <ReadableCode fill text={current} title={path} />}
      </div>
      <section className="eng-document" aria-label="YAML 行差异">
        <h3>{edit ? '与编辑前草稿对比' : '与线上版本对比'}</h3>
        {error && <p role="alert" className="eng-inline-error">{error}</p>}
        {stale && <p role="alert" className="eng-inline-error">源文件草稿已更新。请复制尚未保存的内容，再重新打开编辑。</p>}
        {before === undefined ? <p className="sw-yaml-note">{editable ? '线上版本没有这个文件。' : '当前显示的是线上原文。'}</p>
          : difference === null ? <p className="sw-yaml-note">文件行数过多，暂时无法显示差异。</p>
            : !difference.length ? <p className="sw-yaml-note">没有行差异。</p>
              : <pre className="sw-yaml-diff" tabIndex={0}>{difference.map(line => <span className={`sw-yaml-${line.type}`} key={`${line.type}:${line.number}`}>
                <span>{line.type === 'add' ? '+' : '−'} {line.number}</span>{line.text}{'\n'}
              </span>)}</pre>}
      </section>
    </div>
  </EngineeringDialog>;
}
