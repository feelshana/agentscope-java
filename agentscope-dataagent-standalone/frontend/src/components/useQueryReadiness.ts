import { useEffect, useState } from 'react';
import { getQueryPreparation, preparationPhase } from '../api/modelingWorkflow';

/**
 * Whether the knowledge base's query engine is still preparing after a publish (about half a
 * minute). Returns the phase text while preparing, null when ready (or unknown), undefined until
 * the first answer. Polls while preparing and re-checks after every modeling update.
 */
export function useQueryReadiness(groupId: string | undefined, version?: number | null): string | null | undefined {
  const [result, setResult] = useState<{ groupId: string; version: number | null | undefined; phase: string | null }>();
  useEffect(() => {
    if (!groupId) return;
    let stopped = false;
    let sequence = 0;
    let phase: string | null | undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = async () => {
      if (timer) clearTimeout(timer);
      const request = ++sequence;
      try {
        const status = await getQueryPreparation(groupId);
        if (stopped || request !== sequence) return;
        if (version && status.version > 0 && status.version < version) {
          timer = setTimeout(() => void poll(), 2000);
          return;
        }
        phase = preparationPhase(status.state);
        setResult({ groupId, version, phase });
        if (phase) timer = setTimeout(() => void poll(), 2000);
      } catch {
        if (stopped || request !== sequence) return;
        // A failed poll cannot finish a preparation that the server had reported in progress.
        if (phase) timer = setTimeout(() => void poll(), 2000);
        else setResult({ groupId, version, phase: null });
      }
    };
    void poll();
    const update = (event: Event) => {
      if ((event as CustomEvent<{ groupId: string }>).detail?.groupId === groupId) void poll();
    };
    window.addEventListener('modeling:updated', update);
    return () => { stopped = true; if (timer) clearTimeout(timer); window.removeEventListener('modeling:updated', update); };
  }, [groupId, version]);
  return result && result.groupId === groupId && result.version === version ? result.phase : undefined;
}
