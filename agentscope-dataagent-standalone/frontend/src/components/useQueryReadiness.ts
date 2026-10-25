import { useEffect, useState } from 'react';
import { getQueryPreparation, preparationPhase } from '../api/modelingWorkflow';

/**
 * Whether the knowledge base's query engine is still preparing after a publish (about half a
 * minute). Returns the phase text while preparing, null when ready (or unknown), undefined until
 * the first answer. Polls while preparing and re-checks after every modeling update.
 */
export function useQueryReadiness(groupId: string | undefined, version?: number | null): string | null | undefined {
  const [phase, setPhase] = useState<string | null | undefined>(undefined);
  useEffect(() => {
    if (!groupId) return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = async () => {
      if (timer) clearTimeout(timer);
      try {
        const next = preparationPhase((await getQueryPreparation(groupId)).state);
        if (stopped) return;
        setPhase(next);
        if (next) timer = setTimeout(() => void poll(), 2000);
      } catch { if (!stopped) setPhase(null); }
    };
    void poll();
    const update = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) void poll();
    };
    window.addEventListener('modeling:updated', update);
    return () => { stopped = true; if (timer) clearTimeout(timer); window.removeEventListener('modeling:updated', update); };
  }, [groupId, version]);
  return phase;
}
