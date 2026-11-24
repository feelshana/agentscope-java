import { useMemo, useRef } from 'react';

export default function SourceFileEditor({ label, value, onChange, disabled }: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
}) {
  const gutter = useRef<HTMLDivElement>(null);
  const count = useMemo(() => value.split('\n').length, [value]);
  const numbers = useMemo(() => Array.from({ length: count }, (_, index) => index + 1).join('\n'), [count]);
  return <div className="eng-source-editor">
    <div className="eng-source-gutter" ref={gutter} aria-hidden="true"><pre>{numbers}</pre></div>
    <textarea autoFocus aria-label={label} spellCheck={false} autoCapitalize="off" autoComplete="off" wrap="off"
      disabled={disabled} value={value} onChange={event => onChange(event.currentTarget.value)}
      onScroll={event => { if (gutter.current) gutter.current.scrollTop = event.currentTarget.scrollTop; }} />
  </div>;
}
