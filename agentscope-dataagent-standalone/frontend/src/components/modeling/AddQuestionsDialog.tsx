import { useEffect, useRef } from 'react';
import QuestionIntakeForm from './QuestionIntakeForm';

export default function AddQuestionsDialog({ onSubmit, onClose }: {
  onSubmit: (message: string) => void;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => { dialog.current?.showModal(); }, []);
  return <dialog ref={dialog} aria-label="添加分析问题" onCancel={onClose} className="da-card"
    style={{ width: 560, maxWidth: '92vw', maxHeight: '85vh', overflowY: 'auto', padding: 24, boxSizing: 'border-box' }}>
    <QuestionIntakeForm onCancel={onClose} onSubmit={message => { onSubmit(message); onClose(); }} />
  </dialog>;
}
