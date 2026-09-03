import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import { cancelCommand, deleteCommandHistory, getCommand, getCommands, issueCommand } from './api'
import type { CommandView, IssueCommandRequest } from './types'

vi.mock('../../api/client', () => ({
  api: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
}))

const mockedApi = vi.mocked(api)

function command(taskId: number): CommandView {
  return { taskId, macAddress: 'AA:BB:CC:DD:EE:FF', commandType: 'SHUTDOWN', status: 'QUEUED' }
}

describe('commands api', () => {
  beforeEach(() => vi.clearAllMocks())

  it('uses the backend status and paging contract', async () => {
    mockedApi.get.mockResolvedValue({ content: [command(7)], totalElements: 1, totalPages: 1, number: 2, size: 50 })
    const result = await getCommands({ statuses: ['QUEUED', 'FAILED'], page: 2, size: 50 })
    expect(mockedApi.get).toHaveBeenCalledWith('/commands?status=QUEUED%2CFAILED&page=2&size=50')
    expect(result.content[0].taskId).toBe(7)
    expect(result.number).toBe(2)
  })

  it('normalizes the taskId detail response as a single-item page', async () => {
    mockedApi.get.mockResolvedValue(command(42))
    const result = await getCommands({ statuses: ['COMPLETED'], taskId: 42, page: 9, size: 100 })
    expect(mockedApi.get).toHaveBeenCalledWith('/commands?taskId=42')
    expect(result).toMatchObject({ totalElements: 1, totalPages: 1, number: 0, size: 1 })
  })

  it('de-duplicates concurrent list and detail reads', async () => {
    let resolveList!: (value: { content: CommandView[] }) => void
    const listPending = new Promise<{ content: CommandView[] }>((resolve) => { resolveList = resolve })
    mockedApi.get.mockReturnValueOnce(listPending).mockResolvedValueOnce(command(8))
    const filters = { statuses: ['QUEUED'] as const, page: 0, size: 20 }
    const first = getCommands(filters)
    const second = getCommands(filters)
    expect(mockedApi.get).toHaveBeenCalledTimes(1)
    resolveList({ content: [command(8)] })
    await Promise.all([first, second])

    const detailFirst = getCommand(8)
    const detailSecond = getCommand(8)
    expect(mockedApi.get).toHaveBeenCalledTimes(2)
    await Promise.all([detailFirst, detailSecond])
  })

  it('de-duplicates concurrent command submissions and releases the key after completion', async () => {
    let resolveIssue!: (value: { queued: number }) => void
    const issuePending = new Promise<{ queued: number }>((resolve) => { resolveIssue = resolve })
    mockedApi.post.mockReturnValueOnce(issuePending).mockResolvedValueOnce({ queued: 1 })
    const request: IssueCommandRequest = { scope: 'SINGLE', type: 'SHUTDOWN', macAddress: 'AA:BB:CC:DD:EE:FF' }
    const first = issueCommand(request)
    const second = issueCommand({ ...request })
    expect(mockedApi.post).toHaveBeenCalledTimes(1)
    resolveIssue({ queued: 1 })
    await Promise.all([first, second])
    await issueCommand(request)
    expect(mockedApi.post).toHaveBeenCalledTimes(2)
  })

  it('cancels a queued command through the task endpoint', async () => {
    mockedApi.post.mockResolvedValue({ taskId: 42, status: 'CANCELLED' })
    await cancelCommand(42)
    expect(mockedApi.post).toHaveBeenCalledWith('/commands/42/cancel')
  })

  it('deletes one completed command history entry', async () => {
    mockedApi.delete.mockResolvedValue({ taskId: 42, deleted: true })
    await deleteCommandHistory(42)
    expect(mockedApi.delete).toHaveBeenCalledWith('/commands/42')
  })
})
