import type { ClassroomSummary } from '../../types/dashboard'
import type {
  ClassroomTrafficSummary,
  NetworkTraffic,
  PcTraffic,
  TrafficHistoryPoint,
  TrafficThresholds,
} from './types'

export type TrafficLevel = 'normal' | 'warning' | 'critical'

function finiteNonNegative(value: unknown) {
  const parsed = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0
}

function nullableText(value: unknown) {
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

export function normalizeNetworkTraffic(value: Partial<NetworkTraffic> | null | undefined): NetworkTraffic {
  return {
    inboundMbps: finiteNonNegative(value?.inboundMbps),
    outboundMbps: finiteNonNegative(value?.outboundMbps),
  }
}

export function normalizeThresholds(value: Partial<TrafficThresholds> | null | undefined): TrafficThresholds {
  return {
    globalMbps: finiteNonNegative(value?.globalMbps),
    classroomMbps: finiteNonNegative(value?.classroomMbps),
    pcMbps: finiteNonNegative(value?.pcMbps),
  }
}

export function normalizePcTraffic(rows: PcTraffic[] | null | undefined): PcTraffic[] {
  if (!Array.isArray(rows)) return []

  return rows.map((row, index) => ({
    pcName: nullableText(row?.pcName) || `이름 없는 PC ${index + 1}`,
    ipAddress: nullableText(row?.ipAddress),
    macAddress: nullableText(row?.macAddress) || `unknown-${index}`,
    status: nullableText(row?.status),
    inboundMbps: finiteNonNegative(row?.inboundMbps),
    outboundMbps: finiteNonNegative(row?.outboundMbps),
    updatedAt: nullableText(row?.updatedAt),
  })).sort((left, right) => trafficTotal(right) - trafficTotal(left))
}

export function trafficTotal(traffic: Pick<NetworkTraffic, 'inboundMbps' | 'outboundMbps'>) {
  return finiteNonNegative(traffic.inboundMbps) + finiteNonNegative(traffic.outboundMbps)
}

export function trafficLevel(valueMbps: number, thresholdMbps: number): TrafficLevel {
  const value = finiteNonNegative(valueMbps)
  const threshold = finiteNonNegative(thresholdMbps)
  if (threshold === 0) return 'normal'
  if (value >= threshold) return 'critical'
  if (value >= threshold * 0.8) return 'warning'
  return 'normal'
}

export function trafficRatio(valueMbps: number, thresholdMbps: number) {
  const threshold = finiteNonNegative(thresholdMbps)
  if (threshold === 0) return null
  return finiteNonNegative(valueMbps) / threshold
}

export function summarizeClassroomTraffic(
  classroom: ClassroomSummary,
  rows: PcTraffic[] | undefined,
): ClassroomTrafficSummary {
  if (rows === undefined) {
    return {
      groupId: classroom.groupId,
      groupName: classroom.groupName,
      onlinePcs: classroom.onlinePcs,
      totalPcs: classroom.totalPcs,
      inboundMbps: null,
      outboundMbps: null,
    }
  }

  const normalized = normalizePcTraffic(rows)
  return {
    groupId: classroom.groupId,
    groupName: classroom.groupName,
    onlinePcs: classroom.onlinePcs,
    totalPcs: classroom.totalPcs,
    inboundMbps: normalized.reduce((sum, row) => sum + row.inboundMbps, 0),
    outboundMbps: normalized.reduce((sum, row) => sum + row.outboundMbps, 0),
  }
}

export function appendTrafficHistory(
  history: TrafficHistoryPoint[],
  traffic: NetworkTraffic,
  timestamp: number,
  maxPoints = 30,
) {
  const point: TrafficHistoryPoint = {
    ...normalizeNetworkTraffic(traffic),
    timestamp,
    label: new Date(timestamp).toLocaleTimeString('ko-KR', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    }),
  }
  const withoutDuplicate = history.filter((entry) => entry.timestamp !== timestamp)
  return [...withoutDuplicate, point].slice(-Math.max(1, maxPoints))
}

function escapeCsv(value: string | number) {
  const text = String(value)
  return /[",\r\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text
}

export function createClassroomTrafficCsv(
  rows: ClassroomTrafficSummary[],
  classroomThresholdMbps: number | null,
) {
  const header = ['강의실', '온라인 PC', '전체 PC', '인바운드(Mbps)', '아웃바운드(Mbps)', '인바운드 부하율', '상태']
  const lines = rows.map((row) => {
    const hasTraffic = row.inboundMbps !== null && row.outboundMbps !== null
    const ratio = hasTraffic && classroomThresholdMbps !== null
      ? trafficRatio(row.inboundMbps as number, classroomThresholdMbps)
      : null
    const level = hasTraffic && classroomThresholdMbps !== null
      ? trafficLevel(row.inboundMbps as number, classroomThresholdMbps)
      : null
    return [
      row.groupName,
      row.onlinePcs,
      row.totalPcs,
      row.inboundMbps === null ? '수집 실패' : row.inboundMbps.toFixed(2),
      row.outboundMbps === null ? '수집 실패' : row.outboundMbps.toFixed(2),
      ratio === null ? '임계값 없음' : `${(ratio * 100).toFixed(1)}%`,
      level === null ? '판정 불가' : level === 'normal' ? '정상' : '이상',
    ].map(escapeCsv).join(',')
  })
  return `\uFEFF${[header.join(','), ...lines].join('\r\n')}`
}

export function formatMbps(value: number) {
  return `${new Intl.NumberFormat('ko-KR', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(finiteNonNegative(value))} Mbps`
}

export function formatUpdatedAt(value: string | null) {
  if (!value) return '수집 기록 없음'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '수집 시간 확인 불가'
  return date.toLocaleString('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}
