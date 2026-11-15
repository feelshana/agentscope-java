import { useState } from 'react';
import Icon from '../Icon';
import SqlQaExamplesPanel from './SqlQaExamplesPanel';

/** Mount on first expansion, then retain edits when the user folds the section. */
export default function ConfirmedExamplesSection({ groupId, initialOpen = false }: { groupId: string; initialOpen?: boolean }) {
  const [open, setOpen] = useState(initialOpen);
  const [activated, setActivated] = useState(initialOpen);
  return <section className="mw-question-library" aria-label="已确认问答"><details className="sm-advanced mw-confirmed-examples" open={open}
    onToggle={event => { setOpen(event.currentTarget.open); if (event.currentTarget.open) setActivated(true); }}>
    <summary><span className="mw-confirmed-icon" aria-hidden="true"><Icon name="book" /></span><span className="mw-confirmed-copy"><strong>全部已确认问答</strong><span>问数时作为示例召回</span></span><span className="mw-confirmed-action" aria-hidden="true">{open ? '收起' : '展开'}<Icon name="chevron" size="sm" /></span></summary>
    {activated && <SqlQaExamplesPanel key={groupId} groupId={groupId} />}
  </details></section>;
}
