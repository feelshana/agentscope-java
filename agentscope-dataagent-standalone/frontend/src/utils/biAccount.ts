/** BI account fields are distinct from the Chinese display name. */
export function readBiAccount(storage: Pick<Storage, 'getItem'>): string {
  const username = storage.getItem('username')?.trim();
  const account = storage.getItem('account')?.trim();
  if (username && account && username.toLowerCase() !== account.toLowerCase())
    throw new Error('分析云账号信息不一致，请重新登录分析云。');
  const value = username || account;
  if (!value) throw new Error('未读取到分析云账号，请确认从同源分析云入口访问。');
  return value;
}
