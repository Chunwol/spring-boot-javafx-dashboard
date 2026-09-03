import { describe, expect, it } from 'vitest'
import {
  DEFAULT_COMMAND_DRAFT,
  commandDestinationDisplay,
  commandDisplayType,
  commandProgressSteps,
  commandRunMode,
  commandStatusLabel,
  commandStatusTone,
  commandTransferDisplay,
  canDeleteCommandHistory,
  detailedCommandStatus,
  filterAndSortCommands,
  collectDownloadFilename,
  collectDownloadPath,
  summarizeCommands,
  toIssueCommandRequest,
  validateCommandDraft,
} from './model'
import type { CommandDraft, CommandView } from './types'

function draft(overrides: Partial<CommandDraft> = {}): CommandDraft {
  return { ...DEFAULT_COMMAND_DRAFT, macAddress: 'AA:BB:CC:DD:EE:FF', ...overrides }
}

function command(overrides: Partial<CommandView> = {}): CommandView {
  return {
    taskId: 1,
    macAddress: 'AA:BB:CC:DD:EE:FF',
    commandType: 'SHUTDOWN',
    status: 'QUEUED',
    createdAt: '2026-08-07T10:00:00',
    ...overrides,
  }
}

describe('command model', () => {
  it('maps lifecycle states to operator labels and tones', () => {
    expect(commandStatusLabel('ACKED')).toBe('실행 중')
    expect(commandStatusTone('FAILED')).toBe('failed')
    expect(commandStatusLabel('UNKNOWN')).toBe('UNKNOWN')
  })

  it('requires a valid target for each scope', () => {
    expect(validateCommandDraft(draft({ macAddress: 'invalid' })).macAddress).toBeTruthy()
    expect(validateCommandDraft(draft({ macAddress: 'AA-BB-CC-DD-EE-FF' })).macAddress).toBeUndefined()
    expect(validateCommandDraft(draft({ scope: 'GROUP', groupId: '0' })).groupId).toBeTruthy()
    expect(validateCommandDraft(draft({ scope: 'GROUP', groupId: '218' }))).toEqual({})
  })

  it('validates command-specific JSON and script URLs', () => {
    expect(validateCommandDraft(draft({ type: 'RUN_CMD', payloadJson: '{"value":1}' })).payloadJson).toContain('cmd')
    expect(validateCommandDraft(draft({ type: 'RUN_PS1', scriptUrl: 'http://plain.example/script.ps1' })).scriptUrl).toContain('HTTPS')
    expect(validateCommandDraft(draft({ type: 'RUN_PS1', scriptUrl: '//evil.example/script.ps1' })).scriptUrl).toContain('HTTPS')
    expect(validateCommandDraft(draft({ type: 'RUN_PS1', scriptUrl: '/api/v1/scripts/42/download' }))).toEqual({})
  })

  it('requires a verified update payload and marks it as an admin operation', () => {
    const update = draft({
      type: 'AGENT_UPDATE',
      payloadJson: JSON.stringify({ downloadUrl: 'https://example.test/agent.jar', sha256: 'a'.repeat(64) }),
    })
    expect(validateCommandDraft(update)).toEqual({})
    expect(toIssueCommandRequest(update)).toMatchObject({ type: 'AGENT_UPDATE', isAdminOperation: true })
  })

  it('normalizes a group request without leaking single-target fields', () => {
    expect(toIssueCommandRequest(draft({ scope: 'GROUP', groupId: '218', role: 'STUDENT', broadcastIp: '192.168.1.255', type: 'WOL' }))).toEqual({
      scope: 'GROUP',
      type: 'WOL',
      groupId: 218,
      role: 'STUDENT',
      broadcastIp: '192.168.1.255',
    })
  })

  it('extracts only authenticated collection-result download paths', () => {
    expect(collectDownloadPath({
      commandType: 'COLLECT_FILES',
      stdout: '{"downloadUrl":"https://server.test/api/v1/agent/uploads/42/download"}',
    })).toBe('/agent/uploads/42/download')
    expect(collectDownloadPath({ commandType: 'RUN_CMD', stdout: '/api/v1/agent/uploads/42/download' })).toBeNull()
    expect(collectDownloadPath({ commandType: 'COLLECT_FILES', stdout: 'https://evil.test/file.zip' })).toBeNull()
  })

  it('uses a safe output name for collected files', () => {
    expect(collectDownloadFilename({ taskId: 7, payloadJson: '{"outputName":"room-218"}' })).toBe('room-218.zip')
    expect(collectDownloadFilename({ taskId: 7, payloadJson: 'invalid' })).toBe('collected-7.zip')
  })

  it('filters the 500-item snapshot by PC, room, status and multiple command types', () => {
    const items = [
      command({ taskId: 1, pcName: '218-01', groupName: '218호', commandType: 'SHUTDOWN', status: 'ACKED' }),
      command({ taskId: 2, pcName: '210-02', groupName: '210호', commandType: 'REBOOT', status: 'FAILED' }),
      command({ taskId: 3, pcName: '218-03', groupName: '218호', commandType: 'RUN_CMD', status: 'COMPLETED' }),
    ]
    const filtered = filterAndSortCommands(items, {
      search: '218',
      status: 'ALL',
      hiddenTypes: new Set(['RUN_CMD']),
      group: '218호',
      sort: 'NEWEST',
    })
    expect(filtered.map((item) => item.taskId)).toEqual([1])
  })

  it('supports all six operation sort modes with deterministic tie breakers', () => {
    const items = [
      command({ taskId: 1, pcName: 'PC-10', groupName: '218호', commandType: 'REBOOT', status: 'COMPLETED', createdAt: '2026-08-07T10:00:00' }),
      command({ taskId: 2, pcName: 'PC-2', groupName: '210호', commandType: 'SHUTDOWN', status: 'ACKED', createdAt: '2026-08-07T11:00:00' }),
    ]
    const base = { search: '', status: 'ALL' as const, hiddenTypes: new Set<string>(), group: 'ALL' }
    expect(filterAndSortCommands(items, { ...base, sort: 'NEWEST' }).map((item) => item.taskId)).toEqual([2, 1])
    expect(filterAndSortCommands(items, { ...base, sort: 'OLDEST' }).map((item) => item.taskId)).toEqual([1, 2])
    expect(filterAndSortCommands(items, { ...base, sort: 'GROUP' }).map((item) => item.taskId)).toEqual([2, 1])
    expect(filterAndSortCommands(items, { ...base, sort: 'PC' }).map((item) => item.taskId)).toEqual([2, 1])
    expect(filterAndSortCommands(items, { ...base, sort: 'STATUS' }).map((item) => item.taskId)).toEqual([2, 1])
    expect(filterAndSortCommands(items, { ...base, sort: 'TYPE' }).map((item) => item.taskId)).toEqual([1, 2])
  })

  it('counts verification and SCMATE waits separately from command lifecycle states', () => {
    const summary = summarizeCommands([
      command({ status: 'ACKED', verificationStatus: 'PENDING_ONLINE' }),
      command({ taskId: 2, status: 'COMPLETED', scmateResetStatus: 'PENDING' }),
      command({ taskId: 3, status: 'FAILED' }),
    ])
    expect(summary).toMatchObject({ acked: 1, completed: 1, failed: 1, verifying: 1, scmatePending: 1 })
  })

  it('allows history deletion only after command and follow-up work have settled', () => {
    expect(canDeleteCommandHistory(command({ status: 'COMPLETED' }))).toBe(true)
    expect(canDeleteCommandHistory(command({ status: 'FAILED' }))).toBe(true)
    expect(canDeleteCommandHistory(command({ status: 'CANCELLED' }))).toBe(true)
    expect(canDeleteCommandHistory(command({ status: 'ACKED' }))).toBe(false)
    expect(canDeleteCommandHistory(command({ status: 'COMPLETED', verificationStatus: 'PENDING_ONLINE' }))).toBe(false)
    expect(canDeleteCommandHistory(command({ status: 'COMPLETED', scmateResetStatus: 'PENDING' }))).toBe(false)
  })

  it('derives progress, detailed failures and transfer execution options', () => {
    const transfer = command({
      commandType: 'RUN_PS1',
      status: 'FAILED',
      stderr: 'first failure\nstack trace',
      payloadJson: JSON.stringify({
        transferMode: true,
        sourceType: 'folder',
        displayName: 'exam',
        destinationMode: 'CUSTOM',
        destinationPath: 'C:\\Exam',
        runAfterTransfer: true,
        runMode: 'BACKGROUND',
        windowMode: 'VISIBLE',
      }),
    })
    expect(commandDisplayType(transfer)).toBe('FILE_TX')
    expect(commandTransferDisplay(transfer)).toBe('폴더 · exam')
    expect(commandDestinationDisplay(transfer)).toBe('C:\\Exam')
    expect(commandRunMode(transfer)).toBe('전송 후 실행 · 즉시 실행 · 창 표시')
    expect(detailedCommandStatus(transfer)).toContain('first failure')
    expect(commandProgressSteps(transfer).at(-1)?.state).toBe('failed')
  })
})
