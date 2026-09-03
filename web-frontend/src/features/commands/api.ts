import { api } from '../../api/client'
import { commandListKey, issueCommandKey } from './model'
import type {
  CommandEnqueueResult,
  CommandListFilters,
  CommandPage,
  CommandView,
  IssueCommandRequest,
} from './types'

type BackendCommandPage = Partial<CommandPage> & { content?: CommandView[] }

const listRequests = new Map<string, Promise<CommandPage>>()
const detailRequests = new Map<number, Promise<CommandView>>()
const issueRequests = new Map<string, Promise<CommandEnqueueResult>>()

function dedupe<T>(store: Map<string | number, Promise<T>>, key: string | number, request: () => Promise<T>) {
  const existing = store.get(key)
  if (existing) return existing
  const pending = request()
  store.set(key, pending)
  const clear = () => { if (store.get(key) === pending) store.delete(key) }
  pending.then(clear, clear)
  return pending
}

function normalizePage(payload: BackendCommandPage, fallbackPage: number, fallbackSize: number): CommandPage {
  const content = Array.isArray(payload.content) ? payload.content : []
  return {
    content,
    totalElements: payload.totalElements ?? content.length,
    totalPages: payload.totalPages ?? (content.length ? 1 : 0),
    size: payload.size ?? fallbackSize,
    number: payload.number ?? fallbackPage,
    first: payload.first ?? fallbackPage === 0,
    last: payload.last ?? true,
    numberOfElements: payload.numberOfElements ?? content.length,
    empty: payload.empty ?? content.length === 0,
  }
}

export function getCommands(filters: CommandListFilters) {
  const key = commandListKey(filters)
  return dedupe(listRequests, key, async () => {
    if (filters.taskId) {
      const command = await api.get<CommandView>(`/commands?taskId=${filters.taskId}`)
      return normalizePage({ content: [command], totalElements: 1, totalPages: 1 }, 0, 1)
    }
    const params = new URLSearchParams({
      status: filters.statuses.join(','),
      page: String(Math.max(0, filters.page)),
      size: String(Math.max(1, filters.size)),
    })
    const payload = await api.get<BackendCommandPage>(`/commands?${params.toString()}`)
    return normalizePage(payload, filters.page, filters.size)
  })
}

export function getCommand(taskId: number) {
  return dedupe(detailRequests, taskId, () => api.get<CommandView>(`/commands?taskId=${taskId}`))
}

export function issueCommand(request: IssueCommandRequest) {
  const key = issueCommandKey(request)
  return dedupe(issueRequests, key, () => api.post<CommandEnqueueResult>('/commands', request))
}

export function cancelCommand(taskId: number) {
  return api.post<{ taskId: number; status: 'CANCELLED'; alreadyCancelled?: boolean }>(
    `/commands/${taskId}/cancel`,
  )
}

export function deleteCommandHistory(taskId: number) {
  return api.delete<{ taskId: number; deleted: true }>(`/commands/${taskId}`)
}
