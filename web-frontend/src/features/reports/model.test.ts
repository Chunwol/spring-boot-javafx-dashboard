import { describe, expect, it } from 'vitest'
import {
  defaultReportFilters,
  failedCommandRate,
  formatTrafficKbps,
  normalizeMonthlyReports,
  normalizePerformanceSnapshot,
  normalizeTrafficReport,
  normalizeUptimeReport,
  pdfQuery,
  reportQuery,
  validateReportFilters,
} from './model'

describe('report model', () => {
  it('creates a seven-day local range and validates reversed dates', () => {
    expect(defaultReportFilters(new Date(2026, 7, 7, 15, 30))).toEqual({
      fromDate: '2026-08-01',
      toDate: '2026-08-07',
      groupId: null,
    })
    expect(validateReportFilters({ fromDate: '2026-08-08', toDate: '2026-08-07', groupId: null }))
      .toBe('조회 시작일은 종료일보다 늦을 수 없습니다.')
    expect(validateReportFilters({ fromDate: '2026-01-01', toDate: '2026-02-01', groupId: null }))
      .toBe('트래픽 보고서는 최대 31일까지 조회할 수 있습니다.')
    expect(validateReportFilters({ fromDate: '2026-01-01', toDate: '2026-01-31', groupId: null }))
      .toBeNull()
  })

  it('builds encoded JSON and PDF queries without adding an empty group', () => {
    expect(reportQuery({ fromDate: '2026-08-01', toDate: '2026-08-07', groupId: 12 }))
      .toBe('from=2026-08-01T00%3A00%3A00&to=2026-08-07T23%3A59%3A59&groupId=12')
    expect(pdfQuery({ fromDate: '2026-08-01', toDate: '2026-08-07' }, 'MONTHLY'))
      .toBe('type=MONTHLY&fromDate=2026-08-01&toDate=2026-08-07')
  })

  it('normalizes and sorts monthly reports while dropping malformed rows', () => {
    const rows = normalizeMonthlyReports([
      { yearMonth: '2026-07', totalPcs: -1, totalCommands: 10, failedCommands: 2 },
      { yearMonth: '2026-08', onlineRatio: '91.5', totalTrafficGb: '12.25' },
      { yearMonth: 'invalid' },
    ])
    expect(rows.map((row) => row.yearMonth)).toEqual(['2026-08', '2026-07'])
    expect(rows[0].onlineRatio).toBe(91.5)
    expect(rows[1].totalPcs).toBe(0)
    expect(failedCommandRate(rows[1])).toBe(20)
  })

  it('normalizes traffic maps into stable ordered rows', () => {
    const report = normalizeTrafficReport({
      totalInboundKbps: '2500',
      totalOutboundKbps: -4,
      dailyInbound: { '2026-08-02': 20, bad: 99, '2026-08-01': '10' },
      byTimeSlot: { morning: 5, afternoon: '8' },
    })
    expect(report.totalInboundKbps).toBe(2500)
    expect(report.totalOutboundKbps).toBe(0)
    expect(report.dailyInbound).toEqual([
      { date: '2026-08-01', inboundKbps: 10 },
      { date: '2026-08-02', inboundKbps: 20 },
    ])
    expect(report.byTimeSlot.map((row) => row.totalKbps)).toEqual([5, 8, 0])
    expect(formatTrafficKbps(2500)).toBe('2.5 Mbps')
  })

  it('normalizes uptime strings and bounds percentages', () => {
    const report = normalizeUptimeReport({
      averageUptimePct: '120',
      groups: [
        { groupId: 2, groupName: '210호', totalPc: 40, avgOnline: '8.2', uptimePct: '72.4', anomalyCount: 1 },
        { groupId: 1, groupName: '102호', uptimePct: -1 },
        { groupId: null, groupName: '' },
      ],
    })
    expect(report.averageUptimePct).toBe(100)
    expect(report.groups.map((row) => row.groupName)).toEqual(['102호', '210호'])
    expect(report.groups[1].avgOnline).toBe(8.2)
  })

  it('normalizes performance metrics and pressure levels safely', () => {
    const snapshot = normalizePerformanceSnapshot({
      timestamp: '2026-08-07T18:30:00',
      pc: { total: 850, online: 810, offline: 40, onlineRatio: '95.3' },
      command: { queued: 3, inFlight: 8, failed: 2, staleDelivered: 1, staleAcked: 4 },
      token: { active: 820, revoked: 30 },
      cache: { enabled: true, provider: 'caffeine', estimatedSize: 17, hitRate: 1.5 },
      operational: {
        latency: { failureRate: 0.02, ackP95Ms: 140, resultP95Ms: 900 },
        transfer: { uploadAvgMbps: 8.5, downloadAvgMbps: 12.3, failureRate: 0.04 },
        request: { forbiddenRate: 0.01, errorRate: 0.03 },
      },
      pressure: { queuePressure: 'medium', stalePressure: 'invalid', requestErrorPressure: 'HIGH' },
    })

    expect(snapshot.pc.onlineRatio).toBe(95.3)
    expect(snapshot.cache.hitRate).toBe(1)
    expect(snapshot.command.staleAcked).toBe(4)
    expect(snapshot.pressure).toMatchObject({
      queuePressure: 'MEDIUM',
      stalePressure: 'LOW',
      requestErrorPressure: 'HIGH',
    })
  })
})
