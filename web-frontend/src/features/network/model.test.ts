import { describe, expect, it } from 'vitest'
import {
  appendTrafficHistory,
  createClassroomTrafficCsv,
  formatMbps,
  formatUpdatedAt,
  normalizeNetworkTraffic,
  normalizePcTraffic,
  normalizeThresholds,
  trafficLevel,
  trafficRatio,
  trafficTotal,
  summarizeClassroomTraffic,
} from './model'

describe('network traffic model', () => {
  it('normalizes invalid and negative summary values to zero', () => {
    expect(normalizeNetworkTraffic({ inboundMbps: -4, outboundMbps: Number.NaN })).toEqual({
      inboundMbps: 0,
      outboundMbps: 0,
    })
    expect(normalizeThresholds(undefined)).toEqual({ globalMbps: 0, classroomMbps: 0, pcMbps: 0 })
  })

  it('normalizes PC rows and sorts the busiest PC first', () => {
    const rows = normalizePcTraffic([
      {
        pcName: 'PC-1',
        ipAddress: null,
        macAddress: '00:00:00:00:00:01',
        status: 'ONLINE',
        inboundMbps: 1,
        outboundMbps: 2,
        updatedAt: null,
      },
      {
        pcName: 'PC-2',
        ipAddress: '10.0.0.2',
        macAddress: '00:00:00:00:00:02',
        status: 'ONLINE',
        inboundMbps: 8,
        outboundMbps: 4,
        updatedAt: '2026-08-07T10:00:00',
      },
    ])

    expect(rows.map((row) => row.pcName)).toEqual(['PC-2', 'PC-1'])
    expect(trafficTotal(rows[0])).toBe(12)
  })

  it('uses warning at 80 percent and critical at the threshold', () => {
    expect(trafficLevel(79.9, 100)).toBe('normal')
    expect(trafficLevel(80, 100)).toBe('warning')
    expect(trafficLevel(100, 100)).toBe('critical')
    expect(trafficLevel(100, 0)).toBe('normal')
    expect(trafficRatio(80, 100)).toBe(0.8)
    expect(trafficRatio(80, 0)).toBeNull()
  })

  it('summarizes classroom traffic without turning missing data into zero', () => {
    const classroom = {
      groupId: 218,
      groupName: '218호',
      onlinePcs: 39,
      totalPcs: 41,
      runningCommandCount: 0,
    }

    expect(summarizeClassroomTraffic(classroom, undefined)).toMatchObject({
      inboundMbps: null,
      outboundMbps: null,
    })
    expect(summarizeClassroomTraffic(classroom, [])).toMatchObject({
      inboundMbps: 0,
      outboundMbps: 0,
    })
    expect(summarizeClassroomTraffic(classroom, [{
      pcName: '218-01',
      ipAddress: '10.0.0.1',
      macAddress: '00:00:00:00:00:01',
      status: 'ONLINE',
      inboundMbps: 12,
      outboundMbps: 8,
      updatedAt: null,
    }])).toMatchObject({ inboundMbps: 12, outboundMbps: 8 })
  })

  it('keeps only the latest distinct traffic history points', () => {
    let history = appendTrafficHistory([], { inboundMbps: 1, outboundMbps: 2 }, 1_000, 2)
    history = appendTrafficHistory(history, { inboundMbps: 3, outboundMbps: 4 }, 2_000, 2)
    history = appendTrafficHistory(history, { inboundMbps: 5, outboundMbps: 6 }, 3_000, 2)
    history = appendTrafficHistory(history, { inboundMbps: 7, outboundMbps: 8 }, 3_000, 2)

    expect(history).toHaveLength(2)
    expect(history.map((point) => point.timestamp)).toEqual([2_000, 3_000])
    expect(history[1]).toMatchObject({ inboundMbps: 7, outboundMbps: 8 })
  })

  it('exports filtered classroom rows as an Excel-friendly CSV', () => {
    const csv = createClassroomTrafficCsv([{
      groupId: 1,
      groupName: '109호, 1실',
      onlinePcs: 39,
      totalPcs: 41,
      inboundMbps: 80,
      outboundMbps: 30,
    }, {
      groupId: 2,
      groupName: '218호',
      onlinePcs: 0,
      totalPcs: 41,
      inboundMbps: null,
      outboundMbps: null,
    }], 100)

    expect(csv.startsWith('\uFEFF')).toBe(true)
    expect(csv).toContain('"109호, 1실",39,41,80.00,30.00,80.0%,이상')
    expect(csv).toContain('218호,0,41,수집 실패,수집 실패,임계값 없음,판정 불가')
  })

  it('formats display values and handles missing timestamps', () => {
    expect(formatMbps(12.345)).toBe('12.35 Mbps')
    expect(formatUpdatedAt(null)).toBe('수집 기록 없음')
    expect(formatUpdatedAt('not-a-date')).toBe('수집 시간 확인 불가')
  })
})
