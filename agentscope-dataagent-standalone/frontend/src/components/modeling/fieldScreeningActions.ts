import { getScreeningStatus, getScreeningTable, saveScreening } from '../../api/fieldScreening';
import type { ScreeningField, ScreeningStatus, ScreeningTable } from '../../api/fieldScreening';

export const needsFieldReview = (field: ScreeningField) => field.role === 'UNKNOWN' && !field.userDescribed;

export function canConfirmTable(table: ScreeningTable) {
  return table.fields.length > 0 && table.fields.every(field => field.included && !needsFieldReview(field));
}

/** Recheck the visible scope before writing, skipping tables saved by a previous attempt. */
export async function confirmScreeningSelection(groupId: string, visible: ScreeningStatus[], active: () => boolean) {
  const current = await getScreeningStatus(groupId);
  const expected = new Map(visible.map(table => [table.datasetId, table]));
  const pending = current.filter(table => !table.confirmed);
  if (current.length !== expected.size || current.some(table => expected.get(table.datasetId)?.total !== table.total)) {
    throw new Error('数据表或字段已变化，请核对最新范围后再确认。');
  }

  for (const table of pending) {
    if (!active()) return;
    const fields = await getScreeningTable(groupId, table.datasetId);
    if (!canConfirmTable(fields) || fields.fields.length !== table.total) {
      throw new Error(`「${table.name}」的字段范围需要重新核对。`);
    }
  }
  for (const table of pending) {
    if (!active()) return;
    const result = await saveScreening(groupId, table.datasetId, [], true);
    if (!result.table.confirmed || result.references.length) {
      throw new Error(`「${table.name}」尚未确认，请重新核对。`);
    }
  }
}
