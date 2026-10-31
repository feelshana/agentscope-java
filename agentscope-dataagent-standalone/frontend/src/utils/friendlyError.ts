export type ErrorContext = 'chat' | 'datasource' | 'chart' | 'file';

/** Redact credentials before placing diagnostics in visible text or HTML attributes. */
export function redactError(value: string, secrets: string[] = []): string {
  let text = value;
  for (const secret of secrets.filter(Boolean)) text = text.split(secret).join('[已隐藏]');
  return text
    .replace(/\bBearer\s+[^\s"',;]+/gi, 'Bearer [已隐藏]')
    .replace(/((?:password|passwd|pwd|api[_-]?key|access[_-]?token|token|secret|authorization)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s&,;]+)/gi, '$1[已隐藏]')
    .replace(/((?:https?|mysql|postgresql):\/\/)[^\s/@:]+:[^\s/@]+@/gi, '$1[已隐藏]@')
    .replace(/\bsk-[a-zA-Z0-9_-]{12,}\b/g, '[已隐藏]')
    .replace(/\beyJ[a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+\b/g, '[已隐藏]');
}

export function friendlyError(raw: string, context: ErrorContext, secrets: string[] = []) {
  const detail = redactError(raw, secrets).trim();
  let summary: string;
  let action: string;
  if (/(?:(?:http|请求失败)\s*[:：/]?\s*401\b|unauthorized|登录.*(?:过期|失效)|未登录|token.*expired)/i.test(detail)) {
    summary = '登录状态已失效'; action = '请从原入口重新登录后再试。';
  } else if (/(?:(?:http|请求失败)\s*[:：/]?\s*403\b|forbidden|权限不足|没有.*权限|无权|permission denied)/i.test(detail)) {
    summary = '当前账号没有访问权限'; action = '请联系知识库或数据源管理员确认授权。';
  } else if (context === 'datasource' && /access denied|password authentication failed|authentication failed|密码.*错误|认证失败/i.test(detail)) {
    summary = '数据库拒绝了登录'; action = '请核对用户名、密码，以及数据库对应用服务器的连接授权。';
  } else if (context === 'datasource' && /communications link failure|connection refused|no route to host|unknownhost|unknown host|connect.*timed? ?out|连接.*(?:拒绝|超时|失败)|无法连接/i.test(detail)) {
    summary = '暂时无法连接数据库'; action = '请确认应用服务器能访问填写的主机和端口，并检查数据库是否允许连接。';
  } else if (context === 'datasource' && /unsupported.*version|version.*not supported|版本.*不支持|需要.*MySQL.*8/i.test(detail)) {
    summary = '数据库版本暂不兼容'; action = '请核对当前支持的数据库版本及接入方式。';
  } else if (/too many requests|http\s*[:/]?\s*429\b|队列已满|排队.*超时|请求过多|系统繁忙/i.test(detail)) {
    summary = '当前使用人数较多'; action = '请稍等片刻再试。';
  } else if (/http\s*[:/]?\s*5\d{2}\b/i.test(detail)) {
    summary = '服务暂时无法响应'; action = '请稍后重试；已返回的内容可继续查看。';
  } else if (/timeout|timed? ?out|超时|耗时.*过长/i.test(detail)) {
    summary = '这次处理时间较长，未能完成'; action = context === 'chat' ? '可以缩小日期或数据范围后重试；已返回的内容请先核对。' : '请稍后重试，并检查服务连接是否正常。';
  } else if (context === 'file' && /http\s*[:/]?\s*404\b|not found|不存在|已过期/i.test(detail)) {
    summary = '这个文件暂时无法读取'; action = '文件可能已失效或不可访问，请重新打开会话核对，必要时重新生成。';
  } else if (/failed to fetch|networkerror|network error|connection reset|连接.*中断|网络.*(?:异常|失败)/i.test(detail)) {
    summary = '连接暂时中断'; action = '请检查网络后重试；已返回的内容会保留。';
  } else {
    const defaults = {
      chat: ['本次回答未能完成', '请稍后重试；如果仍然失败，可展开技术详情联系管理员。'],
      datasource: ['本次连接操作未能完成', '请核对连接配置；如果仍然失败，可展开技术详情联系管理员。'],
      chart: ['图表暂时无法显示', '可以先查看已有结果，或展开技术详情核对原因。'],
      file: ['文件暂时无法打开或下载', '请稍后重试；如果仍然失败，可展开技术详情联系管理员。'],
    } as const;
    [summary, action] = defaults[context];
    // Preserve concise business validation messages instead of replacing them with generic text.
    if (/[\u4e00-\u9fff]/.test(raw) && detail.length <= 140 && !/[\r\n]|exception|traceback|jdbc:|https?:\/\/|http\s*[:/]?\s*\d{3}\b|password\s*[:=]|api[_-]?key\s*[:=]|\bat\s+[\w.]+\(/i.test(raw)) {
      summary = detail; action = '';
    }
  }
  return { summary, action, detail: detail !== summary ? detail : '' };
}
