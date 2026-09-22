/**
 * Extraction of Vega-Lite specs from tool-call inputs.
 *
 * The `render_chart` tool receives its full argument map through the SSE
 * `tool_call` event (before the tool even runs), so the chat stream can render
 * the chart client-side as soon as the spec arrives. The agent passes
 * `vega_lite_spec` as a JSON string; historical turns may already store it as
 * an object — both shapes are accepted here, plus a few leniency fallbacks for
 * what LLMs actually produce:
 *
 * <ul>
 *   <li>markdown code fences wrapping the spec (```json … ```)</li>
 *   <li>alternative argument names (`spec`, `vega_spec`)</li>
 *   <li>plain Vega specs (`marks`/`datasets`) alongside Vega-Lite (`mark`/`data`)</li>
 *   <li>stray text around the JSON object — the outermost braces are extracted</li>
 * </ul>
 */

/** A minimal Vega/Vega-Lite spec: an object that declares a mark layer and data. */
export type VegaSpec = Record<string, unknown>;

function asSpec(candidate: unknown): VegaSpec | null {
  if (typeof candidate !== 'object' || candidate === null || Array.isArray(candidate)) {
    return null;
  }
  const spec = candidate as Record<string, unknown>;
  const hasData = 'data' in spec || 'datasets' in spec;
  // Layered / faceted compositions carry their marks inside `layer` (or rely on
  // `encoding` alone), so the top-level `mark` check alone would reject them.
  const hasMark =
    'mark' in spec || 'marks' in spec || 'layer' in spec || 'encoding' in spec;
  return hasData && hasMark ? spec : null;
}

/** Strips a surrounding markdown code fence (```json … ``` / ``` … ```). */
function stripFences(text: string): string {
  const t = text.trim();
  const fence = t.match(/^```[a-zA-Z]*\s*([\s\S]*?)\s*```$/);
  return fence ? fence[1].trim() : t;
}

/**
 * Best-effort JSON parse: direct parse first, then the outermost `{ … }`
 * slice when the text carries stray prose or fences around the object.
 * Non-strings (already-parsed objects) pass through untouched.
 */
function looseParse(raw: unknown): unknown {
  if (typeof raw !== 'string') {
    return raw;
  }
  const t = stripFences(raw);
  try {
    return JSON.parse(t);
  } catch {
    const first = t.indexOf('{');
    const last = t.lastIndexOf('}');
    if (first >= 0 && last > first) {
      try {
        return JSON.parse(t.slice(first, last + 1));
      } catch {
        return null;
      }
    }
    return null;
  }
}

/** Argument names the agent may use to carry the spec, most specific first. */
const SPEC_KEYS = ['vega_lite_spec', 'vega_spec', 'spec'];

/**
 * Returns the Vega-Lite spec embedded in a `render_chart` tool input, or `null`
 * when the tool is not a chart call or the input does not carry a valid spec.
 *
 * @param toolName tool invoked by the agent (e.g. `render_chart`)
 * @param input JSON string of the tool's argument map
 */
export function extractVegaSpec(toolName: string, input?: string): VegaSpec | null {
  if (!input) {
    return null;
  }
  if (!toolName.toLowerCase().includes('render_chart')) {
    return null;
  }
  const args = looseParse(input);
  if (typeof args !== 'object' || args === null) {
    return null;
  }
  const map = args as Record<string, unknown>;
  for (const key of SPEC_KEYS) {
    if (!(key in map)) {
      continue;
    }
    const spec = looseParse(map[key]);
    if (spec !== null) {
      const ok = asSpec(spec);
      if (ok) {
        return ok;
      }
    }
  }
  return null;
}
