import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Eye, Monitor, Power, PowerOff, RefreshCw, RotateCw, Search, Shield, UploadCloud, Wrench } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthProvider'
import { scmatePermission } from '../auth/permissions'
import { ConfirmDialog } from '../components/ui/ConfirmDialog'
import { EmptyState, PageError, PageLoading } from '../components/ui/PageState'
import { useToast } from '../components/ui/ToastProvider'
import { getPcs, sendPcCommands } from '../features/pcs/api'
import { PcOperationsDialog } from '../features/pcs/PcOperationsDialog'
import { ScmateControlDialog, ScmateStatusBadge } from '../features/pcs/ScmateControlDialog'
import { RemoteControlDialog } from '../features/remoteControl/RemoteControlDialog'
import {
  commandLabel,
  commandTargets,
  filterAndSortPcs,
  isSelectablePc,
  allowedScmateActions,
  pcRole,
  pcRoleLabel,
  pcState,
  pcStateLabel,
  selectionState,
} from '../features/pcs/model'
import type { PcFilters, PcPowerCommand } from '../features/pcs/model'
import type { PcStatus } from '../types/dashboard'

const PAGE_SIZE = 100

interface PendingCommand {
  type: PcPowerCommand
  targets: PcStatus[]
  excludedCount: number
  bulk: boolean
}

function SelectAllCheckbox({ checked, indeterminate, disabled, onChange }: {
  checked: boolean
  indeterminate: boolean
  disabled: boolean
  onChange: () => void
}) {
  const ref = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = indeterminate
  }, [indeterminate])
  return <input ref={ref} type="checkbox" checked={checked} disabled={disabled} onChange={onChange} aria-label="조건 결과 전체 선택" />
}

function formatLastSeen(value: string | null | undefined) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ko-KR')
}

function commandDescription(pending: PendingCommand | null) {
  if (!pending) return ''
  const count = pending.targets.length
  const excluded = pending.excludedCount > 0 ? ` 조건에 맞지 않는 ${pending.excludedCount}대는 제외됩니다.` : ''
  if (pending.type === 'PCON') {
    return `${pending.bulk ? '선택한 PC 중 ' : ''}오프라인 PC ${count}대에 켜기 신호를 전송합니다.${excluded}`
  }
  const action = pending.type === 'REBOOT' ? '재시작' : '종료'
  return `${pending.bulk ? '선택한 PC 중 ' : ''}온라인 PC ${count}대를 ${action}합니다. 진행 중인 작업 내용이 사라질 수 있습니다.${excluded}`
}

export function PcsPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { hasPermission } = useAuth()
  const canPowerOn = hasPermission('POWER_ON')
  const canReboot = hasPermission('REBOOT')
  const canShutdown = hasPermission('SHUTDOWN')
  const canRemote = hasPermission('REMOTE_SCREEN_VIEW')
  const canPcOperations = hasPermission('COMMAND_ISSUE') && hasPermission('FILE_UPLOAD_DISTRIBUTE')
  const canAgentUpdate = hasPermission('AGENT_UPDATE') && hasPermission('FILE_DISTRIBUTION_VIEW')
  const canScmate = ['SCMATE_STATUS_VIEW', 'SCMATE_OPERATOR_MODE', 'SCMATE_BASELINE_UPDATE',
    'SCMATE_PROTECTED_MODE', 'SCMATE_SYSTEM_RESTORE', 'SCMATE_RECOVERY_MODULE_INSTALL',
    'SCMATE_EXCEPTION_FOLDER_MANAGE']
    .some((permission) => hasPermission(permission as Parameters<typeof hasPermission>[0]))
  const { showToast } = useToast()
  const [filters, setFilters] = useState<PcFilters>({ search: '', groupId: 'all', state: 'all', role: 'all', scmate: 'all' })
  const [selectedMacs, setSelectedMacs] = useState<Set<string>>(() => new Set())
  const [page, setPage] = useState(0)
  const [pendingCommand, setPendingCommand] = useState<PendingCommand | null>(null)
  const [operationTargets, setOperationTargets] = useState<PcStatus[]>([])
  const [viewerTarget, setViewerTarget] = useState<PcStatus | null>(null)
  const [remoteTarget, setRemoteTarget] = useState<PcStatus | null>(null)
  const [scmateTargetMac, setScmateTargetMac] = useState<string | null>(null)

  const pcsQuery = useQuery({
    queryKey: ['pcs'],
    queryFn: getPcs,
    staleTime: 10_000,
    refetchInterval: 30_000,
  })
  const pcs = pcsQuery.data || []
  const scmateTarget = scmateTargetMac ? pcs.find((pc) => pc.macAddress === scmateTargetMac) || null : null
  const scmateClassroomPcs = scmateTarget && pcRole(scmateTarget) === 'teacher' && scmateTarget.groupId != null
    ? pcs.filter((pc) => pc.groupId === scmateTarget.groupId)
    : undefined
  const filteredPcs = useMemo(() => filterAndSortPcs(pcs, filters), [pcs, filters])
  const pageCount = Math.max(1, Math.ceil(filteredPcs.length / PAGE_SIZE))
  const visiblePcs = filteredPcs.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE)
  const selectState = useMemo(() => selectionState(filteredPcs, selectedMacs), [filteredPcs, selectedMacs])
  const selectedPcs = useMemo(() => filteredPcs.filter((pc) => selectedMacs.has(pc.macAddress)), [filteredPcs, selectedMacs])
  const selectedRolloutMacs = useMemo(() => selectedPcs.filter((pc) => pc.online && !pc.brokenNote).map((pc) => pc.macAddress), [selectedPcs])
  const commandCandidates = selectedPcs.length ? selectedPcs : filteredPcs
  const candidateLabel = selectedPcs.length ? '선택' : '검색 결과'
  const candidateCommands = useMemo(() => ({
    powerOn: commandTargets(commandCandidates, 'PCON').targets.length,
    reboot: commandTargets(commandCandidates, 'REBOOT').targets.length,
    shutdown: commandTargets(commandCandidates, 'SHUTDOWN').targets.length,
  }), [commandCandidates])
  const groups = useMemo(() => {
    const entries = new Map<number, string>()
    for (const pc of pcs) if (pc.groupId != null) entries.set(pc.groupId, pc.groupName || `${pc.groupId}`)
    return [...entries].sort((left, right) => left[1].localeCompare(right[1], 'ko', { numeric: true }))
  }, [pcs])
  const counts = useMemo(() => ({
    online: pcs.filter((pc) => pcState(pc) === 'online').length,
    offline: pcs.filter((pc) => pcState(pc) === 'offline').length,
    broken: pcs.filter((pc) => pcState(pc) === 'broken').length,
  }), [pcs])

  useEffect(() => setPage(0), [filters])
  useEffect(() => {
    if (page >= pageCount) setPage(pageCount - 1)
  }, [page, pageCount])
  useEffect(() => {
    if (!pcsQuery.data) return
    const currentMacs = new Set(pcsQuery.data.filter(isSelectablePc).map((pc) => pc.macAddress))
    setSelectedMacs((current) => {
      const next = new Set([...current].filter((mac) => currentMacs.has(mac)))
      return next.size === current.size ? current : next
    })
  }, [pcsQuery.data])

  const commandMutation = useMutation({
    mutationFn: (pending: PendingCommand) => sendPcCommands(pending.targets, pending.type),
    onSuccess: (summary, pending) => {
      const label = commandLabel(pending.type)
      if (summary.queued > 0 && summary.failed === 0) {
        showToast(`${label} 명령 ${summary.queued}건을 전송했습니다.`)
      } else if (summary.queued > 0) {
        showToast(`${label} 명령 ${summary.queued}건 전송, ${summary.failed}건 실패했습니다.`, 'error')
      } else if (summary.duplicateSkipped > 0) {
        showToast('동일한 전원 명령이 이미 진행 중입니다.', 'error')
      } else {
        showToast(summary.messages[0] || `${label} 명령을 전송하지 못했습니다.`, 'error')
      }
      setSelectedMacs((current) => {
        const next = new Set(current)
        pending.targets.forEach((pc) => next.delete(pc.macAddress))
        return next
      })
      setPendingCommand(null)
      window.setTimeout(() => void queryClient.invalidateQueries({ queryKey: ['pcs'] }), 1_000)
    },
    onError: (error) => showToast(error instanceof Error ? error.message : '명령을 전송하지 못했습니다.', 'error'),
  })

  const openCommand = (type: PcPowerCommand, candidates: PcStatus[], bulk: boolean) => {
    const permitted = type === 'PCON' ? canPowerOn : type === 'REBOOT' ? canReboot : canShutdown
    if (!permitted) {
      showToast(`${commandLabel(type)} 권한이 없습니다.`, 'error')
      return
    }
    const preview = commandTargets(candidates, type)
    if (!preview.targets.length) {
      showToast(type === 'PCON' ? '켜기 명령을 보낼 오프라인 PC가 없습니다.' : '명령을 보낼 온라인 PC가 없습니다.', 'error')
      return
    }
    setPendingCommand({ type, targets: preview.targets, excludedCount: preview.excluded.length, bulk })
  }

  const togglePc = (macAddress: string) => {
    setSelectedMacs((current) => {
      const next = new Set(current)
      if (next.has(macAddress)) next.delete(macAddress)
      else next.add(macAddress)
      return next
    })
  }

  const toggleFiltered = () => {
    setSelectedMacs((current) => {
      const next = new Set(current)
      if (selectState.checked) selectState.selectableMacs.forEach((mac) => next.delete(mac))
      else selectState.selectableMacs.forEach((mac) => next.add(mac))
      return next
    })
  }

  const refresh = () => void pcsQuery.refetch()
  const openScmate = (pc: PcStatus) => setScmateTargetMac(pc.macAddress)
  const updateFilter = <Key extends keyof PcFilters>(key: Key, value: PcFilters[Key]) => {
    setFilters((current) => ({ ...current, [key]: value }))
  }

  if (!pcsQuery.data && pcsQuery.isLoading) return <section className="content-section"><PageLoading /></section>
  if (!pcsQuery.data && pcsQuery.isError) {
    return <section className="content-section"><PageError message={pcsQuery.error instanceof Error ? pcsQuery.error.message : 'PC 목록을 불러오지 못했습니다.'} onRetry={refresh} /></section>
  }

  return (
    <section className="content-section">
      <header className="page-header classroom-toolbar-header">
        <div>
          <h1>PC 관리</h1>
          <p>전체 {pcs.length}대 · 온라인 {counts.online} · 오프라인 {counts.offline} · 고장 {counts.broken}</p>
        </div>
        <div className="toolbar-actions">
          <label className="toolbar-search"><Search size={16} /><input value={filters.search} onChange={(event) => updateFilter('search', event.target.value)} placeholder="PC명, IP, MAC 검색" /></label>
          <select value={filters.groupId} onChange={(event) => updateFilter('groupId', event.target.value === 'all' ? 'all' : Number(event.target.value))} aria-label="강의실 필터">
            <option value="all">전체 강의실</option>
            {groups.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
          </select>
          <select value={filters.state} onChange={(event) => updateFilter('state', event.target.value as PcFilters['state'])} aria-label="상태 필터">
            <option value="all">전체 상태</option><option value="online">온라인</option><option value="offline">오프라인</option><option value="broken">고장</option>
          </select>
          <select value={filters.role} onChange={(event) => updateFilter('role', event.target.value as PcFilters['role'])} aria-label="역할 필터">
            <option value="all">전체 역할</option><option value="teacher">교사</option><option value="student">학생</option>
          </select>
          <select value={filters.scmate} onChange={(event) => updateFilter('scmate', event.target.value as PcFilters['scmate'])} aria-label="SCMATE 설정 필터">
            <option value="all">SCMATE 전체</option>
            <optgroup label="모드">
              <option value="server">서버</option><option value="operator">운영자 모드</option><option value="protected">복구 모드</option><option value="transition">전환 중</option>
            </optgroup>
            <optgroup label="복구 주기">
              <option value="restart">매 부팅</option><option value="daily-first-boot">매일 첫 부팅</option><option value="weekly">요일 복구</option>
            </optgroup>
            <optgroup label="복구 시점">
              <option value="baseline">기본 시점</option><option value="snap1">시점 1</option><option value="snap2">시점 2</option>
            </optgroup>
            <optgroup label="상태">
              <option value="not-installed">설치안됨</option><option value="disabled">비활성</option><option value="license-expired">라이선스 만료</option><option value="error">오류·조회 불가</option><option value="unknown">확인 안 됨</option>
            </optgroup>
          </select>
          <button className="icon-button bordered" onClick={refresh} disabled={pcsQuery.isFetching} aria-label="새로고침"><RefreshCw size={17} className={pcsQuery.isFetching ? 'spin-icon' : ''} /></button>
        </div>
      </header>

      {pcsQuery.isError && <div className="form-error" role="alert">새 데이터 갱신에 실패해 마지막으로 받은 목록을 표시하고 있습니다.</div>}

      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, minHeight: 54, padding: '8px 0', borderTop: '1px solid var(--border)' }}>
        <strong style={{ fontSize: 12 }}>{selectedPcs.length > 0 ? `${selectedPcs.length}대 선택` : `검색 결과 ${filteredPcs.length}대`}</strong>
        <div style={{ display: 'flex', flexWrap: 'wrap', justifyContent: 'flex-end', gap: 8 }}>
          {canPcOperations && <button className="button secondary" disabled={!selectedPcs.some((pc) => pc.online && !pc.brokenNote)} onClick={() => setOperationTargets(selectedPcs)}><Wrench size={16} />선택 작업</button>}
          {canAgentUpdate && <button className="button secondary" disabled={!selectedRolloutMacs.length} onClick={() => navigate('/files', { state: { targetMacs: selectedRolloutMacs } })}><UploadCloud size={16} />선택 에이전트 배포</button>}
          {canPowerOn && <button className="button secondary" disabled={candidateCommands.powerOn === 0 || commandMutation.isPending} onClick={() => openCommand('PCON', commandCandidates, true)}><Power size={16} />{candidateLabel} 켜기</button>}
          {canReboot && <button className="button danger" disabled={candidateCommands.reboot === 0 || commandMutation.isPending} onClick={() => openCommand('REBOOT', commandCandidates, true)}><RotateCw size={16} />{candidateLabel} 재시작</button>}
          {canShutdown && <button className="button danger" disabled={candidateCommands.shutdown === 0 || commandMutation.isPending} onClick={() => openCommand('SHUTDOWN', commandCandidates, true)}><PowerOff size={16} />{candidateLabel} 종료</button>}
        </div>
      </div>

      {!filteredPcs.length ? <EmptyState message="조건에 맞는 PC가 없습니다." /> : (
        <div className="table-scroll">
          <table className="data-table pc-table">
            <thead>
              <tr>
                <th style={{ width: 44 }}><SelectAllCheckbox checked={selectState.checked} indeterminate={selectState.indeterminate} disabled={!selectState.selectableMacs.length} onChange={toggleFiltered} /></th>
                <th>강의실</th><th>PC 이름</th><th>IP 주소</th><th>MAC 주소</th><th>역할</th><th>상태</th><th>SCMATE</th><th>에이전트 버전</th><th>전체 번들</th><th>마지막 연결</th><th style={{ minWidth: 190 }}>화면·명령</th>
              </tr>
            </thead>
            <tbody>
              {visiblePcs.map((pc) => {
                const state = pcState(pc)
                const selectable = isSelectablePc(pc)
                return (
                  <tr key={pc.macAddress || `${pc.pcName}-${pc.ipAddress}`}>
                    <td><input type="checkbox" checked={selectedMacs.has(pc.macAddress)} disabled={!selectable} onChange={() => togglePc(pc.macAddress)} aria-label={`${pc.pcName} 선택`} /></td>
                    <td>{pc.groupName || '-'}</td>
                    <td><strong>{pc.pcName || '-'}</strong></td>
                    <td>{pc.ipAddress || '-'}</td>
                    <td>{pc.macAddress || '-'}</td>
                    <td>{pcRoleLabel(pc)}</td>
                    <td><span className={`status-badge ${state === 'broken' ? 'warning' : state}`}>{pcStateLabel(pc)}</span></td>
                    <td><button className="scmate-status-button" onClick={() => openScmate(pc)} aria-label={pcRole(pc) === 'teacher' ? `${pc.groupName || pc.pcName} SCMATE 서버 제어` : `${pc.pcName} SCMATE 상태 상세`}><ScmateStatusBadge pc={pc} /></button></td>
                    <td>{pc.agentVersion || '-'}</td>
                    <td>{pc.bundleVersion || '-'}</td>
                    <td>{formatLastSeen(pc.lastSeen)}</td>
                    <td>
                      {selectable ? (
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 2 }}>
                          {state === 'offline' && pc.groupId != null && canPowerOn ? (
                            <button className="icon-button" style={{ width: 30, height: 30 }} onClick={() => openCommand('PCON', [pc], false)} disabled={commandMutation.isPending} aria-label={`${pc.pcName} 켜기`} title="PC 켜기"><Power size={15} /></button>
                          ) : state === 'online' ? (
                            <>
                              {canReboot && <button className="icon-button" style={{ width: 30, height: 30 }} onClick={() => openCommand('REBOOT', [pc], false)} disabled={commandMutation.isPending} aria-label={`${pc.pcName} 재시작`} title="재시작"><RotateCw size={15} /></button>}
                              {canPcOperations && <button className="icon-button" style={{ width: 30, height: 30 }} onClick={() => setOperationTargets([pc])} disabled={commandMutation.isPending} aria-label={`${pc.pcName} 작업`} title="PC 작업"><Wrench size={15} /></button>}
                              {canRemote && <button className="icon-button" style={{ width: 30, height: 30 }} onClick={() => setViewerTarget(pc)} disabled={commandMutation.isPending} aria-label={`${pc.pcName} 화면 보기`} title="개별 화면 보기"><Eye size={15} /></button>}
                              {canRemote && <button className="icon-button" style={{ width: 30, height: 30 }} onClick={() => setRemoteTarget(pc)} disabled={commandMutation.isPending} aria-label={`${pc.pcName} 원격 제어`} title="원격 제어"><Monitor size={15} /></button>}
                              {canShutdown && <button className="icon-button" style={{ width: 30, height: 30, color: 'var(--red)' }} onClick={() => openCommand('SHUTDOWN', [pc], false)} disabled={commandMutation.isPending} aria-label={`${pc.pcName} 종료`} title="종료"><PowerOff size={15} /></button>}
                            </>
                          ) : '-'}
                          {canScmate && ((pcRole(pc) === 'teacher' && pc.groupId != null) || allowedScmateActions(pc).length > 0) && (
                            <button className="icon-button scmate-action-button" style={{ width: 30, height: 30 }} onClick={() => openScmate(pc)} disabled={commandMutation.isPending} aria-label={`${pc.pcName} SCMATE 작업`} title="SCMATE 작업"><Shield size={15} /></button>
                          )}
                        </span>
                      ) : '-'}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {filteredPcs.length > PAGE_SIZE && (
        <footer style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, paddingTop: 12 }}>
          <span style={{ color: 'var(--muted)', fontSize: 12 }}>{page * PAGE_SIZE + 1}-{Math.min((page + 1) * PAGE_SIZE, filteredPcs.length)} / {filteredPcs.length}</span>
          <div style={{ display: 'flex', gap: 8 }}>
            <button className="button secondary" disabled={page === 0} onClick={() => setPage((current) => Math.max(0, current - 1))}>이전</button>
            <button className="button secondary" disabled={page >= pageCount - 1} onClick={() => setPage((current) => Math.min(pageCount - 1, current + 1))}>다음</button>
          </div>
        </footer>
      )}

      <ConfirmDialog
        open={Boolean(pendingCommand)}
        title={`${pendingCommand ? commandLabel(pendingCommand.type) : ''} 확인`}
        description={commandDescription(pendingCommand)}
        confirmLabel={pendingCommand ? commandLabel(pendingCommand.type) : '확인'}
        danger={pendingCommand?.type === 'SHUTDOWN' || pendingCommand?.type === 'REBOOT'}
        busy={commandMutation.isPending}
        onCancel={() => { if (!commandMutation.isPending) setPendingCommand(null) }}
        onConfirm={() => { if (pendingCommand && !commandMutation.isPending) commandMutation.mutate(pendingCommand) }}
      />
      {operationTargets.length > 0 && <PcOperationsDialog targets={operationTargets} onClose={() => setOperationTargets([])} />}
      {viewerTarget && <RemoteControlDialog pc={viewerTarget} mode="view" onClose={() => setViewerTarget(null)} />}
      {remoteTarget && <RemoteControlDialog pc={remoteTarget} onClose={() => setRemoteTarget(null)} />}
      {scmateTarget && <ScmateControlDialog pc={scmateTarget} classroomPcs={scmateClassroomPcs} canAct={canScmate} canPerform={(action) => hasPermission(scmatePermission(action))} onClose={() => setScmateTargetMac(null)} />}
    </section>
  )
}
