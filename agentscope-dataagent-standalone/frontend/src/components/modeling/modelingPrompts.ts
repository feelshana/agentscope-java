import type { MdlModelView, MdlView } from '../../api/semanticModeling';
import type { IconName } from '../Icon';

export type ModelingPrompt = { title: string; detail: string; icon: IconName; text: string };

const modelName = (model: MdlModelView) => model.datasetName || model.modelName;

/** Use the current draft's existing metadata to make the prompts specific to this knowledge base. */
export function getModelingPrompts(view: MdlView | null): ModelingPrompt[] {
  const models = view?.models ?? [];
  const undocumented = models.find(model => model.columns.some(column => !column.relationship && !column.description?.trim()));
  const model = undocumented ?? models[0];
  const focus = model ? `「${modelName(model)}」` : '当前模型';
  const missing = undocumented?.columns.filter(column => !column.relationship && !column.description?.trim())
    .slice(0, 2).map(column => column.displayName || column.name);
  const relation = view?.relations[0];
  const relationLabel = relation && [relation.leftModel, relation.rightModel]
    .map(name => models.find(entry => entry.modelName === name)?.datasetName || name).join(' → ');
  const publicView = view?.views[0];

  return [
    {
      title: missing?.length ? '补齐字段说明' : relationLabel ? '核对表间关系' : '检查模型口径',
      icon: 'model',
      detail: missing?.length ? `${focus} · ${missing.join('、')}` : relationLabel ?? '字段含义、表角色和业务规则',
      text: missing?.length
        ? `请检查${focus}中「${missing.join('、')}」的业务含义、单位和统计口径。先根据已有规则和资料列出可补充的说明，无法确定的内容请问我。`
        : relationLabel
          ? `请核对「${relationLabel}」的关联字段和基数，说明哪些统计可能受重复计数影响。请依据已有模型和资料给出改进建议，先不修改草稿。`
          : `请检查${focus}的字段含义、表角色和业务规则，列出最值得完善的两三项，以及需要我补充的信息。先给建议。`,
    },
    {
      title: '校对问数口径',
      icon: 'chat',
      detail: model ? `围绕${focus}设计试问题` : '挑出容易问错的问题',
      text: `请基于${focus}和已确认的业务规则，推荐两条值得试问的问题。说明预期使用的指标、时间范围和过滤条件，并指出哪些口径最容易理解错。先给建议。`,
    },
    {
      title: publicView ? '核对公共视图' : '梳理公共视图',
      icon: 'table',
      detail: publicView ? `「${publicView.name}」的用途和统计粒度` : '找出适合复用的一组字段',
      text: publicView
        ? `请检查公共视图「${publicView.name}」适合哪些问数场景，说明它的统计粒度、过滤范围和可能重复计数的情况。先给建议，不修改视图。`
        : `基于现有模型和关系，帮我判断是否有适合复用的公共视图，优先考虑${focus}。请说明用途、建议字段和统计粒度，先不创建。`,
    },
    {
      title: '核对业务资料',
      icon: 'file',
      detail: '对照资料、字段含义和统计口径',
      text: `请对照本知识库已有的业务资料和${focus}，找出定义不一致、单位不明或仍缺依据的地方，列出需要我补充的资料。如果没有业务文档，请说明最有帮助的资料类型。`,
    },
  ];
}
