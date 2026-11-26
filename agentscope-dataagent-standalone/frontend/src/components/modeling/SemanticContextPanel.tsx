import { useMemo, useState } from 'react';
import type { MdlPreview } from '../../api/semanticModeling';
import type { QuestionReview } from '../../api/modelingQuestions';
import CompiledMdlPanel from './CompiledMdlPanel';
import EngineeringDialog, { EngineeringVersions } from './EngineeringDialog';
import type { EngineeringVersion } from './EngineeringDialog';
import EngineeringDirectory from './EngineeringDirectory';
import EngineeringRules from './EngineeringRules';
import ReadableCode from '../ReadableCode';

const tabs = [['rules', '业务规则'], ['mdl', '编译 MDL'], ['examples', '问答示例']] as const;
type Tab = typeof tabs[number][0];

export default function SemanticContextPanel({ groupId, question, preview, reviews, onClose }: {
  groupId: string; question: string; preview: MdlPreview | null; reviews: QuestionReview[]; onClose: () => void;
}) {
  const [version, setVersion] = useState<EngineeringVersion>('draft');
  const [tab, setTab] = useState<Tab>('rules');
  const [openedMdl, setOpenedMdl] = useState(false);
  const [selected, setSelected] = useState('');
  const examples = useMemo(() => reviews.filter(r => r.status === 'CONFIRMED' && (version === 'draft' || r.published?.current)), [reviews, version]);
  const labels = useMemo(() => new Map(examples.map(r => [r.question.id, r.question.question])), [examples]);
  const example = examples.find(r => r.question.id === selected) ?? examples[0];
  return <EngineeringDialog title="核对规则与版本" context={question} onClose={onClose}
    toolbar={<><div className="eng-tabs" role="group" aria-label="核对内容">
      {tabs.map(([key, label]) => <button type="button" key={key} aria-pressed={tab === key} onClick={() => { setTab(key); if (key === 'mdl') setOpenedMdl(true); }}>{label}</button>)}
    </div><EngineeringVersions version={version} publishedVersion={preview?.mdlVersion} onChange={setVersion} /></>}
    footer={<span>展示该版本的知识库上下文。{tab === 'mdl' && version === 'draft' && '编译 MDL 来自草稿最近一次成功构建。'}</span>}>
    <div className="eng-tab-view" hidden={tab !== 'rules'}><EngineeringRules preview={preview} version={version} /></div>
    {openedMdl && <div className="eng-tab-view" hidden={tab !== 'mdl'}><CompiledMdlPanel groupId={groupId} version={version} /></div>}
    {tab === 'examples' && (!example ? <p className="am-empty">当前版本没有已确认的问答示例。</p> : <div className="eng-browser">
      <EngineeringDirectory paths={[...labels.keys()]} labels={labels} current={example.question.id} onSelect={setSelected} />
      <div className="eng-document"><ReadableCode key={example.question.id} fill language="sql" text={example.question.sql} title={example.question.question} /></div>
    </div>)}
  </EngineeringDialog>;
}
