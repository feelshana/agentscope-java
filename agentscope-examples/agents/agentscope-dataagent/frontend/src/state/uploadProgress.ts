/**
 * 模块级上传进度 store：跨页面挂载/卸载保留每个知识库的上传/解析状态。
 * 组件 state 会在离开知识库页时被卸载带走，导致"处理中"的文件列表消失；
 * 这里把状态提升到模块单例，返回页面时可继续看到进行中的上传。
 */
export interface UploadStatus {
  name: string;
  status: 'queued' | 'uploading' | 'ready' | 'failed';
  error?: string;
  file?: File;
  selectedSheet?: string;
}

const EMPTY: UploadStatus[] = [];
const byGroup = new Map<string, UploadStatus[]>();
const listeners = new Set<() => void>();

function emit() {
  listeners.forEach(l => l());
}

export function getUploadStatuses(groupId: string): UploadStatus[] {
  return byGroup.get(groupId) ?? EMPTY;
}

export function mutateUploadStatuses(
  groupId: string,
  fn: (prev: UploadStatus[]) => UploadStatus[],
) {
  byGroup.set(groupId, fn(byGroup.get(groupId) ?? []));
  emit();
}

export function subscribeUploadStatuses(fn: () => void): () => void {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}
