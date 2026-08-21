export const ALERT_FILTERS = [
  { key: 'PC_OFFLINE', label: 'PC 오프라인', description: 'PC가 서버에 응답하지 않을 때' },
  { key: 'CLASSROOM_POWER', label: '강의실 전원 상태', description: '강의실이 켜지거나 꺼질 때' },
  { key: 'TRAFFIC_HIGH', label: '네트워크 과부하', description: '트래픽 임계치를 초과할 때' },
  { key: 'SCMATE', label: 'SCMATE 감지', description: '설치 직후 연결 끊김이 감지될 때' },
  { key: 'METRICS_PERF', label: '성능 메트릭', description: '명령 실패율, 지연, API 오류율이 증가할 때' },
  { key: 'OTA_UPDATE', label: '에이전트 업데이트', description: 'OTA 업데이트 결과가 발생할 때' },
] as const

export const PERFORMANCE_TYPES = [
  'METRICS_TRANSFER_FAILURE_RATE',
  'METRICS_ACK_LATENCY',
  'METRICS_RESULT_LATENCY',
  'METRICS_REQUEST_ERROR_RATE',
  'METRICS_FORBIDDEN_RATE',
  'METRICS_COMMAND_FAILURE_RATE',
]

export const CLASSROOM_POWER_TYPES = ['CLASSROOM_POWER_ON', 'CLASSROOM_POWER_OFF']

export function parseHiddenAlertTypes(raw: string) {
  if (!raw.trim()) return new Set<string>(['PC_OFFLINE', 'TRAFFIC_HIGH'])
  try {
    const parsed = JSON.parse(raw) as unknown
    return new Set(Array.isArray(parsed) ? parsed.filter((value): value is string => typeof value === 'string') : [])
  } catch {
    return new Set(raw.replace(/[\[\]"]/g, '').split(',').map((value) => value.trim()).filter(Boolean))
  }
}

export function alertFilterSelected(key: string, hidden: ReadonlySet<string>) {
  const groupedTypes = key === 'METRICS_PERF' ? PERFORMANCE_TYPES : key === 'CLASSROOM_POWER' ? CLASSROOM_POWER_TYPES : null
  if (groupedTypes) return !groupedTypes.every((type) => hidden.has(type))
  return !hidden.has(key)
}

export function toggleAlertFilter(hidden: ReadonlySet<string>, key: string, selected: boolean) {
  const next = new Set(hidden)
  const keys = key === 'METRICS_PERF' ? PERFORMANCE_TYPES : key === 'CLASSROOM_POWER' ? CLASSROOM_POWER_TYPES : [key]
  keys.forEach((type) => selected ? next.delete(type) : next.add(type))
  return next
}
