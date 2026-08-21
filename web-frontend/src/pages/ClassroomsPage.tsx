import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import {
  LayoutGrid,
  List,
  Download,
  Maximize,
  Monitor,
  PanelsTopLeft,
  Power,
  PowerOff,
  Printer,
  RefreshCw,
  Search,
  X,
} from 'lucide-react'
import { useAuth } from '../auth/AuthProvider'
import { ConfirmDialog } from '../components/ui/ConfirmDialog'
import { EmptyState, PageError, PageLoading } from '../components/ui/PageState'
import { useToast } from '../components/ui/ToastProvider'
import { useDialogFocus } from '../components/ui/useDialogFocus'
import { getClassroomDashboard, sendRoomCommand } from '../features/classrooms/api'
import { routerWolFailureMessage } from '../features/commands/routerWolMessage'
import { ClassroomScreenWall } from '../features/remoteControl/ClassroomScreenWall'
import {
  type RoomCounts,
  getRoomCounts,
  isBrokenPc,
  isOnlinePc,
  roomStatus,
  roomStatusLabel,
  seatLabel,
  sortPcs,
} from '../features/classrooms/model'
import type { ClassroomSummary, CommandResult, PcStatus, RoomStatus } from '../types/dashboard'

type ViewMode = 'grid' | 'table'
type CommandType = 'WOL' | 'SHUTDOWN'
interface PendingCommand { room: ClassroomSummary; type: CommandType }

function asNumber(value: unknown, fallback = 0) {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : fallback
  }
  return fallback
}

function csvCell(value: unknown) {
  let text = value == null ? '' : String(value)
  if (/^[=+\-@]/.test(text)) text = `'${text}`
  return `"${text.replace(/"/g, '""')}"`
}

function downloadCsv(rooms: ClassroomSummary[], pcMap: Map<number, PcStatus[]>) {
  const rows = [['강의실', '상태', '전체 PC', '온라인', '경고', '오프라인', '마지막 하트비트']]
  rooms.forEach((room) => {
    const counts = getRoomCounts(room, pcMap.get(room.groupId) || [])
    rows.push([room.groupName, roomStatusLabel[roomStatus(room, counts)], String(counts.total), String(counts.online), String(counts.broken), String(counts.offline), room.lastHeartbeat || ''])
  })
  const blob = new Blob([`\uFEFF${rows.map((row) => row.map(csvCell).join(',')).join('\r\n')}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = `classrooms-${new Date().toISOString().slice(0, 10)}.csv`
  anchor.click()
  URL.revokeObjectURL(url)
}

export function commandDeliveryMessage(roomName: string, type: CommandType, result: CommandResult) {
  const label = type === 'WOL' ? '전체 켜기' : '전체 종료'
  const requested = asNumber(result.requested)
  const queued = asNumber(result.queued)
  const accepted = asNumber(result.acceptedTargets)
  const failed = asNumber(result.failedTargets)
  const duplicate = asNumber(result.duplicateSkipped)
  const duplicateTargets = asNumber(result.duplicateTargets, duplicate)
  const offlineSkipped = asNumber(result.offlineSkipped)
  const notFound = asNumber(result.notFoundSkipped)
  const routerSignals = asNumber(result.routerSignals)
  const routerAttempts = asNumber(result.routerAttempts)
  const requestedTargets = requested > 0 ? requested : accepted + duplicateTargets + failed
  if (type === 'WOL' && routerSignals > 0) {
    const detailParts = [`신호 대상 ${accepted}대`, `공유기 ${routerSignals}건`]
    if (failed > 0) detailParts.push(`실패 ${failed}대`)
    if (duplicateTargets > 0) detailParts.push(`중복 제외 ${duplicateTargets}대`)
    return {
      message: `${roomName} 전체 켜기 ${requestedTargets}대 요청 (${detailParts.join(' / ')})`,
      isError: failed > 0,
    }
  }
  if (queued > 0 || accepted > 0) {
    const targetCount = requestedTargets > 0 ? requestedTargets : queued
    const detailParts: string[] = []
    if (accepted > 0) detailParts.push(`신호 대상 ${accepted}대`)
    if (queued > 0) detailParts.push(type === 'WOL' ? `전송 경로 ${queued}건` : `명령 ${queued}건 등록`)
    if (failed > 0) detailParts.push(`실패 ${failed}대`)
    if (duplicateTargets > 0) detailParts.push(`중복 제외 ${duplicateTargets}대`)
    if (offlineSkipped > 0) detailParts.push(`오프라인 제외 ${offlineSkipped}대`)
    if (notFound > 0) detailParts.push(`미대상 ${notFound}대`)
    if (routerAttempts > 0 && type === 'WOL') detailParts.push(`라우팅 시도 ${routerAttempts}건`)
    const detail = detailParts.length ? ` (${detailParts.join(' / ')})` : ''
    return {
      message: `${roomName} ${label} 명령 ${targetCount > 0 ? `${targetCount}대 ` : ''}요청${detail}`,
      isError: failed > 0 || notFound > 0,
    }
  }
  if (duplicate > 0) {
    return {
      message: '동일한 명령이 이미 진행 중입니다.',
      isError: true,
    }
  }
  const skippedParts: string[] = []
  if (failed > 0) skippedParts.push(`실패 ${failed}대`)
  if (offlineSkipped > 0) skippedParts.push(`오프라인 제외 ${offlineSkipped}대`)
  if (notFound > 0) skippedParts.push(`미대상 ${notFound}대`)
  if (skippedParts.length) {
    return {
      message: `${roomName} ${label} 명령 ${skippedParts.join(' / ')}`,
      isError: true,
    }
  }
  return {
    message: routerWolFailureMessage(result as { routerMessage?: string; routerAttempts?: number; message?: string }) || result.message || '명령 대상이 없습니다.',
    isError: true,
  }
}

function RoomStatusBar({ counts }: { counts: RoomCounts }) {
  const total = Math.max(1, counts.total)
  return (
    <div className="room-status-summary" aria-label={`온라인 ${counts.online}대, 고장 ${counts.broken}대, 오프라인 ${counts.offline}대`}>
      <div className="room-status-bar" aria-hidden="true">
        {counts.online > 0 && <span className="online" style={{ width: `${counts.online / total * 100}%` }} />}
        {counts.broken > 0 && <span className="broken" style={{ width: `${counts.broken / total * 100}%` }} />}
        {counts.offline > 0 && <span className="offline" style={{ width: `${counts.offline / total * 100}%` }} />}
      </div>
      <small><span>온라인 {counts.online}</span><span>고장 {counts.broken}</span><span>오프라인 {counts.offline}</span></small>
    </div>
  )
}

function RoomCard({ room, pcs, onOpen }: { room: ClassroomSummary; pcs: PcStatus[]; onOpen: () => void }) {
  const counts = getRoomCounts(room, pcs)
  const state = roomStatus(room, counts)
  const teachers = pcs.filter((pc) => pc.role?.toUpperCase() === 'TEACHER')
  const students = sortPcs(pcs.filter((pc) => pc.role?.toUpperCase() !== 'TEACHER'))
  const warningCount = counts.broken

  return (
    <button className={`room-card status-${state}`} onClick={onOpen} aria-label={`${room.groupName} 상세 보기`}>
      <header className="room-card-header">
        <div><h2>{room.groupName}</h2><p>{counts.total}/{counts.online}{warningCount > 0 && <span className="warning-count">경고 {warningCount}</span>}</p></div>
        <span className={`status-badge ${state}`}>{roomStatusLabel[state]}</span>
      </header>
      <div className="room-divider" />
      <div className={`teacher-tile ${teachers.some(isOnlinePc) || (!teachers.length && ['ONLINE', 'IDLE'].includes(room.teacherPcStatus?.toUpperCase() || '')) ? 'online' : 'offline'}`}>
        <Monitor size={14} />교수 PC
      </div>
      <div className="student-pc-grid">
        {students.map((pc) => (
          <span
            key={pc.macAddress || `${pc.pcName}-${pc.ipAddress}`}
            className={`pc-tile ${isBrokenPc(pc) ? 'broken' : isOnlinePc(pc) ? 'online' : 'offline'}`}
            title={`${pc.pcName} · ${pc.status || (pc.online ? 'ONLINE' : 'OFFLINE')}`}
          >
            {seatLabel(pc)}
          </span>
        ))}
      </div>
    </button>
  )
}

function RoomTable({ rooms, pcMap, onOpen }: {
  rooms: ClassroomSummary[]
  pcMap: Map<number, PcStatus[]>
  onOpen: (room: ClassroomSummary) => void
}) {
  return (
    <div className="table-scroll">
      <table className="data-table classroom-table">
        <thead><tr><th>강의실</th><th>상태</th><th>PC 현황</th><th>전체 PC</th><th>온라인</th><th>고장</th><th>마지막 하트비트</th></tr></thead>
        <tbody>
          {rooms.map((room) => {
            const counts = getRoomCounts(room, pcMap.get(room.groupId) || [])
            const state = roomStatus(room, counts)
            return (
              <tr key={room.groupId} onClick={() => onOpen(room)} tabIndex={0} onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); onOpen(room) }
              }}>
                <td><strong>{room.groupName}</strong></td>
                <td><span className={`status-badge ${state}`}>{roomStatusLabel[state]}</span></td>
                <td className="room-status-cell"><RoomStatusBar counts={counts} /></td>
                <td>{counts.total}</td><td>{counts.online}</td><td>{counts.broken}</td>
                <td>{room.lastHeartbeat ? new Date(room.lastHeartbeat).toLocaleString('ko-KR') : '-'}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function RoomDetail({ room, pcs, isAdmin, onClose, onCommand, onOpenScreenWall }: {
  room: ClassroomSummary
  pcs: PcStatus[]
  isAdmin: boolean
  onClose: () => void
  onCommand: (type: CommandType) => void
  onOpenScreenWall: () => void
}) {
  const [viewMode, setViewMode] = useState<'list' | 'grid'>('list')
  const sorted = sortPcs(pcs)
  const counts = getRoomCounts(room, sorted)
  const online = counts.online
  const broken = counts.broken
  const offline = counts.offline
  const latestSeen = sorted.reduce<Date | null>((latest, pc) => {
    if (!pc.lastSeen) return latest
    const candidate = new Date(pc.lastSeen)
    if (Number.isNaN(candidate.getTime())) return latest
    return !latest || candidate > latest ? candidate : latest
  }, null)
  const drawerRef = useDialogFocus<HTMLElement>(onClose)
  return (
    <>
      <button className="drawer-backdrop" onClick={onClose} aria-label="상세 닫기" />
      <aside ref={drawerRef} className="room-drawer" role="dialog" aria-modal="true" aria-label={`${room.groupName} 상세`} tabIndex={-1}>
        <header className="drawer-header">
          <div><h2 title={room.groupName}>{room.groupName}</h2><p>PC 상태 상세 · 마지막 갱신 {latestSeen ? latestSeen.toLocaleString('ko-KR') : '-'}</p></div>
          <button className="icon-button" onClick={onClose} aria-label="닫기"><X size={19} /></button>
        </header>
        <div className="drawer-metrics">
          <div><span className="metric-dot online" /><strong>{online}</strong><small>온라인</small></div>
          <div><span className="metric-dot warning" /><strong>{broken}</strong><small>경고</small></div>
          <div><span className="metric-dot offline" /><strong>{offline}</strong><small>오프라인</small></div>
        </div>
        <div className="drawer-section-title"><span>PC 목록</span><div className="segmented-control" aria-label="PC 보기 방식"><button className={viewMode === 'list' ? 'active' : ''} onClick={() => setViewMode('list')} aria-label="목록 보기"><List size={15} /></button><button className={viewMode === 'grid' ? 'active' : ''} onClick={() => setViewMode('grid')} aria-label="그리드 보기"><LayoutGrid size={15} /></button></div></div>
        <div className={viewMode === 'grid' ? 'drawer-pc-grid' : 'drawer-pc-list'}>
          {sorted.map((pc) => (
            <div className="drawer-pc-row" key={pc.macAddress || `${pc.pcName}-${pc.ipAddress}`}>
              <span className={`pc-state-mark ${isBrokenPc(pc) ? 'broken' : isOnlinePc(pc) ? 'online' : 'offline'}`} />
              <div><strong title={`${pc.role?.toUpperCase() === 'TEACHER' ? '교수 PC' : `${seatLabel(pc)}번`} · ${pc.pcName}`}>{pc.role?.toUpperCase() === 'TEACHER' ? '교수 PC' : `${seatLabel(pc)}번`} · {pc.pcName}</strong><small title={`${pc.ipAddress || '-'} · ${pc.agentVersion || '버전 정보 없음'}`}>{pc.ipAddress || '-'} · {pc.agentVersion || '버전 정보 없음'}</small></div>
              <span className="pc-state-text">{isBrokenPc(pc) ? '고장' : isOnlinePc(pc) ? '온라인' : '오프라인'}</span>
            </div>
          ))}
          {!sorted.length && <EmptyState message="등록된 PC가 없습니다." />}
        </div>
        {isAdmin && (
          <footer className="drawer-actions">
            <button className="button secondary" onClick={onOpenScreenWall}><PanelsTopLeft size={17} />화면 모니터링</button>
            <button className="button secondary" onClick={() => onCommand('WOL')}><Power size={17} />전체 켜기</button>
            <button className="button danger" onClick={() => onCommand('SHUTDOWN')}><PowerOff size={17} />전체 종료</button>
          </footer>
        )}
      </aside>
    </>
  )
}

export function ClassroomsPage() {
  const navigate = useNavigate()
  const { groupId: groupIdParam } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const queryClient = useQueryClient()
  const { isAdmin } = useAuth()
  const { showToast } = useToast()
  const [viewMode, setViewMode] = useState<ViewMode>('grid')
  const [search, setSearch] = useState(searchParams.get('q') || '')
  const [statusFilter, setStatusFilter] = useState<RoomStatus | 'all'>('all')
  const [pendingCommand, setPendingCommand] = useState<PendingCommand | null>(null)
  const [screenWallRoomId, setScreenWallRoomId] = useState<number | null>(null)

  useEffect(() => {
    const query = searchParams.get('q') || ''
    setSearch((current) => current === query ? current : query)
  }, [searchParams])

  const dashboardQuery = useQuery({
    queryKey: ['classrooms', 'dashboard'],
    queryFn: getClassroomDashboard,
    refetchInterval: 5_000,
  })
  const rooms = dashboardQuery.data?.rooms || []
  const pcs = dashboardQuery.data?.pcs || []
  const pcMap = useMemo(() => {
    const map = new Map<number, PcStatus[]>()
    const seen = new Set<string>()
    pcs.forEach((pc, index) => {
      if (pc.groupId == null) return
      const mac = pc.macAddress?.trim().toUpperCase()
      const ip = pc.ipAddress?.trim()
      const dedupeKey = mac
        ? `${pc.groupId}|mac:${mac}`
        : ip
          ? `${pc.groupId}|ip:${ip}`
          : `${pc.groupId}|row:${index}`
      if (seen.has(dedupeKey)) return
      seen.add(dedupeKey)
      map.set(pc.groupId, [...(map.get(pc.groupId) || []), pc])
    })
    return map
  }, [pcs])

  const onlinePcCount = useMemo(() => rooms.reduce(
    (sum, room) => sum + getRoomCounts(room, pcMap.get(room.groupId) || []).online,
    0,
  ), [pcMap, rooms])
  const refreshedAt = dashboardQuery.data?.fetchedAt
    ? new Date(dashboardQuery.data.fetchedAt).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
    : '-'

  const filteredRooms = useMemo(() => {
    const needle = search.trim().toLocaleLowerCase('ko-KR')
    return rooms.filter((room) => {
      const roomMatches = room.groupName.toLocaleLowerCase('ko-KR').includes(needle)
      const pcMatches = (pcMap.get(room.groupId) || []).some((pc) => [pc.pcName, pc.ipAddress, pc.macAddress]
        .some((value) => value?.toLocaleLowerCase('ko-KR').includes(needle)))
      if (needle && !roomMatches && !pcMatches) return false
      const counts = getRoomCounts(room, pcMap.get(room.groupId) || [])
      return statusFilter === 'all' || roomStatus(room, counts) === statusFilter
    })
  }, [pcMap, rooms, search, statusFilter])

  const selectedRoomId = groupIdParam && /^\d+$/.test(groupIdParam) ? Number(groupIdParam) : null
  const selectedRoom = rooms.find((room) => room.groupId === selectedRoomId) || null
  const commandMutation = useMutation({
    mutationFn: ({ room, type }: PendingCommand) => sendRoomCommand(room.groupId, type),
    onSuccess: (result, variables) => {
      const summary = commandDeliveryMessage(variables.room.groupName, variables.type, result)
      showToast(summary.message, summary.isError ? 'error' : 'success')
      setPendingCommand(null)
      void queryClient.invalidateQueries({ queryKey: ['classrooms', 'dashboard'] })
      window.setTimeout(() => {
        void queryClient.invalidateQueries({ queryKey: ['classrooms', 'dashboard'] })
      }, 2_000)
    },
    onError: (error) => showToast(error instanceof Error ? error.message : '명령을 전송하지 못했습니다.', 'error'),
  })

  const refresh = () => {
    void dashboardQuery.refetch()
  }

  const updateSearch = (value: string) => {
    setSearch(value)
    const next = new URLSearchParams(searchParams)
    if (value.trim()) next.set('q', value)
    else next.delete('q')
    setSearchParams(next, { replace: true })
  }
  const routeWithSearch = (path: string) => {
    const query = searchParams.toString()
    return query ? `${path}?${query}` : path
  }
  const toggleFullscreen = async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen()
      else await document.documentElement.requestFullscreen()
    } catch {
      showToast('브라우저에서 전체화면을 시작하지 못했습니다.', 'error')
    }
  }

  if (!dashboardQuery.data && dashboardQuery.isLoading) return <section className="content-section"><PageLoading /></section>
  if (!dashboardQuery.data && dashboardQuery.isError) {
    const error = dashboardQuery.error
    return <section className="content-section"><PageError message={error instanceof Error ? error.message : '현황을 불러오지 못했습니다.'} onRetry={refresh} /></section>
  }

  return (
    <section className="content-section classrooms-page">
      <header className="page-header classroom-toolbar-header">
        <div><h1>강의실 현황</h1><p>총 {rooms.length}개 강의실 · 온라인 PC {onlinePcCount}대 · {refreshedAt} 갱신</p></div>
        <div className="toolbar-actions">
          <label className="toolbar-search"><Search size={16} /><input value={search} onChange={(event) => updateSearch(event.target.value)} placeholder="강의실 검색" /></label>
          <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value as RoomStatus | 'all')} aria-label="상태 필터">
            <option value="all">전체 상태</option><option value="online">온라인</option><option value="running">명령 수행 중</option><option value="warning">경고</option><option value="offline">오프라인</option>
          </select>
          <div className="segmented-control" aria-label="보기 방식">
            <button className={viewMode === 'table' ? 'active' : ''} onClick={() => setViewMode('table')} aria-label="목록 보기"><List size={17} /></button>
            <button className={viewMode === 'grid' ? 'active' : ''} onClick={() => setViewMode('grid')} aria-label="그리드 보기"><LayoutGrid size={17} /></button>
          </div>
          <button className="icon-button bordered" onClick={refresh} disabled={dashboardQuery.isFetching} aria-label="새로고침"><RefreshCw size={17} className={dashboardQuery.isFetching ? 'spin-icon' : ''} /></button>
          <button className="icon-button bordered" onClick={() => downloadCsv(filteredRooms, pcMap)} aria-label="CSV 다운로드" title="CSV 다운로드"><Download size={17} /></button>
          <button className="icon-button bordered" onClick={() => window.print()} aria-label="인쇄" title="인쇄"><Printer size={17} /></button>
          <button className="icon-button bordered" onClick={() => void toggleFullscreen()} aria-label="전체화면" title="전체화면"><Maximize size={17} /></button>
        </div>
      </header>

      {dashboardQuery.isError && <div className="form-error" role="alert">새 데이터 갱신에 실패해 마지막으로 받은 현황을 표시하고 있습니다.</div>}

      {!filteredRooms.length ? <EmptyState message="조건에 맞는 강의실이 없습니다." /> : viewMode === 'grid' ? (
        <div className="room-grid">
          {filteredRooms.map((room) => <RoomCard key={room.groupId} room={room} pcs={pcMap.get(room.groupId) || []} onOpen={() => navigate(routeWithSearch(`/classrooms/${room.groupId}`))} />)}
        </div>
      ) : <RoomTable rooms={filteredRooms} pcMap={pcMap} onOpen={(room) => navigate(routeWithSearch(`/classrooms/${room.groupId}`))} />}

      {selectedRoom && <RoomDetail room={selectedRoom} pcs={pcMap.get(selectedRoom.groupId) || []} isAdmin={isAdmin} onClose={() => navigate(routeWithSearch('/classrooms'))} onCommand={(type) => setPendingCommand({ room: selectedRoom, type })} onOpenScreenWall={() => setScreenWallRoomId(selectedRoom.groupId)} />}
      {screenWallRoomId != null && rooms.find((room) => room.groupId === screenWallRoomId) && (
        <ClassroomScreenWall
          room={rooms.find((room) => room.groupId === screenWallRoomId)!}
          pcs={pcMap.get(screenWallRoomId) || []}
          onClose={() => setScreenWallRoomId(null)}
        />
      )}
      <ConfirmDialog
        open={Boolean(pendingCommand)}
        title={pendingCommand?.type === 'SHUTDOWN' ? '전체 종료 확인' : '전체 켜기 확인'}
        description={pendingCommand ? `${pendingCommand.room.groupName}의 ${pendingCommand.type === 'SHUTDOWN' ? `온라인 PC ${getRoomCounts(pendingCommand.room, pcMap.get(pendingCommand.room.groupId) || []).online}대를 종료합니다. 작업 중인 내용이 사라질 수 있습니다.` : `오프라인 PC ${getRoomCounts(pendingCommand.room, pcMap.get(pendingCommand.room.groupId) || []).offline}대에 Wake-on-LAN 신호를 전송합니다.`}` : ''}
        confirmLabel={pendingCommand?.type === 'SHUTDOWN' ? '전체 종료' : '전체 켜기'}
        danger={pendingCommand?.type === 'SHUTDOWN'}
        busy={commandMutation.isPending}
        onCancel={() => { if (!commandMutation.isPending) setPendingCommand(null) }}
        onConfirm={() => { if (pendingCommand && !commandMutation.isPending) commandMutation.mutate(pendingCommand) }}
      />
    </section>
  )
}
