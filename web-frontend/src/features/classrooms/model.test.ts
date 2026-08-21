import { describe, expect, it } from 'vitest'
import type { ClassroomSummary, PcStatus } from '../../types/dashboard'
import { getRoomCounts, isBrokenPc, roomStatus, seatLabel, sortPcs } from './model'

function pc(overrides: Partial<PcStatus>): PcStatus {
  return {
    macAddress: '00:11:22:33:44:55',
    pcName: 'PC-1',
    online: false,
    ...overrides,
  }
}

function room(overrides: Partial<ClassroomSummary>): ClassroomSummary {
  return {
    groupId: 1,
    groupName: '218호',
    onlinePcs: 0,
    totalPcs: 41,
    runningCommandCount: 0,
    ...overrides,
  }
}

describe('classroom model', () => {
  it('keeps running command status ahead of online state', () => {
    expect(roomStatus(room({ onlinePcs: 40, teacherPcStatus: 'ONLINE', runningCommandCount: 1 }))).toBe('running')
  })

  it('uses command-eligible PC states for the displayed online count', () => {
    const counts = getRoomCounts(room({ onlinePcs: 2, totalPcs: 3 }), [
      pc({ macAddress: '00:00:00:00:00:01', status: 'ONLINE' }),
      pc({ macAddress: '00:00:00:00:00:02', status: 'OFFLINE' }),
      pc({ macAddress: 'BROKEN-3', pcName: '미사용 PC 3', status: 'BROKEN' }),
    ])

    expect(counts).toEqual({ total: 3, online: 1, broken: 1, offline: 1 })
    expect(roomStatus(room({ onlinePcs: 2, totalPcs: 3 }), counts)).toBe('warning')
  })

  it('sorts teacher first and student PCs by numeric seat', () => {
    const sorted = sortPcs([
      pc({ pcName: 'PC-10', role: 'STUDENT' }),
      pc({ pcName: 'TEACHER', role: 'TEACHER', macAddress: '00:00:00:00:00:01' }),
      pc({ pcName: 'PC-2', role: 'STUDENT', macAddress: '00:00:00:00:00:02' }),
    ])
    expect(sorted.map((item) => item.pcName)).toEqual(['TEACHER', 'PC-2', 'PC-10'])
  })

  it('uses the structured unused PC seat number and marks it broken', () => {
    const unused = pc({ macAddress: 'BROKEN-17-9', pcName: '미사용 PC 9', status: 'BROKEN' })
    expect(seatLabel(unused)).toBe('9')
    expect(isBrokenPc(unused)).toBe(true)
  })

  it('uses a compact placeholder when a PC name has no seat number', () => {
    expect(seatLabel(pc({ pcName: 'LAPTOP' }))).toBe('?')
  })
})
