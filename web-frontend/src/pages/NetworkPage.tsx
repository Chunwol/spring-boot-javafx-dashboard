import { keepPreviousData, useQuery } from '@tanstack/react-query'
import {
  Activity,
  ArrowDownToLine,
  ArrowUpFromLine,
  Download,
  Gauge,
  Maximize2,
  Minimize2,
  Printer,
  RefreshCw,
  Router,
} from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { useNavigate, useParams } from 'react-router-dom'
import { EmptyState, PageError, PageLoading } from '../components/ui/PageState'
import {
  getClassroomPcTraffic,
  getClassroomTrafficSummaries,
  getNetworkClassrooms,
  getNetworkTraffic,
  getTrafficThresholds,
} from '../features/network/api'
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
} from '../features/network/model'
import type { TrafficLevel } from '../features/network/model'
import type { TrafficHistoryPoint } from '../features/network/types'
import '../features/network/network.css'

const POLL_INTERVAL_MS = 15_000
const MAX_CHART_POINTS = 30

type StatusFilter = 'all' | 'normal' | 'anomaly'

const levelLabel = { normal: '정상', warning: '주의', critical: '임계 초과' } as const

function MetricCard({ icon, label, value, hint }: {
  icon: React.ReactNode
  label: string
  value: string
  hint: string
}) {
  return (
    <div className="network-metric">
      <span className="network-metric-icon">{icon}</span>
      <div>
        <span className="network-metric-label">{label}</span>
        <strong className="network-metric-value">{value}</strong>
        <small className="network-metric-hint">{hint}</small>
      </div>
    </div>
  )
}

function StatusBadge({ level }: { level: TrafficLevel | null }) {
  if (!level) return <span className="network-status unknown">판정 불가</span>
  return <span className={`network-status ${level}`}>{levelLabel[level]}</span>
}

function LoadIndicator({ inboundMbps, thresholdMbps }: {
  inboundMbps: number | null
  thresholdMbps: number | null
}) {
  if (inboundMbps === null) return <span className="network-unavailable">수집 중</span>
  if (thresholdMbps === null || thresholdMbps <= 0) return <span className="network-unavailable">임계값 없음</span>
  const ratio = trafficRatio(inboundMbps, thresholdMbps) ?? 0
  const level = trafficLevel(inboundMbps, thresholdMbps)
  return (
    <div className="network-load" aria-label={`인바운드 임계값 대비 ${Math.round(ratio * 100)}%`}>
      <span className="network-load-track"><span className={`network-load-fill ${level}`} style={{ width: `${Math.min(100, ratio * 100)}%` }} /></span>
      <strong>{(ratio * 100).toFixed(1)}%</strong>
    </div>
  )
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback
}

function downloadCsv(contents: string) {
  const blob = new Blob([contents], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `network_classroom_traffic_${new Date().toISOString().slice(0, 10)}.csv`
  anchor.click()
  URL.revokeObjectURL(url)
}

export function NetworkPage() {
  const navigate = useNavigate()
  const { groupId: groupIdParam } = useParams()
  const pageRef = useRef<HTMLElement>(null)
  const routeGroupId = groupIdParam && /^\d+$/.test(groupIdParam) ? Number(groupIdParam) : null
  const [selectedClassroomId, setSelectedClassroomId] = useState<number | null>(routeGroupId)
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all')
  const [history, setHistory] = useState<TrafficHistoryPoint[]>([])
  const [isFullscreen, setIsFullscreen] = useState(false)

  const trafficQuery = useQuery({
    queryKey: ['network', 'traffic'],
    queryFn: getNetworkTraffic,
    placeholderData: keepPreviousData,
    refetchInterval: POLL_INTERVAL_MS,
  })
  const thresholdsQuery = useQuery({
    queryKey: ['network', 'thresholds'],
    queryFn: getTrafficThresholds,
    placeholderData: keepPreviousData,
    staleTime: 60_000,
    refetchInterval: 5 * 60_000,
  })
  const classroomsQuery = useQuery({
    queryKey: ['network', 'classrooms'],
    queryFn: getNetworkClassrooms,
    placeholderData: keepPreviousData,
    refetchInterval: 60_000,
  })
  const classrooms = classroomsQuery.data ?? []

  const classroomSummaryQuery = useQuery({
    queryKey: ['network', 'classroom-traffic-summary'],
    queryFn: getClassroomTrafficSummaries,
    placeholderData: keepPreviousData,
    refetchInterval: 60_000,
  })

  useEffect(() => {
    if (!classroomsQuery.data) return
    const routeExists = routeGroupId !== null && classrooms.some((room) => room.groupId === routeGroupId)
    const next = routeExists ? routeGroupId : classrooms[0]?.groupId ?? null
    setSelectedClassroomId(next)
    if (groupIdParam && !routeExists) {
      navigate(next === null ? '/network' : `/network/${next}`, { replace: true })
    }
  }, [classrooms, classroomsQuery.data, groupIdParam, navigate, routeGroupId])

  useEffect(() => {
    if (!trafficQuery.data || trafficQuery.dataUpdatedAt <= 0) return
    setHistory((current) => appendTrafficHistory(
      current,
      trafficQuery.data,
      trafficQuery.dataUpdatedAt,
      MAX_CHART_POINTS,
    ))
  }, [trafficQuery.data, trafficQuery.dataUpdatedAt])

  useEffect(() => {
    const handleFullscreenChange = () => setIsFullscreen(document.fullscreenElement === pageRef.current)
    document.addEventListener('fullscreenchange', handleFullscreenChange)
    return () => document.removeEventListener('fullscreenchange', handleFullscreenChange)
  }, [])

  const traffic = trafficQuery.data ? normalizeNetworkTraffic(trafficQuery.data) : null
  const thresholds = thresholdsQuery.data ? normalizeThresholds(thresholdsQuery.data) : null
  const classroomRows = classroomSummaryQuery.data ?? classrooms.map((room) => ({
    groupId: room.groupId,
    groupName: room.groupName,
    onlinePcs: room.onlinePcs,
    totalPcs: room.totalPcs,
    inboundMbps: null,
    outboundMbps: null,
    updatedAt: null,
  }))
  const filteredClassroomRows = classroomRows.filter((row) => {
    if (statusFilter === 'all') return true
    if (row.inboundMbps === null || !thresholds) return false
    const level = trafficLevel(row.inboundMbps, thresholds.classroomMbps)
    return statusFilter === 'normal' ? level === 'normal' : level !== 'normal'
  })

  const selectedClassroom = classrooms.find((room) => room.groupId === selectedClassroomId) ?? null
  const selectedTrafficQuery = useQuery({
    queryKey: ['network', 'classroom-pcs', selectedClassroomId],
    queryFn: () => getClassroomPcTraffic(selectedClassroomId as number),
    placeholderData: keepPreviousData,
    enabled: selectedClassroomId != null,
    refetchInterval: POLL_INTERVAL_MS,
  })
  const pcRows = useMemo(
    () => normalizePcTraffic(selectedTrafficQuery.data),
    [selectedTrafficQuery.data],
  )
  const selectedSummary = classroomRows.find((row) => row.groupId === selectedClassroomId) ?? null
  const classroomInbound = selectedSummary?.inboundMbps ?? null
  const classroomOutbound = selectedSummary?.outboundMbps ?? null
  const globalLevel = traffic && thresholds
    ? trafficLevel(traffic.inboundMbps, thresholds.globalMbps)
    : null
  const classroomLevel = classroomInbound !== null && thresholds
    ? trafficLevel(classroomInbound, thresholds.classroomMbps)
    : null

  const partialErrors = [
    trafficQuery.isError ? '전체 트래픽' : null,
    thresholdsQuery.isError ? '임계값' : null,
    classroomsQuery.isError ? '강의실 목록' : null,
    classroomSummaryQuery.isError ? '강의실 트래픽 요약' : null,
    selectedTrafficQuery.isError ? '선택 강의실 PC 트래픽' : null,
  ].filter(Boolean)
  const isRefreshing = trafficQuery.isFetching
    || thresholdsQuery.isFetching
    || classroomsQuery.isFetching
    || classroomSummaryQuery.isFetching
    || selectedTrafficQuery.isFetching

  const refresh = () => {
    void trafficQuery.refetch()
    void thresholdsQuery.refetch()
    void classroomsQuery.refetch()
    void classroomSummaryQuery.refetch()
    if (selectedClassroomId != null) void selectedTrafficQuery.refetch()
  }

  const toggleFullscreen = async () => {
    if (document.fullscreenElement) {
      await document.exitFullscreen()
    } else {
      await pageRef.current?.requestFullscreen()
    }
  }

  const isInitialLoading = !trafficQuery.data && !thresholdsQuery.data && !classroomsQuery.data
    && (trafficQuery.isPending || thresholdsQuery.isPending || classroomsQuery.isPending)
  const allInitialRequestsFailed = trafficQuery.isError
    && thresholdsQuery.isError
    && classroomsQuery.isError
    && !trafficQuery.data
    && !thresholdsQuery.data
    && !classroomsQuery.data

  if (isInitialLoading) {
    return <section className="content-section"><PageLoading label="네트워크 현황을 불러오는 중입니다." /></section>
  }
  if (allInitialRequestsFailed) {
    return (
      <section className="content-section">
        <PageError message={errorMessage(trafficQuery.error, '네트워크 현황을 불러오지 못했습니다.')} onRetry={refresh} />
      </section>
    )
  }

  return (
    <section ref={pageRef} className={`content-section network-page${isFullscreen ? ' network-fullscreen' : ''}`}>
      <header className="page-header network-page-header">
        <div>
          <h1>네트워크 현황</h1>
          <p>전체와 선택 강의실은 15초, 강의실 요약은 60초마다 갱신합니다.</p>
        </div>
        <div className="toolbar-actions network-page-actions">
          <span className="network-refresh-state" aria-live="polite">
            {isRefreshing ? '갱신 중' : trafficQuery.dataUpdatedAt > 0 ? `${formatUpdatedAt(new Date(trafficQuery.dataUpdatedAt).toISOString())} 갱신` : '갱신 기록 없음'}
          </span>
          <button className="icon-button bordered" onClick={() => downloadCsv(createClassroomTrafficCsv(filteredClassroomRows, thresholds?.classroomMbps ?? null))} disabled={!filteredClassroomRows.length} aria-label="CSV 내보내기" title="CSV 내보내기">
            <Download size={17} />
          </button>
          <button className="icon-button bordered" onClick={() => window.print()} aria-label="인쇄" title="인쇄">
            <Printer size={17} />
          </button>
          <button className="icon-button bordered" onClick={() => { void toggleFullscreen() }} aria-label={isFullscreen ? '전체화면 종료' : '전체화면'} title={isFullscreen ? '전체화면 종료' : '전체화면'}>
            {isFullscreen ? <Minimize2 size={17} /> : <Maximize2 size={17} />}
          </button>
          <button className="icon-button bordered" onClick={refresh} disabled={isRefreshing} aria-label="새로고침" title="새로고침">
            <RefreshCw size={17} className={isRefreshing ? 'spin-icon' : ''} />
          </button>
        </div>
      </header>

      {partialErrors.length > 0 && (
        <div role="alert" className="network-partial-error">
          <strong>일부 정보를 갱신하지 못했습니다.</strong>
          <span>{partialErrors.join(', ')}{trafficQuery.data || classroomsQuery.data ? '의 마지막 정상 데이터를 유지합니다.' : '을 다시 확인해 주세요.'}</span>
        </div>
      )}

      <div className="network-summary-grid" aria-label="전체 네트워크 요약">
        <MetricCard icon={<ArrowDownToLine size={19} />} label="전체 인바운드" value={traffic ? formatMbps(traffic.inboundMbps) : trafficQuery.isError ? '조회 실패' : '불러오는 중'} hint="현재 수집값" />
        <MetricCard icon={<ArrowUpFromLine size={19} />} label="전체 아웃바운드" value={traffic ? formatMbps(traffic.outboundMbps) : trafficQuery.isError ? '조회 실패' : '불러오는 중'} hint="현재 수집값" />
        <MetricCard icon={<Activity size={19} />} label="전체 합계" value={traffic ? formatMbps(trafficTotal(traffic)) : trafficQuery.isError ? '조회 실패' : '불러오는 중'} hint="인바운드 + 아웃바운드" />
        <MetricCard icon={<Gauge size={19} />} label="전체 상태" value={globalLevel ? levelLabel[globalLevel] : '판정 불가'} hint={traffic && thresholds && thresholds.globalMbps > 0 ? `인바운드 임계값 대비 ${Math.round((traffic.inboundMbps / thresholds.globalMbps) * 100)}%` : '임계값 또는 트래픽 없음'} />
      </div>

      <div className="network-chart-section">
        <div className="network-section-heading">
          <div>
            <h2>인바운드 / 아웃바운드 이력</h2>
            <p>최근 {MAX_CHART_POINTS}회 수집값</p>
          </div>
          <StatusBadge level={globalLevel} />
        </div>
        <div className="network-chart" aria-label="전체 트래픽 이력 차트">
          {history.length === 0 ? (
            <EmptyState message="트래픽 수집값을 기다리는 중입니다." />
          ) : (
            <ResponsiveContainer width="100%" height="100%" minWidth={0}>
              <AreaChart data={history} margin={{ top: 10, right: 12, left: 0, bottom: 0 }}>
                <defs>
                  <linearGradient id="networkInbound" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#2f6fed" stopOpacity={0.24} />
                    <stop offset="95%" stopColor="#2f6fed" stopOpacity={0.02} />
                  </linearGradient>
                  <linearGradient id="networkOutbound" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#0f9f77" stopOpacity={0.2} />
                    <stop offset="95%" stopColor="#0f9f77" stopOpacity={0.02} />
                  </linearGradient>
                </defs>
                <CartesianGrid stroke="#e8edf3" strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="label" tick={{ fontSize: 10, fill: '#708096' }} minTickGap={30} axisLine={false} tickLine={false} />
                <YAxis tick={{ fontSize: 10, fill: '#708096' }} width={52} axisLine={false} tickLine={false} tickFormatter={(value) => `${value}M`} />
                <Tooltip formatter={(value) => formatMbps(Number(value))} labelStyle={{ color: '#243247', fontWeight: 700 }} />
                <Legend wrapperStyle={{ fontSize: 11 }} />
                <Area type="monotone" dataKey="inboundMbps" name="인바운드" stroke="#2f6fed" fill="url(#networkInbound)" strokeWidth={2} isAnimationActive={false} />
                <Area type="monotone" dataKey="outboundMbps" name="아웃바운드" stroke="#0f9f77" fill="url(#networkOutbound)" strokeWidth={2} isAnimationActive={false} />
              </AreaChart>
            </ResponsiveContainer>
          )}
        </div>
      </div>

      <div className="network-section-heading network-classroom-heading">
        <div>
          <h2>강의실별 부하</h2>
          <p>상태는 인바운드 임계값을 기준으로 판정합니다.</p>
        </div>
        <div className="network-filter" role="group" aria-label="강의실 상태 필터">
          {([['all', '전체'], ['normal', '정상'], ['anomaly', '이상']] as const).map(([value, label]) => (
            <button key={value} type="button" className={statusFilter === value ? 'active' : ''} aria-pressed={statusFilter === value} onClick={() => setStatusFilter(value)}>{label}</button>
          ))}
        </div>
      </div>

      {!classroomsQuery.data && classroomsQuery.isPending ? (
        <PageLoading label="강의실 부하를 불러오는 중입니다." />
      ) : !classroomsQuery.data && classroomsQuery.isError ? (
        <PageError message={errorMessage(classroomsQuery.error, '강의실 목록을 불러오지 못했습니다.')} onRetry={() => { void classroomsQuery.refetch() }} />
      ) : !classrooms.length ? (
        <EmptyState message="등록된 강의실이 없습니다." />
      ) : !filteredClassroomRows.length ? (
        <EmptyState message="선택한 상태에 해당하는 강의실이 없습니다." />
      ) : (
        <div className="table-scroll network-classroom-table-wrap">
          <table className="data-table network-classroom-table" aria-label="강의실별 네트워크 부하">
            <thead>
              <tr><th>강의실</th><th>PC</th><th>인바운드</th><th>아웃바운드</th><th>인바운드 부하</th><th>상태</th><th>갱신</th></tr>
            </thead>
            <tbody>
              {filteredClassroomRows.map((row) => {
                const level = row.inboundMbps !== null && thresholds
                  ? trafficLevel(row.inboundMbps, thresholds.classroomMbps)
                  : null
                return (
                  <tr key={row.groupId} className={selectedClassroomId === row.groupId ? 'selected' : ''} onClick={() => navigate(`/network/${row.groupId}`)} aria-current={selectedClassroomId === row.groupId ? 'true' : undefined}>
                    <td><strong>{row.groupName}</strong></td>
                    <td>{row.onlinePcs} / {row.totalPcs}</td>
                    <td>{row.inboundMbps === null ? classroomSummaryQuery.isError ? '수집 실패' : '수집 중' : formatMbps(row.inboundMbps)}</td>
                    <td>{row.outboundMbps === null ? classroomSummaryQuery.isError ? '수집 실패' : '수집 중' : formatMbps(row.outboundMbps)}</td>
                    <td><LoadIndicator inboundMbps={row.inboundMbps} thresholdMbps={thresholds?.classroomMbps ?? null} /></td>
                    <td><StatusBadge level={level} /></td>
                    <td>{row.updatedAt ? formatUpdatedAt(row.updatedAt) : '-'}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      <div className="network-detail-section">
        <div className="network-section-heading">
          <div>
            <h2>{selectedClassroom ? `${selectedClassroom.groupName} PC 상세` : '강의실 PC 상세'}</h2>
            <p>{selectedClassroom ? `${selectedClassroom.onlinePcs} / ${selectedClassroom.totalPcs}대 온라인` : '강의실을 선택해 주세요.'}</p>
          </div>
          <div className="toolbar-actions">
            {selectedSummary && (
              <span className="network-detail-total">
                인 {classroomInbound === null ? '-' : formatMbps(classroomInbound)} · 아웃 {classroomOutbound === null ? '-' : formatMbps(classroomOutbound)}
              </span>
            )}
            <StatusBadge level={classroomLevel} />
            <label>
              <span className="sr-only">강의실 선택</span>
              <select
                value={selectedClassroomId ?? ''}
                onChange={(event) => navigate(event.target.value ? `/network/${event.target.value}` : '/network')}
                disabled={!classrooms.length}
              >
                {!classrooms.length && <option value="">등록된 강의실 없음</option>}
                {classrooms.map((room) => <option key={room.groupId} value={room.groupId}>{room.groupName}</option>)}
              </select>
            </label>
          </div>
        </div>

        {!selectedClassroom ? (
          <EmptyState message="조회할 강의실이 없습니다." />
        ) : selectedTrafficQuery.isPending && !selectedTrafficQuery.data ? (
          <PageLoading label="PC별 트래픽을 불러오는 중입니다." />
        ) : selectedTrafficQuery.isError && !selectedTrafficQuery.data ? (
          <PageError message={errorMessage(selectedTrafficQuery.error, 'PC별 트래픽을 불러오지 못했습니다.')} onRetry={() => { void selectedTrafficQuery.refetch() }} />
        ) : !pcRows.length ? (
          <EmptyState message="최근 10분 동안 수집된 PC 트래픽이 없습니다." />
        ) : (
          <div className="table-scroll" aria-busy={selectedTrafficQuery?.isFetching}>
            <table className="data-table network-pc-table" aria-label="선택 강의실 PC별 네트워크 부하">
              <thead>
                <tr><th>PC 이름</th><th>IP 주소</th><th>상태</th><th>인바운드</th><th>아웃바운드</th><th>합계</th><th>인바운드 부하</th><th>수집 시각</th></tr>
              </thead>
              <tbody>
                {pcRows.map((row) => {
                  return (
                    <tr key={row.macAddress}>
                      <td><strong>{row.pcName}</strong><br /><small className="network-mac">{row.macAddress}</small></td>
                      <td>{row.ipAddress || '-'}</td>
                      <td><span className={`network-pc-state ${row.status?.toUpperCase() === 'ONLINE' ? 'online' : ''}`}>{row.status?.toUpperCase() === 'ONLINE' ? '온라인' : row.status || '상태 없음'}</span></td>
                      <td>{formatMbps(row.inboundMbps)}</td>
                      <td>{formatMbps(row.outboundMbps)}</td>
                      <td>{formatMbps(trafficTotal(row))}</td>
                      <td><LoadIndicator inboundMbps={row.inboundMbps} thresholdMbps={thresholds?.pcMbps ?? null} /></td>
                      <td>{formatUpdatedAt(row.updatedAt)}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="network-threshold-note">
        <Router size={15} />
        <span>임계값</span>
        <strong>전체 {thresholds ? formatMbps(thresholds.globalMbps) : '조회 실패'}</strong>
        <strong>강의실 {thresholds ? formatMbps(thresholds.classroomMbps) : '조회 실패'}</strong>
        <strong>PC {thresholds ? formatMbps(thresholds.pcMbps) : '조회 실패'}</strong>
        <small>모든 상태 판정은 인바운드 기준</small>
      </div>
    </section>
  )
}
