export interface ReportFilters {
  fromDate: string
  toDate: string
  groupId: number | null
}

export interface MonthlyReport {
  id: number | null
  yearMonth: string
  totalPcs: number
  avgOnlinePcs: number
  peakOnlinePcs: number
  onlineRatio: number
  totalCommands: number
  failedCommands: number
  totalAlerts: number
  totalTrafficGb: number
  summaryJson: string | null
  generatedAt: string | null
}

export interface TrafficReport {
  from: string | null
  to: string | null
  totalInboundKbps: number
  totalOutboundKbps: number
  dailyInbound: Array<{ date: string; inboundKbps: number }>
  byTimeSlot: Array<{ key: 'morning' | 'afternoon' | 'night'; label: string; totalKbps: number }>
}

export interface UptimeGroup {
  groupId: number
  groupName: string
  totalPc: number
  avgOnline: number
  uptimePct: number
  anomalyCount: number
}

export interface UptimeReport {
  from: string | null
  to: string | null
  averageUptimePct: number
  groups: UptimeGroup[]
}

export type PressureLevel = 'LOW' | 'MEDIUM' | 'HIGH'

export interface PerformanceSnapshot {
  timestamp: string | null
  pc: { total: number; online: number; offline: number; onlineRatio: number }
  command: {
    queued: number
    delivered: number
    acked: number
    completed: number
    failed: number
    inFlight: number
    staleDelivered: number
    staleAcked: number
  }
  token: { active: number; revoked: number }
  cache: { enabled: boolean; provider: string | null; estimatedSize: number; hitRate: number }
  operational: {
    latency: { failureRate: number; ackP95Ms: number; resultP95Ms: number }
    transfer: { uploadAvgMbps: number; downloadAvgMbps: number; failureRate: number }
    request: { forbiddenRate: number; errorRate: number }
  }
  pressure: {
    queuePressure: PressureLevel
    stalePressure: PressureLevel
    commandFailurePressure: PressureLevel
    transferFailurePressure: PressureLevel
    requestErrorPressure: PressureLevel
  }
}

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {}
}

function finiteNonNegative(value: unknown) {
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) ? Math.max(0, parsed) : 0
}

function nullableString(value: unknown) {
  return typeof value === 'string' && value.trim() ? value : null
}

function rate(value: unknown) {
  return Math.min(1, finiteNonNegative(value))
}

function pressureLevel(value: unknown): PressureLevel {
  const normalized = typeof value === 'string' ? value.trim().toUpperCase() : ''
  return normalized === 'HIGH' || normalized === 'MEDIUM' ? normalized : 'LOW'
}

function localDateString(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function defaultReportFilters(reference = new Date()): ReportFilters {
  const to = new Date(reference.getFullYear(), reference.getMonth(), reference.getDate())
  const from = new Date(to)
  from.setDate(from.getDate() - 6)
  return { fromDate: localDateString(from), toDate: localDateString(to), groupId: null }
}

export function validateReportFilters(filters: ReportFilters) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(filters.fromDate) || !/^\d{4}-\d{2}-\d{2}$/.test(filters.toDate)) {
    return '조회 시작일과 종료일을 입력하세요.'
  }
  if (filters.fromDate > filters.toDate) return '조회 시작일은 종료일보다 늦을 수 없습니다.'
  const from = Date.parse(`${filters.fromDate}T00:00:00Z`)
  const to = Date.parse(`${filters.toDate}T00:00:00Z`)
  if (to - from > 30 * 24 * 60 * 60 * 1000) return '트래픽 보고서는 최대 31일까지 조회할 수 있습니다.'
  return null
}

export function reportQuery(filters: ReportFilters) {
  const params = new URLSearchParams({
    from: `${filters.fromDate}T00:00:00`,
    to: `${filters.toDate}T23:59:59`,
  })
  if (filters.groupId != null) params.set('groupId', String(filters.groupId))
  return params.toString()
}

export function pdfQuery(filters: Pick<ReportFilters, 'fromDate' | 'toDate'>, type: 'WEEKLY' | 'MONTHLY' = 'WEEKLY') {
  return new URLSearchParams({ type, fromDate: filters.fromDate, toDate: filters.toDate }).toString()
}

export function normalizeMonthlyReports(payload: unknown): MonthlyReport[] {
  if (!Array.isArray(payload)) return []
  return payload.flatMap((item) => {
    const source = record(item)
    const yearMonth = nullableString(source.yearMonth)
    if (!yearMonth || !/^\d{4}-\d{2}$/.test(yearMonth)) return []
    return [{
      id: source.id == null ? null : finiteNonNegative(source.id),
      yearMonth,
      totalPcs: finiteNonNegative(source.totalPcs),
      avgOnlinePcs: finiteNonNegative(source.avgOnlinePcs),
      peakOnlinePcs: finiteNonNegative(source.peakOnlinePcs),
      onlineRatio: finiteNonNegative(source.onlineRatio),
      totalCommands: finiteNonNegative(source.totalCommands),
      failedCommands: finiteNonNegative(source.failedCommands),
      totalAlerts: finiteNonNegative(source.totalAlerts),
      totalTrafficGb: finiteNonNegative(source.totalTrafficGb),
      summaryJson: nullableString(source.summaryJson),
      generatedAt: nullableString(source.generatedAt),
    }]
  }).sort((left, right) => right.yearMonth.localeCompare(left.yearMonth))
}

export function normalizeTrafficReport(payload: unknown): TrafficReport {
  const source = record(payload)
  const daily = record(source.dailyInbound)
  const slots = record(source.byTimeSlot)
  const slotLabels = {
    morning: '오전 (00~12시)',
    afternoon: '오후 (12~18시)',
    night: '야간 (18~24시)',
  } as const

  return {
    from: nullableString(source.from),
    to: nullableString(source.to),
    totalInboundKbps: finiteNonNegative(source.totalInboundKbps),
    totalOutboundKbps: finiteNonNegative(source.totalOutboundKbps),
    dailyInbound: Object.entries(daily)
      .filter(([date]) => /^\d{4}-\d{2}-\d{2}$/.test(date))
      .map(([date, value]) => ({ date, inboundKbps: finiteNonNegative(value) }))
      .sort((left, right) => left.date.localeCompare(right.date)),
    byTimeSlot: (['morning', 'afternoon', 'night'] as const).map((key) => ({
      key,
      label: slotLabels[key],
      totalKbps: finiteNonNegative(slots[key]),
    })),
  }
}

export function normalizeUptimeReport(payload: unknown): UptimeReport {
  const source = record(payload)
  const groups = Array.isArray(source.groups) ? source.groups : []
  return {
    from: nullableString(source.from),
    to: nullableString(source.to),
    averageUptimePct: Math.min(100, finiteNonNegative(source.averageUptimePct)),
    groups: groups.flatMap((item) => {
      const row = record(item)
      const groupId = Number(row.groupId)
      const groupName = nullableString(row.groupName)
      if (!Number.isFinite(groupId) || !groupName) return []
      return [{
        groupId,
        groupName,
        totalPc: finiteNonNegative(row.totalPc),
        avgOnline: finiteNonNegative(row.avgOnline),
        uptimePct: Math.min(100, finiteNonNegative(row.uptimePct)),
        anomalyCount: finiteNonNegative(row.anomalyCount),
      }]
    }).sort((left, right) => left.groupName.localeCompare(right.groupName, 'ko-KR', { numeric: true })),
  }
}

export function normalizePerformanceSnapshot(payload: unknown): PerformanceSnapshot {
  const source = record(payload)
  const pc = record(source.pc)
  const command = record(source.command)
  const token = record(source.token)
  const cache = record(source.cache)
  const operational = record(source.operational)
  const latency = record(operational.latency)
  const transfer = record(operational.transfer)
  const request = record(operational.request)
  const pressure = record(source.pressure)

  return {
    timestamp: nullableString(source.timestamp),
    pc: {
      total: finiteNonNegative(pc.total),
      online: finiteNonNegative(pc.online),
      offline: finiteNonNegative(pc.offline),
      onlineRatio: Math.min(100, finiteNonNegative(pc.onlineRatio)),
    },
    command: {
      queued: finiteNonNegative(command.queued),
      delivered: finiteNonNegative(command.delivered),
      acked: finiteNonNegative(command.acked),
      completed: finiteNonNegative(command.completed),
      failed: finiteNonNegative(command.failed),
      inFlight: finiteNonNegative(command.inFlight),
      staleDelivered: finiteNonNegative(command.staleDelivered),
      staleAcked: finiteNonNegative(command.staleAcked),
    },
    token: {
      active: finiteNonNegative(token.active),
      revoked: finiteNonNegative(token.revoked),
    },
    cache: {
      enabled: cache.enabled === true,
      provider: nullableString(cache.provider),
      estimatedSize: finiteNonNegative(cache.estimatedSize),
      hitRate: rate(cache.hitRate),
    },
    operational: {
      latency: {
        failureRate: rate(latency.failureRate),
        ackP95Ms: finiteNonNegative(latency.ackP95Ms),
        resultP95Ms: finiteNonNegative(latency.resultP95Ms),
      },
      transfer: {
        uploadAvgMbps: finiteNonNegative(transfer.uploadAvgMbps),
        downloadAvgMbps: finiteNonNegative(transfer.downloadAvgMbps),
        failureRate: rate(transfer.failureRate),
      },
      request: {
        forbiddenRate: rate(request.forbiddenRate),
        errorRate: rate(request.errorRate),
      },
    },
    pressure: {
      queuePressure: pressureLevel(pressure.queuePressure),
      stalePressure: pressureLevel(pressure.stalePressure),
      commandFailurePressure: pressureLevel(pressure.commandFailurePressure),
      transferFailurePressure: pressureLevel(pressure.transferFailurePressure),
      requestErrorPressure: pressureLevel(pressure.requestErrorPressure),
    },
  }
}

export function formatTrafficKbps(value: number) {
  const safe = finiteNonNegative(value)
  if (safe >= 1_000_000) return `${(safe / 1_000_000).toLocaleString('ko-KR', { maximumFractionDigits: 2 })} Gbps`
  if (safe >= 1_000) return `${(safe / 1_000).toLocaleString('ko-KR', { maximumFractionDigits: 2 })} Mbps`
  return `${safe.toLocaleString('ko-KR', { maximumFractionDigits: 0 })} Kbps`
}

export function formatReportDateTime(value: string | null) {
  if (!value) return '-'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? '-' : parsed.toLocaleString('ko-KR')
}

export function reportMonthLabel(yearMonth: string) {
  const [year, month] = yearMonth.split('-')
  return `${year}년 ${Number(month)}월`
}

export function failedCommandRate(report: Pick<MonthlyReport, 'totalCommands' | 'failedCommands'>) {
  if (report.totalCommands <= 0) return 0
  return Math.min(100, (report.failedCommands / report.totalCommands) * 100)
}
