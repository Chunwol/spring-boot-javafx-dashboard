import { useMemo, useState } from 'react'
import type { CSSProperties, FormEvent, ReactNode } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Activity,
  ArrowDownToLine,
  ArrowUpFromLine,
  CalendarDays,
  Clock3,
  Database,
  Download,
  FileBarChart,
  Gauge,
  KeyRound,
  RefreshCw,
  RotateCcw,
} from 'lucide-react'
import { useAuth } from '../auth/AuthProvider'
import { ConfirmDialog } from '../components/ui/ConfirmDialog'
import { EmptyState, PageError, PageLoading } from '../components/ui/PageState'
import { useToast } from '../components/ui/ToastProvider'
import { getClassrooms } from '../features/classrooms/api'
import {
  downloadReportPdf,
  getMonthlyReports,
  getPerformanceStatus,
  getTrafficReport,
  getUptimeReport,
  regenerateMonthlyReport,
} from '../features/reports/api'
import {
  defaultReportFilters,
  failedCommandRate,
  formatReportDateTime,
  formatTrafficKbps,
  normalizeMonthlyReports,
  normalizePerformanceSnapshot,
  normalizeTrafficReport,
  normalizeUptimeReport,
  reportMonthLabel,
  validateReportFilters,
} from '../features/reports/model'
import type { MonthlyReport, PressureLevel, ReportFilters } from '../features/reports/model'

const styles = {
  filters: {
    display: 'flex',
    alignItems: 'end',
    flexWrap: 'wrap',
    gap: 10,
    marginBottom: 16,
    padding: 12,
    background: 'var(--surface-subtle)',
    border: '1px solid var(--border)',
    borderRadius: 7,
  },
  field: { display: 'grid', gap: 5, minWidth: 152 },
  fieldLabel: { color: 'var(--muted)', fontSize: 10, fontWeight: 800 },
  input: {
    height: 36,
    padding: '0 10px',
    color: '#415169',
    background: 'white',
    border: '1px solid var(--border)',
    borderRadius: 6,
    fontSize: 12,
  },
  summaryGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(185px, 1fr))',
    gap: 12,
    marginBottom: 18,
  },
  metric: {
    display: 'grid',
    gridTemplateColumns: '38px minmax(0, 1fr)',
    alignItems: 'center',
    gap: 11,
    minHeight: 82,
    padding: 14,
    background: 'var(--surface-subtle)',
    border: '1px solid var(--border)',
    borderRadius: 7,
  },
  metricIcon: {
    display: 'grid',
    placeItems: 'center',
    width: 38,
    height: 38,
    color: 'var(--blue)',
    background: 'var(--blue-soft)',
    borderRadius: 6,
  },
  metricLabel: { display: 'block', marginBottom: 3, color: 'var(--muted)', fontSize: 11 },
  metricValue: { display: 'block', fontSize: 18, lineHeight: 1.2 },
  metricHint: { display: 'block', marginTop: 4, color: 'var(--muted)', fontSize: 10 },
  sectionGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 460px), 1fr))',
    gap: 16,
    marginBottom: 18,
  },
  section: { minWidth: 0 },
  sectionHeader: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    flexWrap: 'wrap',
    gap: 10,
    minHeight: 38,
    marginBottom: 10,
  },
  sectionTitle: { display: 'flex', alignItems: 'center', gap: 7, margin: 0, fontSize: 14 },
  subtext: { margin: '4px 0 0', color: 'var(--muted)', fontSize: 10 },
  slots: { display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 8, marginBottom: 10 },
  slot: {
    minWidth: 0,
    padding: 10,
    background: 'var(--surface-subtle)',
    border: '1px solid var(--border)',
    borderRadius: 6,
  },
  slotLabel: { display: 'block', marginBottom: 4, color: 'var(--muted)', fontSize: 9 },
  slotValue: { display: 'block', overflow: 'hidden', fontSize: 12, textOverflow: 'ellipsis' },
  progressTrack: {
    width: 110,
    height: 7,
    overflow: 'hidden',
    background: '#e8edf3',
    borderRadius: 4,
  },
  progressBar: { height: '100%', background: 'var(--green)', borderRadius: 4 },
  monthlyActions: { display: 'flex', alignItems: 'end', flexWrap: 'wrap', gap: 8 },
  pressureList: { display: 'flex', flexWrap: 'wrap', gap: 8 },
  inlineError: { marginBottom: 10 },
} satisfies Record<string, CSSProperties>

function MetricCard({ icon, label, value, hint }: { icon: ReactNode; label: string; value: string; hint: string }) {
  return (
    <div style={styles.metric}>
      <span style={styles.metricIcon}>{icon}</span>
      <div>
        <span style={styles.metricLabel}>{label}</span>
        <strong style={styles.metricValue}>{value}</strong>
        <small style={styles.metricHint}>{hint}</small>
      </div>
    </div>
  )
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback
}

function currentYearMonth() {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

function percentage(value: number) {
  return `${(value * 100).toLocaleString('ko-KR', { maximumFractionDigits: 1 })}%`
}

function PressureBadge({ label, level }: { label: string; level: PressureLevel }) {
  const colors: Record<PressureLevel, { color: string; background: string; border: string }> = {
    LOW: { color: '#15803d', background: '#f0fdf4', border: '#bbf7d0' },
    MEDIUM: { color: '#b45309', background: '#fffbeb', border: '#fde68a' },
    HIGH: { color: '#b91c1c', background: '#fef2f2', border: '#fecaca' },
  }
  const levelLabel: Record<PressureLevel, string> = { LOW: '낮음', MEDIUM: '주의', HIGH: '높음' }
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '6px 8px', border: `1px solid ${colors[level].border}`, borderRadius: 6, color: colors[level].color, background: colors[level].background, fontSize: 10, fontWeight: 800 }}>
      {label}<strong>{levelLabel[level]}</strong>
    </span>
  )
}

function MonthlyRows({ reports, canWrite, onRegenerate }: {
  reports: MonthlyReport[]
  canWrite: boolean
  onRegenerate: (yearMonth: string) => void
}) {
  return (
    <div className="table-scroll">
      <table className="data-table">
        <thead>
          <tr>
            <th>대상 월</th><th>등록 PC</th><th>평균/최대 온라인</th><th>온라인 비율</th>
            <th>명령 실패</th><th>경고</th><th>트래픽</th><th>생성 시각</th>{canWrite && <th>작업</th>}
          </tr>
        </thead>
        <tbody>
          {reports.map((report) => (
            <tr key={report.yearMonth} style={{ cursor: 'default' }}>
              <td><strong>{reportMonthLabel(report.yearMonth)}</strong></td>
              <td>{report.totalPcs.toLocaleString('ko-KR')}대</td>
              <td>{report.avgOnlinePcs.toLocaleString('ko-KR')} / {report.peakOnlinePcs.toLocaleString('ko-KR')}대</td>
              <td>{report.onlineRatio.toLocaleString('ko-KR', { maximumFractionDigits: 1 })}%</td>
              <td>
                {report.failedCommands.toLocaleString('ko-KR')} / {report.totalCommands.toLocaleString('ko-KR')}건
                <small style={{ display: 'block', color: 'var(--muted)' }}>{failedCommandRate(report).toFixed(1)}%</small>
              </td>
              <td>{report.totalAlerts.toLocaleString('ko-KR')}건</td>
              <td>{report.totalTrafficGb.toLocaleString('ko-KR', { maximumFractionDigits: 2 })} GB</td>
              <td>{formatReportDateTime(report.generatedAt)}</td>
              {canWrite && (
                <td>
                  <button className="button secondary" onClick={() => onRegenerate(report.yearMonth)}>
                    <RotateCcw size={14} />재생성
                  </button>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export function ReportsPage() {
  const queryClient = useQueryClient()
  const { hasPermission } = useAuth()
  const canRegenerate = hasPermission('REPORT_REGENERATE')
  const canExport = hasPermission('REPORT_EXPORT')
  const { showToast } = useToast()
  const initialFilters = useMemo(() => defaultReportFilters(), [])
  const [draftFilters, setDraftFilters] = useState<ReportFilters>(initialFilters)
  const [appliedFilters, setAppliedFilters] = useState<ReportFilters>(initialFilters)
  const [filterError, setFilterError] = useState<string | null>(null)
  const [pdfType, setPdfType] = useState<'WEEKLY' | 'MONTHLY'>('WEEKLY')
  const [regenerateMonth, setRegenerateMonth] = useState(currentYearMonth())
  const [pendingRegenerate, setPendingRegenerate] = useState<string | null>(null)

  const classroomsQuery = useQuery({
    queryKey: ['classrooms'],
    queryFn: getClassrooms,
    staleTime: 60_000,
  })
  const monthlyQuery = useQuery({
    queryKey: ['reports', 'monthly'],
    queryFn: getMonthlyReports,
    staleTime: 60_000,
  })
  const trafficQuery = useQuery({
    queryKey: ['reports', 'traffic', appliedFilters],
    queryFn: () => getTrafficReport(appliedFilters),
    staleTime: 30_000,
  })
  const uptimeQuery = useQuery({
    queryKey: ['reports', 'uptime', appliedFilters],
    queryFn: () => getUptimeReport(appliedFilters),
    staleTime: 30_000,
  })
  const performanceQuery = useQuery({
    queryKey: ['status', 'performance'],
    queryFn: getPerformanceStatus,
    staleTime: 15_000,
    refetchInterval: 30_000,
  })

  const classrooms = classroomsQuery.data || []
  const monthlyReports = useMemo(() => normalizeMonthlyReports(monthlyQuery.data), [monthlyQuery.data])
  const traffic = useMemo(() => normalizeTrafficReport(trafficQuery.data), [trafficQuery.data])
  const uptime = useMemo(() => normalizeUptimeReport(uptimeQuery.data), [uptimeQuery.data])
  const performance = useMemo(() => normalizePerformanceSnapshot(performanceQuery.data), [performanceQuery.data])
  const selectedClassroom = classrooms.find((room) => room.groupId === appliedFilters.groupId)
  const totalTraffic = traffic.totalInboundKbps + traffic.totalOutboundKbps
  const isRefreshing = classroomsQuery.isFetching || monthlyQuery.isFetching || trafficQuery.isFetching || uptimeQuery.isFetching || performanceQuery.isFetching

  const regenerateMutation = useMutation({
    mutationFn: regenerateMonthlyReport,
    onSuccess: (_result, yearMonth) => {
      showToast(`${reportMonthLabel(yearMonth)} 보고서를 재생성했습니다.`)
      setPendingRegenerate(null)
      void queryClient.invalidateQueries({ queryKey: ['reports', 'monthly'] })
    },
    onError: (error) => showToast(errorMessage(error, '월간 보고서를 재생성하지 못했습니다.'), 'error'),
  })
  const pdfMutation = useMutation({
    mutationFn: () => downloadReportPdf(appliedFilters, pdfType),
    onSuccess: () => showToast('PDF 보고서를 내려받았습니다.'),
    onError: (error) => showToast(errorMessage(error, 'PDF 보고서를 내려받지 못했습니다.'), 'error'),
  })

  const applyFilters = (event: FormEvent) => {
    event.preventDefault()
    const validation = validateReportFilters(draftFilters)
    setFilterError(validation)
    if (validation) return
    if (
      draftFilters.fromDate === appliedFilters.fromDate
      && draftFilters.toDate === appliedFilters.toDate
      && draftFilters.groupId === appliedFilters.groupId
    ) {
      void trafficQuery.refetch()
      void uptimeQuery.refetch()
      return
    }
    setAppliedFilters({ ...draftFilters })
  }

  const refresh = () => {
    void classroomsQuery.refetch()
    void monthlyQuery.refetch()
    void trafficQuery.refetch()
    void uptimeQuery.refetch()
    void performanceQuery.refetch()
  }

  return (
    <section className="content-section">
      <header className="page-header">
        <div>
          <h1>보고서</h1>
          <p>{selectedClassroom?.groupName || '전체 강의실'} · {appliedFilters.fromDate} ~ {appliedFilters.toDate}</p>
        </div>
        <button className="icon-button bordered" onClick={refresh} disabled={isRefreshing} aria-label="새로고침" title="새로고침">
          <RefreshCw size={17} className={isRefreshing ? 'spin-icon' : ''} />
        </button>
      </header>

      <form style={styles.filters} onSubmit={applyFilters}>
        <label style={styles.field}>
          <span style={styles.fieldLabel}>시작일</span>
          <input style={styles.input} type="date" value={draftFilters.fromDate} onChange={(event) => setDraftFilters((current) => ({ ...current, fromDate: event.target.value }))} />
        </label>
        <label style={styles.field}>
          <span style={styles.fieldLabel}>종료일</span>
          <input style={styles.input} type="date" value={draftFilters.toDate} onChange={(event) => setDraftFilters((current) => ({ ...current, toDate: event.target.value }))} />
        </label>
        <label style={{ ...styles.field, minWidth: 190 }}>
          <span style={styles.fieldLabel}>강의실</span>
          <select
            style={styles.input}
            value={draftFilters.groupId ?? ''}
            onChange={(event) => setDraftFilters((current) => ({ ...current, groupId: event.target.value ? Number(event.target.value) : null }))}
            disabled={classroomsQuery.isLoading}
          >
            <option value="">전체 강의실</option>
            {classrooms.map((room) => <option key={room.groupId} value={room.groupId}>{room.groupName}</option>)}
          </select>
        </label>
        <button className="button primary" type="submit" disabled={trafficQuery.isFetching || uptimeQuery.isFetching}>조회</button>
        <span style={{ flex: 1 }} />
        <label style={{ ...styles.field, minWidth: 128 }}>
          <span style={styles.fieldLabel}>PDF 형식</span>
          <select style={styles.input} value={pdfType} onChange={(event) => setPdfType(event.target.value as 'WEEKLY' | 'MONTHLY')}>
            <option value="WEEKLY">주간 형식</option>
            <option value="MONTHLY">월간 형식</option>
          </select>
        </label>
        {canExport && <button className="button secondary" type="button" onClick={() => pdfMutation.mutate()} disabled={pdfMutation.isPending || Boolean(filterError)}>
          <Download size={16} />{pdfMutation.isPending ? '생성 중' : '전체 강의실 PDF'}
        </button>}
      </form>

      {filterError && <div className="form-error" role="alert" style={styles.inlineError}>{filterError}</div>}
      {classroomsQuery.isError && <div className="form-error" role="alert" style={styles.inlineError}>강의실 목록을 불러오지 못해 전체 강의실 기준으로 조회할 수 있습니다.</div>}

      <section aria-labelledby="performance-status-title" style={{ marginBottom: 18 }}>
        <header style={styles.sectionHeader}>
          <div>
            <h2 id="performance-status-title" style={styles.sectionTitle}><Activity size={16} />실시간 운영 성능</h2>
            <p style={styles.subtext}>최근 스냅샷 {formatReportDateTime(performance.timestamp)}</p>
          </div>
          <div style={styles.pressureList} aria-label="운영 압력 상태">
            <PressureBadge label="명령 대기" level={performance.pressure.queuePressure} />
            <PressureBadge label="지연" level={performance.pressure.stalePressure} />
            <PressureBadge label="명령 실패" level={performance.pressure.commandFailurePressure} />
            <PressureBadge label="전송 실패" level={performance.pressure.transferFailurePressure} />
            <PressureBadge label="API 오류" level={performance.pressure.requestErrorPressure} />
          </div>
        </header>
        {performanceQuery.isLoading && !performanceQuery.data ? (
          <PageLoading label="운영 성능을 불러오는 중입니다." />
        ) : performanceQuery.isError && !performanceQuery.data ? (
          <PageError message={errorMessage(performanceQuery.error, '운영 성능을 불러오지 못했습니다.')} onRetry={() => { void performanceQuery.refetch() }} />
        ) : (
          <>
            {performanceQuery.isError && <div className="form-error" role="alert">운영 성능 갱신에 실패해 기존 스냅샷을 표시합니다.</div>}
            <div style={styles.summaryGrid} aria-busy={performanceQuery.isFetching}>
              <MetricCard icon={<Gauge size={19} />} label="현재 온라인율" value={`${performance.pc.onlineRatio.toFixed(1)}%`} hint={`${performance.pc.online.toLocaleString('ko-KR')} / ${performance.pc.total.toLocaleString('ko-KR')}대 · 오프라인 ${performance.pc.offline.toLocaleString('ko-KR')}대`} />
              <MetricCard icon={<Activity size={19} />} label="명령 대기 / 진행" value={`${performance.command.queued.toLocaleString('ko-KR')} / ${performance.command.inFlight.toLocaleString('ko-KR')}건`} hint={`완료 ${performance.command.completed.toLocaleString('ko-KR')} · 실패 ${performance.command.failed.toLocaleString('ko-KR')} · 실패율 ${percentage(performance.operational.latency.failureRate)}`} />
              <MetricCard icon={<Clock3 size={19} />} label="전달 / ACK 지연" value={`${performance.command.staleDelivered.toLocaleString('ko-KR')} / ${performance.command.staleAcked.toLocaleString('ko-KR')}건`} hint={`ACK P95 ${performance.operational.latency.ackP95Ms.toFixed(0)}ms · 결과 P95 ${performance.operational.latency.resultP95Ms.toFixed(0)}ms`} />
              <MetricCard icon={<KeyRound size={19} />} label="활성 / 폐기 토큰" value={`${performance.token.active.toLocaleString('ko-KR')} / ${performance.token.revoked.toLocaleString('ko-KR')}개`} hint={`403 ${percentage(performance.operational.request.forbiddenRate)} · API 오류 ${percentage(performance.operational.request.errorRate)}`} />
              <MetricCard icon={<Database size={19} />} label="캐시 적중률" value={performance.cache.enabled ? percentage(performance.cache.hitRate) : '사용 안 함'} hint={`${performance.cache.provider || '미확인'} · ${performance.cache.estimatedSize.toLocaleString('ko-KR')}개 · 전송 실패 ${percentage(performance.operational.transfer.failureRate)}`} />
              <MetricCard icon={<ArrowUpFromLine size={19} />} label="평균 업 / 다운로드" value={`${performance.operational.transfer.uploadAvgMbps.toFixed(1)} / ${performance.operational.transfer.downloadAvgMbps.toFixed(1)} Mbps`} hint="파일 전송 운영 지표" />
            </div>
          </>
        )}
      </section>

      <div style={styles.summaryGrid} aria-label="보고서 요약">
        <MetricCard icon={<Gauge size={19} />} label="평균 가동률" value={`${uptime.averageUptimePct.toFixed(1)}%`} hint={`${uptime.groups.length}개 강의실 집계`} />
        <MetricCard icon={<ArrowDownToLine size={19} />} label="인바운드 합계" value={formatTrafficKbps(traffic.totalInboundKbps)} hint="기간 내 수집값 합계" />
        <MetricCard icon={<ArrowUpFromLine size={19} />} label="아웃바운드 합계" value={formatTrafficKbps(traffic.totalOutboundKbps)} hint="기간 내 수집값 합계" />
        <MetricCard icon={<FileBarChart size={19} />} label="월간 보고서" value={`${monthlyReports.length.toLocaleString('ko-KR')}건`} hint={monthlyReports[0] ? `최근 ${reportMonthLabel(monthlyReports[0].yearMonth)}` : '생성 기록 없음'} />
      </div>

      <div style={styles.sectionGrid}>
        <section style={styles.section} aria-labelledby="traffic-report-title">
          <header style={styles.sectionHeader}>
            <div>
              <h2 id="traffic-report-title" style={styles.sectionTitle}><Activity size={16} />트래픽 요약</h2>
              <p style={styles.subtext}>전체 수집값 {formatTrafficKbps(totalTraffic)}</p>
            </div>
          </header>
          {trafficQuery.isLoading && !trafficQuery.data ? (
            <PageLoading label="트래픽 보고서를 불러오는 중입니다." />
          ) : trafficQuery.isError && !trafficQuery.data ? (
            <PageError message={errorMessage(trafficQuery.error, '트래픽 보고서를 불러오지 못했습니다.')} onRetry={() => { void trafficQuery.refetch() }} />
          ) : (
            <>
              {trafficQuery.isError && <div className="form-error" role="alert">트래픽 갱신에 실패해 기존 데이터를 표시합니다.</div>}
              <div style={styles.slots}>
                {traffic.byTimeSlot.map((slot) => (
                  <div style={styles.slot} key={slot.key}>
                    <span style={styles.slotLabel}>{slot.label}</span>
                    <strong style={styles.slotValue}>{formatTrafficKbps(slot.totalKbps)}</strong>
                  </div>
                ))}
              </div>
              {!traffic.dailyInbound.length ? <EmptyState message="선택 기간의 트래픽 기록이 없습니다." /> : (
                <div className="table-scroll" aria-busy={trafficQuery.isFetching}>
                  <table className="data-table">
                    <thead><tr><th>날짜</th><th>인바운드 수집값</th></tr></thead>
                    <tbody>{traffic.dailyInbound.map((row) => (
                      <tr key={row.date} style={{ cursor: 'default' }}><td>{row.date}</td><td><strong>{formatTrafficKbps(row.inboundKbps)}</strong></td></tr>
                    ))}</tbody>
                  </table>
                </div>
              )}
            </>
          )}
        </section>

        <section style={styles.section} aria-labelledby="uptime-report-title">
          <header style={styles.sectionHeader}>
            <div>
              <h2 id="uptime-report-title" style={styles.sectionTitle}><Gauge size={16} />강의실 가동률</h2>
              <p style={styles.subtext}>평균 {uptime.averageUptimePct.toFixed(1)}%</p>
            </div>
          </header>
          {uptimeQuery.isLoading && !uptimeQuery.data ? (
            <PageLoading label="가동률 보고서를 불러오는 중입니다." />
          ) : uptimeQuery.isError && !uptimeQuery.data ? (
            <PageError message={errorMessage(uptimeQuery.error, '가동률 보고서를 불러오지 못했습니다.')} onRetry={() => { void uptimeQuery.refetch() }} />
          ) : !uptime.groups.length ? (
            <EmptyState message="선택 기간의 가동률 기록이 없습니다." />
          ) : (
            <>
              {uptimeQuery.isError && <div className="form-error" role="alert">가동률 갱신에 실패해 기존 데이터를 표시합니다.</div>}
              <div className="table-scroll" aria-busy={uptimeQuery.isFetching}>
                <table className="data-table">
                  <thead><tr><th>강의실</th><th>등록 PC</th><th>가동률</th><th>이상 탐지</th></tr></thead>
                  <tbody>{uptime.groups.map((row) => (
                    <tr key={row.groupId} style={{ cursor: 'default' }}>
                      <td><strong>{row.groupName}</strong><small style={{ display: 'block', color: 'var(--muted)' }}>평균 온라인 {row.avgOnline.toFixed(1)}회</small></td>
                      <td>{row.totalPc.toLocaleString('ko-KR')}대</td>
                      <td>
                        <strong>{row.uptimePct.toFixed(1)}%</strong>
                        <div style={styles.progressTrack} aria-hidden="true"><div style={{ ...styles.progressBar, width: `${row.uptimePct}%` }} /></div>
                      </td>
                      <td style={{ color: row.anomalyCount > 0 ? 'var(--red)' : 'var(--green)', fontWeight: 700 }}>
                        {row.anomalyCount.toLocaleString('ko-KR')}건
                      </td>
                    </tr>
                  ))}</tbody>
                </table>
              </div>
            </>
          )}
        </section>
      </div>

      <section aria-labelledby="monthly-report-title">
        <header style={styles.sectionHeader}>
          <div>
            <h2 id="monthly-report-title" style={styles.sectionTitle}><CalendarDays size={16} />월간 보고서</h2>
            <p style={styles.subtext}>월별 운영 집계 스냅샷</p>
          </div>
          {canRegenerate && (
            <div style={styles.monthlyActions}>
              <label style={{ ...styles.field, minWidth: 142 }}>
                <span style={styles.fieldLabel}>재생성 대상 월</span>
                <input style={styles.input} type="month" value={regenerateMonth} onChange={(event) => setRegenerateMonth(event.target.value)} />
              </label>
              {canRegenerate && <button className="button secondary" onClick={() => setPendingRegenerate(regenerateMonth)} disabled={!/^\d{4}-\d{2}$/.test(regenerateMonth)}>
                <RotateCcw size={15} />재생성
              </button>}
            </div>
          )}
        </header>
        {monthlyQuery.isLoading && !monthlyQuery.data ? (
          <PageLoading label="월간 보고서를 불러오는 중입니다." />
        ) : monthlyQuery.isError && !monthlyQuery.data ? (
          <PageError message={errorMessage(monthlyQuery.error, '월간 보고서를 불러오지 못했습니다.')} onRetry={() => { void monthlyQuery.refetch() }} />
        ) : !monthlyReports.length ? (
          <EmptyState message="생성된 월간 보고서가 없습니다." />
        ) : (
          <>
            {monthlyQuery.isError && <div className="form-error" role="alert">월간 보고서 갱신에 실패해 기존 데이터를 표시합니다.</div>}
            <MonthlyRows reports={monthlyReports} canWrite={canRegenerate} onRegenerate={setPendingRegenerate} />
          </>
        )}
      </section>

      <ConfirmDialog
        open={Boolean(pendingRegenerate)}
        title="월간 보고서 재생성"
        description={pendingRegenerate ? `${reportMonthLabel(pendingRegenerate)} 집계를 현재 저장된 데이터로 다시 생성합니다. 기존 집계 결과는 교체됩니다.` : ''}
        confirmLabel="재생성"
        busy={regenerateMutation.isPending}
        onCancel={() => { if (!regenerateMutation.isPending) setPendingRegenerate(null) }}
        onConfirm={() => { if (pendingRegenerate && !regenerateMutation.isPending) regenerateMutation.mutate(pendingRegenerate) }}
      />
    </section>
  )
}
