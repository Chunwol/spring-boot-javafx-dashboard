import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../api/client'
import type { PcStatus } from '../../types/dashboard'
import { sendPcCommands, sendScmateAction, sendScmateActions } from './api'

vi.mock('../../api/client', () => ({
  api: { get: vi.fn(), post: vi.fn() },
}))

const mockedPost = vi.mocked(api.post)

function pc(macAddress: string, groupId: number): PcStatus {
  return { macAddress, groupId, pcName: macAddress, online: true, status: 'ONLINE' }
}

describe('sendPcCommands', () => {
  beforeEach(() => mockedPost.mockReset())

  it('queues shutdown for every target with one MULTI request', async () => {
    mockedPost.mockResolvedValue({ queued: 2, duplicateSkipped: 0 })
    const targets = [pc('AA:BB:CC:DD:EE:01', 1), pc('AA:BB:CC:DD:EE:02', 2)]

    const summary = await sendPcCommands(targets, 'SHUTDOWN')

    expect(mockedPost).toHaveBeenCalledTimes(1)
    expect(mockedPost).toHaveBeenCalledWith('/commands', {
      scope: 'MULTI',
      targetMacs: targets.map((target) => target.macAddress),
      type: 'SHUTDOWN',
    })
    expect(summary).toMatchObject({ requested: 2, queued: 2, failed: 0 })
  })

  it('groups WOL by classroom and counts every target in a failed group', async () => {
    mockedPost
      .mockResolvedValueOnce({ queued: 1, acceptedTargets: 2, duplicateTargets: 0, failedTargets: 0 })
      .mockRejectedValueOnce(new Error('network'))
    const targets = [
      pc('AA:BB:CC:DD:EE:01', 1),
      pc('AA:BB:CC:DD:EE:02', 1),
      pc('AA:BB:CC:DD:EE:03', 2),
      pc('AA:BB:CC:DD:EE:04', 2),
    ]

    const summary = await sendPcCommands(targets, 'PCON')

    expect(mockedPost).toHaveBeenCalledTimes(2)
    expect(summary).toMatchObject({ requested: 4, queued: 2, failed: 2 })
  })

  it('posts a SCMATE action with the exact backend contract', async () => {
    mockedPost.mockResolvedValue({ message: 'accepted' })

    await sendScmateAction('AA:BB:CC:DD:EE:01', 'RESTORE_BASELINE')

    expect(mockedPost).toHaveBeenCalledWith('/scmate/actions', {
      macAddress: 'AA:BB:CC:DD:EE:01',
      action: 'RESTORE_BASELINE',
      recoveryPoint: 'BASELINE',
    })
  })

  it('passes an explicitly selected recovery point', async () => {
    mockedPost.mockResolvedValue({ message: 'accepted' })

    await sendScmateAction('AA:BB:CC:DD:EE:01', 'UPDATE_BASELINE', 'SNAP2')

    expect(mockedPost).toHaveBeenCalledWith('/scmate/actions', {
      macAddress: 'AA:BB:CC:DD:EE:01',
      action: 'UPDATE_BASELINE',
      recoveryPoint: 'SNAP2',
    })
  })

  it('keeps API error messages for every failed classroom target', async () => {
    mockedPost.mockResolvedValue({
      requested: 2,
      queued: 1,
      failed: 1,
      results: [
        { macAddress: 'AA:BB:CC:DD:EE:01', accepted: true },
        { macAddress: 'AA:BB:CC:DD:EE:02', accepted: false, error: '라이선스가 만료되었습니다.' },
      ],
    })
    const targets = [pc('AA:BB:CC:DD:EE:01', 1), pc('AA:BB:CC:DD:EE:02', 1)]

    const summary = await sendScmateActions(targets, 'REQUEST_PROTECTED', 'SNAP1')

    expect(summary).toEqual({
      requested: 2,
      queued: 1,
      failed: 1,
      errors: [{ pcName: 'AA:BB:CC:DD:EE:02', message: '라이선스가 만료되었습니다.' }],
    })
    expect(mockedPost).toHaveBeenCalledTimes(1)
    expect(mockedPost).toHaveBeenCalledWith('/scmate/actions/bulk', [
      { macAddress: 'AA:BB:CC:DD:EE:01', action: 'REQUEST_PROTECTED', recoveryPoint: 'SNAP1' },
      { macAddress: 'AA:BB:CC:DD:EE:02', action: 'REQUEST_PROTECTED', recoveryPoint: 'SNAP1' },
    ])
  })
})
