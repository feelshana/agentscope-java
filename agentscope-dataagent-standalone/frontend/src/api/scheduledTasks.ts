import { getToken } from './auth';

export interface ScheduledTask {
  id: string;
  title: string;
  prompt: string;
  email: string | null;
  knowledgeBaseId: string | null;
  agentId: string;
  scheduleFrequency: string;
  scheduleTime: string;
  effectiveFrom: string;
  status: string;
  runCount: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface ScheduledTaskRequest {
  title: string;
  prompt: string;
  email?: string;
  knowledgeBaseId?: string;
  agentId: string;
  scheduleFrequency: string;
  scheduleTime: string;
  effectiveFrom?: string;
}

export interface StatusUpdateRequest {
  status: string;
}

function jsonHeaders(): Record<string, string> {
  const token = getToken();
  return {
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

async function errorMessage(res: Response, fallback: string): Promise<string> {
  try {
    const body = (await res.json()) as { message?: string };
    return body.message || fallback;
  } catch {
    return fallback;
  }
}

export async function listScheduledTasks(): Promise<ScheduledTask[]> {
  const res = await fetch('/api/scheduled-tasks', { headers: jsonHeaders() });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to list tasks: ${res.status}`));
  return res.json();
}

export async function createScheduledTask(req: ScheduledTaskRequest): Promise<ScheduledTask> {
  const res = await fetch('/api/scheduled-tasks', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to create task: ${res.status}`));
  return res.json();
}

export async function updateScheduledTask(
  id: string,
  req: ScheduledTaskRequest,
): Promise<ScheduledTask> {
  const res = await fetch(`/api/scheduled-tasks/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to update task: ${res.status}`));
  return res.json();
}

export async function updateTaskStatus(id: string, status: string): Promise<ScheduledTask> {
  const res = await fetch(`/api/scheduled-tasks/${encodeURIComponent(id)}/status`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify({ status }),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to update status: ${res.status}`));
  return res.json();
}

export async function deleteScheduledTask(id: string): Promise<void> {
  const res = await fetch(`/api/scheduled-tasks/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: jsonHeaders(),
  });
  if (!res.ok) throw new Error(await errorMessage(res, `Failed to delete task: ${res.status}`));
}
