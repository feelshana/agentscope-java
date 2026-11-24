import type { DraftChange } from '../../api/draftReview';

const categories: Record<DraftChange['category'], string> = {
  MODEL: '模型', RELATIONSHIP: '关系', RULE: '业务规则', CAVEAT: '注意事项',
  DOCUMENT: '业务文档', EXAMPLE: '问答', SAMPLE: '采样资料', OTHER: '工程文件',
};

export function draftChangeGroups(changes: readonly DraftChange[], questionTitles?: ReadonlyMap<string, string>) {
  const grouped = new Map<string, { key: string; category: string; title: string; files: DraftChange[] }>();
  for (const change of changes) {
    const { path, category } = change;
    const asset = /^(views|models|cubes)\/([^/]+)\//.exec(path);
    const question = /^knowledge\/(?:questions|sql)\/([^/]+)\.(?:ya?ml|md)$/.exec(path);
    const key = asset ? `${asset[1]}/${asset[2]}` : question ? `question:${question[1]}` : path;
    const existing = grouped.get(key);
    if (existing) {
      existing.files.push(change);
      continue;
    }
    grouped.set(key, {
      key,
      category: asset?.[1] === 'views' ? '公共视图' : asset?.[1] === 'cubes' ? '立方体' : categories[category],
      title: asset?.[2] ?? (question && (questionTitles?.get(question[1]) ?? question[1])) ?? path.slice(path.lastIndexOf('/') + 1),
      files: [change],
    });
  }
  return [...grouped.values()];
}
