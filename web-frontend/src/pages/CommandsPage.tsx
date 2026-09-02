import { useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  CheckCircle2,
  Ban,
  ChevronLeft,
  ChevronRight,
  CircleAlert,
  Download,
  FileTerminal,
  Plus,
  RefreshCw,
  RotateCcw,
  Search,
  X,
} from 'lucide-react'
import { useAuth } from '../auth/AuthProvider'
import { api } from '../api/client'
import { ConfirmDialog } from '../components/ui/ConfirmDialog'
import { EmptyState, PageError, PageLoading } from '../components/ui/PageState'
import { useToast } from '../components/ui/ToastProvider'
import { useDialogFocus } from '../components/ui/useDialogFocus'
import { cancelCommand, getCommand, getCommands, issueCommand } from '../features/commands/api'
import { routerWolFailureMessage } from '../features/commands/routerWolMessage'
import { listActiveRemoteControlSessions } from '../features/remoteControl/api'
import { clientEnvironmentLabel } from '../utils/clientInfo'
import {
  COMMAND_TYPE_LABELS,
  DEFAULT_COMMAND_DRAFT,
  commandDestinationDisplay,
  commandDisplayType,
  commandProgressSteps,
  commandRunMode,
  commandStatusLabel,
  commandStatusTone,
  commandTargetLabel,
  commandTransferDisplay,
  commandTypeLabel,
  commandVerificationLabel,
  collectDownloadFilename,
  collectDownloadPath,
  canCancelCommand,
  detailedCommandStatus,
  filterAndSortCommands,
  formatCommandDate,
  isFinalCommandStatus,
  summarizeCommands,
  toIssueCommandRequest,
  validateCommandDraft,
} from '../features/commands/model'
import {
  COMMAND_STATUSES,
  COMMAND_TYPES,
  type CommandDraft,
  type CommandDraftErrors,
  type CommandEnqueueResult,
  type CommandSort,
  type CommandStatusFilter,
  type CommandView,
  type IssueCommandRequest,
} from '../features/commands/types'
import '../features/commands/commands.css'

const PAYLOAD_TYPES = new Set(['RUN_CMD', 'RUN_PS1', 'COLLECT_FILES', 'LIST_FILES', 'AGENT_UPDATE', 'AGENT_BUNDLE_UPDATE'])

function StatusBadge({ status }: { status: string }) {
  return <span className={`command-status ${commandStatusTone(status)}`}>{commandStatusLabel(status)}</span>
}

function remoteViewerStatusLabel(status: string) {
  return ({
    OPEN: '준비',
    PENDING: 'PC 준비 중',
    READY: '접속 준비',
    TICKET_ISSUED: '접속 준비',
    CONNECTING: '연결 중',
    CONNECTED: '화면 보는 중',
  } as Record<string, string>)[status] || status
}

function CommandDetail({ command, loading, error, downloadBusy, cancelBusy, onDownload, onCancel, onClose }: {
  command: CommandView | null
  loading: boolean
  error: string | null
  downloadBusy: boolean
  cancelBusy: boolean
  onDownload: (command: CommandView) => void
  onCancel: (command: CommandView) => void
  onClose: () => void
}) {
  return (
    <>
      <button className="drawer-backdrop" onClick={onClose} aria-label="명령 상세 닫기" />
      <aside className="room-drawer command-drawer" aria-label="명령 상세">
        <header className="drawer-header">
          <div><h2>명령 상세</h2><p>{command ? `작업 #${command.taskId}` : '작업 정보'}</p></div>
          <button className="icon-button" onClick={onClose} aria-label="닫기"><X size={19} /></button>
        </header>
        <div className="command-detail-body">
          {loading && !command ? <PageLoading label="명령 상세를 불러오는 중입니다." /> : error && !command ? (
            <PageError message={error} />
          ) : command ? (
            <>
              {error && <div className="command-inline-error" role="alert"><CircleAlert size={15} />{error} 기존 상세 정보를 표시합니다.</div>}
              <ol className="command-progress" aria-label="명령 진행 단계">
                {commandProgressSteps(command).map((step, index) => (
                  <li className={`command-progress-step ${step.state}`} key={step.label}>
                    <span aria-hidden="true">{step.state === 'done' ? '✓' : step.state === 'failed' ? '!' : index + 1}</span>
                    <strong>{step.label}</strong>
                  </li>
                ))}
              </ol>
              <dl className="command-detail-grid">
                <div><dt>상태</dt><dd>{detailedCommandStatus(command)}</dd></div>
                <div><dt>명령</dt><dd>{commandTypeLabel(command.commandType)} ({commandDisplayType(command)})</dd></div>
                <div><dt>대상 PC</dt><dd>{commandTargetLabel(command)}</dd></div>
                <div><dt>MAC 주소</dt><dd>{command.macAddress || '-'}</dd></div>
                <div><dt>강의실</dt><dd>{command.groupName || command.groupId || '-'}</dd></div>
                <div><dt>IP 주소</dt><dd>{command.ipAddress || '-'}</dd></div>
                <div><dt>발행 계정</dt><dd>{command.requestedBy || '기록 없음'}</dd></div>
                <div><dt>발행 위치</dt><dd>{command.requestedIp || '-'}</dd></div>
                <div><dt>생성</dt><dd>{formatCommandDate(command.createdAt)}</dd></div>
                <div><dt>전달</dt><dd>{formatCommandDate(command.deliveredAt)}</dd></div>
                <div><dt>확인</dt><dd>{formatCommandDate(command.ackedAt)}</dd></div>
                <div><dt>완료</dt><dd>{formatCommandDate(command.completedAt)}</dd></div>
                <div><dt>종료 코드</dt><dd>{command.exitCode ?? '-'}</dd></div>
                <div><dt>재시도</dt><dd>{command.retryCount ?? 0}</dd></div>
                <div><dt>검증 상태</dt><dd>{commandVerificationLabel(command)}</dd></div>
                <div><dt>SCMATE 복구</dt><dd>{command.scmateResetStatus || '-'}</dd></div>
                <div><dt>실행 방식</dt><dd>{commandRunMode(command)}</dd></div>
                <div><dt>전송 대상</dt><dd>{commandTransferDisplay(command)}</dd></div>
                <div><dt>대상 경로</dt><dd>{commandDestinationDisplay(command)}</dd></div>
              </dl>
              {(command.status === 'FAILED' || (command.exitCode != null && command.exitCode !== 0)) && (
                <section className="command-failure" role="alert">
                  <CircleAlert size={17} />
                  <div><strong>명령 실행 실패</strong><p>종료 코드 {command.exitCode ?? '-'} · {command.stderr?.trim().split(/\r?\n/, 1)[0] || '상세 오류가 기록되지 않았습니다.'}</p></div>
                </section>
              )}
              {canCancelCommand(command.status) && (
                <section className="command-output-section">
                  <button className="button danger" onClick={() => onCancel(command)} disabled={cancelBusy}>
                    <Ban size={16} />{cancelBusy ? '취소 처리 중' : '대기 명령 취소'}
                  </button>
                  <p>PC에 아직 전달되지 않은 명령만 안전하게 취소할 수 있습니다.</p>
                </section>
              )}
              {command.scriptUrl && (
                <section className="command-output-section"><h3>스크립트 URL</h3><pre className="command-output command-payload">{command.scriptUrl}</pre></section>
              )}
              {command.payloadJson && (
                <section className="command-output-section"><h3>요청 JSON</h3><pre className="command-output command-payload">{command.payloadJson}</pre></section>
              )}
              <section className="command-output-section"><h3>표준 출력</h3><pre className="command-output">{command.stdout || '출력 없음'}</pre></section>
              <section className="command-output-section"><h3>표준 오류</h3><pre className="command-output error">{command.stderr || '오류 없음'}</pre></section>
              {collectDownloadPath(command) && <section className="command-output-section">
                <button className="button primary" onClick={() => onDownload(command)} disabled={downloadBusy}>
                  <Download size={16} />{downloadBusy ? '내려받는 중' : '수집 파일 다운로드'}
                </button>
              </section>}
            </>
          ) : null}
        </div>
      </aside>
    </>
  )
}

function IssueCommandDialog({ draft, errors, busy, onChange, onSubmit, onClose }: {
  draft: CommandDraft
  errors: CommandDraftErrors
  busy: boolean
  onChange: (next: CommandDraft) => void
  onSubmit: (event: FormEvent<HTMLFormElement>) => void
  onClose: () => void
}) {
  const showPayload = PAYLOAD_TYPES.has(draft.type)
  const dialogRef = useDialogFocus<HTMLElement>(onClose, busy)
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !busy) onClose()
    }}>
      <section ref={dialogRef} className="command-issue-dialog" role="dialog" aria-modal="true" aria-labelledby="command-issue-title" tabIndex={-1}>
        <button className="icon-button modal-close" onClick={onClose} disabled={busy} aria-label="닫기"><X size={18} /></button>
        <h2 id="command-issue-title">명령 발행</h2>
        <p>관리자 명령</p>
        <form onSubmit={onSubmit} noValidate>
          <div className="command-form-grid">
            <label className="command-form-field">대상 범위
              <select value={draft.scope} onChange={(event) => onChange({ ...draft, scope: event.target.value as CommandDraft['scope'] })}>
                <option value="SINGLE">단일 PC</option><option value="GROUP">강의실</option>
              </select>
            </label>
            <label className="command-form-field">명령
              <select value={draft.type} onChange={(event) => onChange({ ...draft, type: event.target.value as CommandDraft['type'] })}>
                {COMMAND_TYPES.map((type) => <option value={type} key={type}>{COMMAND_TYPE_LABELS[type]}</option>)}
              </select>
              {errors.type && <span className="field-error">{errors.type}</span>}
            </label>
            {draft.scope === 'SINGLE' ? (
              <label className="command-form-field full">MAC 주소
                <input value={draft.macAddress} onChange={(event) => onChange({ ...draft, macAddress: event.target.value })} placeholder="AA:BB:CC:DD:EE:FF" autoComplete="off" />
                {errors.macAddress && <span className="field-error">{errors.macAddress}</span>}
              </label>
            ) : (
              <>
                <label className="command-form-field">강의실 ID
                  <input type="number" min="1" step="1" value={draft.groupId} onChange={(event) => onChange({ ...draft, groupId: event.target.value })} />
                  {errors.groupId && <span className="field-error">{errors.groupId}</span>}
                </label>
                <label className="command-form-field">대상 역할
                  <select value={draft.role} onChange={(event) => onChange({ ...draft, role: event.target.value as CommandDraft['role'] })}>
                    <option value="ALL">전체</option><option value="TEACHER">교수 PC</option><option value="STUDENT">학생 PC</option>
                  </select>
                </label>
              </>
            )}
            {draft.type === 'WOL' && (
              <label className="command-form-field full">브로드캐스트 IP
                <input value={draft.broadcastIp} onChange={(event) => onChange({ ...draft, broadcastIp: event.target.value })} placeholder="255.255.255.255" inputMode="numeric" />
                {errors.broadcastIp && <span className="field-error">{errors.broadcastIp}</span>}
              </label>
            )}
            {draft.type === 'RUN_PS1' && (
              <label className="command-form-field full">스크립트 URL
                <input value={draft.scriptUrl} onChange={(event) => onChange({ ...draft, scriptUrl: event.target.value })} placeholder="https://... 또는 /api/..." autoComplete="off" />
                {errors.scriptUrl && <span className="field-error">{errors.scriptUrl}</span>}
              </label>
            )}
            {showPayload && (
              <label className="command-form-field full">요청 JSON
                <textarea value={draft.payloadJson} onChange={(event) => onChange({ ...draft, payloadJson: event.target.value })} placeholder={draft.type === 'RUN_CMD' ? '{"cmd":"hostname"}' : '{}'} spellCheck={false} />
                {errors.payloadJson && <span className="field-error">{errors.payloadJson}</span>}
              </label>
            )}
          </div>
          <div className="command-form-actions">
            <button type="button" className="button secondary" onClick={onClose} disabled={busy}>취소</button>
            <button type="submit" className="button primary" disabled={busy}><FileTerminal size={17} />확인</button>
          </div>
        </form>
      </section>
    </div>
  )
}

function enqueueMessage(result: CommandEnqueueResult) {
  const queued = result.queued || 0
  if (queued > 0 && (result.routerSignals || 0) > 0) return { message: '공유기 WOL 신호를 전송했습니다.', type: 'success' as const }
  if (queued > 0) return { message: `명령 ${queued}건을 대기열에 등록했습니다.`, type: 'success' as const }
  if ((result.duplicateSkipped || 0) > 0) return { message: '동일한 명령이 이미 진행 중입니다.', type: 'error' as const }
  if ((result.offlineSkipped || 0) > 0) return { message: `오프라인 대상 ${result.offlineSkipped}대를 제외했습니다.`, type: 'error' as const }
  return { message: routerWolFailureMessage(result) || result.message || '명령 대상이 없습니다.', type: 'error' as const }
}

export function CommandsPage() {
  const queryClient = useQueryClient()
  const { isAdmin, canWrite, hasPermission } = useAuth()
  const allowed = isAdmin || hasPermission?.('COMMANDS') === true
  const canOperate = isAdmin || (canWrite && allowed)
  const { showToast } = useToast()
  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState<CommandStatusFilter>('ALL')
  const [hiddenTypes, setHiddenTypes] = useState<Set<string>>(() => new Set())
  const [groupFilter, setGroupFilter] = useState('ALL')
  const [sort, setSort] = useState<CommandSort>('NEWEST')
  const [taskIdInput, setTaskIdInput] = useState('')
  const [taskId, setTaskId] = useState<number | undefined>()
  const [taskIdError, setTaskIdError] = useState('')
  const [page, setPage] = useState(0)
  const [pageSize, setPageSize] = useState(20)
  const [selectedTaskId, setSelectedTaskId] = useState<number | null>(null)
  const [issueOpen, setIssueOpen] = useState(false)
  const [draft, setDraft] = useState<CommandDraft>({ ...DEFAULT_COMMAND_DRAFT })
  const [draftErrors, setDraftErrors] = useState<CommandDraftErrors>({})
  const [pendingIssue, setPendingIssue] = useState<IssueCommandRequest | null>(null)
  const [pendingCancelTaskId, setPendingCancelTaskId] = useState<number | null>(null)

  const serverFilters = useMemo(() => ({
    statuses: COMMAND_STATUSES,
    taskId,
    page: 0,
    size: taskId ? 1 : 500,
  }), [taskId])

  const commandsQuery = useQuery({
    queryKey: ['commands', 'operations-snapshot', taskId || null],
    queryFn: () => getCommands(serverFilters),
    enabled: allowed,
    placeholderData: taskId ? undefined : keepPreviousData,
    refetchInterval: 10_000,
  })
  const remoteSessionsQuery = useQuery({
    queryKey: ['remote-control', 'active-sessions'],
    queryFn: listActiveRemoteControlSessions,
    enabled: allowed,
    refetchInterval: 5_000,
  })
  const detailQuery = useQuery({
    queryKey: ['command', selectedTaskId],
    queryFn: () => getCommand(selectedTaskId as number),
    enabled: allowed && selectedTaskId !== null,
    refetchInterval: (query) => isFinalCommandStatus(query.state.data?.status) ? false : 5_000,
  })
  const snapshot = commandsQuery.data?.content || []
  const availableTypes = useMemo(() => Array.from(new Set(snapshot.map(commandDisplayType))).filter((type) => type !== '-').sort(), [snapshot])
  const availableGroups = useMemo(() => Array.from(new Set(snapshot.map((command) => command.groupName?.trim() || (command.groupId != null ? `그룹 ${command.groupId}` : '')).filter(Boolean))).sort((a, b) => a.localeCompare(b, 'ko-KR', { numeric: true })), [snapshot])
  const filteredCommands = useMemo(() => taskId ? snapshot : filterAndSortCommands(snapshot, {
    search,
    status: statusFilter,
    hiddenTypes,
    group: groupFilter,
    sort,
  }), [snapshot, taskId, search, statusFilter, hiddenTypes, groupFilter, sort])
  const summary = useMemo(() => summarizeCommands(snapshot), [snapshot])
  const totalLocalPages = taskId ? (filteredCommands.length ? 1 : 0) : Math.ceil(filteredCommands.length / pageSize)
  const currentPage = Math.min(page, Math.max(0, totalLocalPages - 1))
  const visibleCommands = taskId
    ? filteredCommands
    : filteredCommands.slice(currentPage * pageSize, (currentPage + 1) * pageSize)
  const selectedFromList = snapshot.find((command) => command.taskId === selectedTaskId) || null
  const selectedCommand = detailQuery.data || selectedFromList

  const issueMutation = useMutation({
    mutationFn: issueCommand,
    onSuccess: (result) => {
      const feedback = enqueueMessage(result)
      showToast(feedback.message, feedback.type)
      setPendingIssue(null)
      setIssueOpen(false)
      setDraft({ ...DEFAULT_COMMAND_DRAFT })
      setDraftErrors({})
      setPage(0)
      void queryClient.invalidateQueries({ queryKey: ['commands'] })
    },
    onError: (error) => {
      showToast(error instanceof Error ? error.message : '명령을 발행하지 못했습니다.', 'error')
      setPendingIssue(null)
    },
  })
  const downloadMutation = useMutation({
    mutationFn: (command: CommandView) => {
      const path = collectDownloadPath(command)
      if (!path) throw new Error('다운로드할 수집 결과가 없습니다.')
      return api.download(path, collectDownloadFilename(command))
    },
    onSuccess: () => showToast('수집 파일 다운로드를 시작했습니다.'),
    onError: (error) => showToast(error instanceof Error ? error.message : '수집 파일을 내려받지 못했습니다.', 'error'),
  })
  const cancelMutation = useMutation({
    mutationFn: (taskId: number) => cancelCommand(taskId),
    onSuccess: (result) => {
      showToast(`작업 #${result.taskId} 대기 명령을 취소했습니다.`, 'success')
      setPendingCancelTaskId(null)
      void queryClient.invalidateQueries({ queryKey: ['commands'] })
      void queryClient.invalidateQueries({ queryKey: ['command', result.taskId] })
    },
    onError: (error) => {
      showToast(error instanceof Error ? error.message : '대기 명령을 취소하지 못했습니다.', 'error')
      setPendingCancelTaskId(null)
    },
  })

  const applyTaskId = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const raw = taskIdInput.trim()
    if (!raw) {
      setTaskId(undefined)
      setTaskIdError('')
      setPage(0)
      return
    }
    const parsed = Number(raw)
    if (!Number.isInteger(parsed) || parsed <= 0) {
      setTaskIdError('작업 ID는 양의 정수여야 합니다.')
      return
    }
    setTaskId(parsed)
    setTaskIdError('')
    setPage(0)
  }

  const clearTaskId = () => {
    setTaskIdInput('')
    setTaskId(undefined)
    setTaskIdError('')
    setPage(0)
  }

  const resetFilters = () => {
    setSearch('')
    setStatusFilter('ALL')
    setHiddenTypes(new Set())
    setGroupFilter('ALL')
    setSort('NEWEST')
    setPage(0)
  }

  const toggleType = (type: string) => {
    setHiddenTypes((current) => {
      const next = new Set(current)
      if (next.has(type)) next.delete(type)
      else next.add(type)
      return next
    })
    setPage(0)
  }

  const prepareIssue = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const errors = validateCommandDraft(draft)
    setDraftErrors(errors)
    if (Object.keys(errors).length) return
    setPendingIssue(toIssueCommandRequest(draft))
  }

  if (!allowed) {
    return <section className="content-section commands-page"><PageError message="명령 발행 / 이력 기능 권한이 없습니다." /></section>
  }
  if (commandsQuery.isPending && !commandsQuery.data) return <section className="content-section commands-page"><PageLoading label="명령 이력을 불러오는 중입니다." /></section>
  if (commandsQuery.isError && !commandsQuery.data) {
    return <section className="content-section commands-page"><PageError message={commandsQuery.error instanceof Error ? commandsQuery.error.message : '명령 이력을 불러오지 못했습니다.'} onRetry={() => { void commandsQuery.refetch() }} /></section>
  }

  const serverTotal = commandsQuery.data?.totalElements || snapshot.length
  const snapshotLimited = !taskId && serverTotal > snapshot.length
  const confirmationTarget = pendingIssue?.scope === 'SINGLE' ? pendingIssue.macAddress : `강의실 #${pendingIssue?.groupId}`
  const confirmationType = pendingIssue ? commandTypeLabel(pendingIssue.type) : ''
  const confirmationDanger = pendingIssue ? ['SHUTDOWN', 'REBOOT', 'RUN_CMD', 'RUN_PS1', 'AGENT_UPDATE', 'AGENT_BUNDLE_UPDATE'].includes(pendingIssue.type) : false

  return (
    <section className="content-section commands-page">
      <header className="page-header commands-toolbar">
        <div>
          <h1>명령 발행 / 이력</h1>
          <p>{taskId ? `작업 #${taskId} 서버 단건 조회` : `서버 ${serverTotal.toLocaleString('ko-KR')}건 중 최근 ${snapshot.length.toLocaleString('ko-KR')}건 운영 스냅샷`} · 10초 자동 갱신</p>
        </div>
        <div className="commands-toolbar-actions">
          <form className="command-id-filter" onSubmit={applyTaskId}>
            <label className="commands-filter">작업 ID
              <input value={taskIdInput} onChange={(event) => setTaskIdInput(event.target.value)} inputMode="numeric" placeholder="정확히 조회" aria-invalid={Boolean(taskIdError)} />
            </label>
            <button className="icon-button bordered" type="submit" aria-label="작업 ID 조회" title="작업 ID 조회"><Search size={17} /></button>
            {taskId && <button className="icon-button bordered" type="button" onClick={clearTaskId} aria-label="작업 ID 필터 해제" title="필터 해제"><X size={17} /></button>}
          </form>
          <button className="icon-button bordered" onClick={() => { void commandsQuery.refetch() }} disabled={commandsQuery.isFetching} aria-label="새로고침" title="새로고침"><RefreshCw size={17} className={commandsQuery.isFetching ? 'spin-icon' : ''} /></button>
          {canOperate && <button className="button primary" onClick={() => setIssueOpen(true)}><Plus size={17} />명령 발행</button>}
        </div>
      </header>
      {taskIdError && <p className="form-error" role="alert">{taskIdError}</p>}
      <div className="commands-live-row" aria-live="polite">
        <span className={`commands-fetching${commandsQuery.isFetching && !commandsQuery.isPending ? ' active' : ''}`}><RefreshCw size={13} className="spin-icon" />갱신 중</span>
        {snapshotLimited && <span>검색·필터·정렬은 최신 500건 범위입니다. 오래된 작업은 작업 ID로 조회하세요.</span>}
      </div>
      {commandsQuery.isError && commandsQuery.data && (
        <div className="command-inline-error" role="alert"><CircleAlert size={15} />자동 갱신에 실패했습니다. 기존 스냅샷을 유지합니다.</div>
      )}

      <section className="management-form" aria-label="현재 원격 접속">
        <div className="management-form-header">
          <div><h2>현재 원격 접속</h2><p>계정·접속 IP·브라우저·대상 PC를 5초마다 확인합니다.</p></div>
          <span className="status-badge running">{remoteSessionsQuery.data?.length || 0}개 연결</span>
        </div>
        {remoteSessionsQuery.isError ? (
          <div className="command-inline-error" role="alert"><CircleAlert size={15} />원격 접속 현황을 불러오지 못했습니다.</div>
        ) : !(remoteSessionsQuery.data || []).length ? (
          <p>현재 화면을 보고 있는 사용자가 없습니다.</p>
        ) : (
          <div className="table-scroll">
            <table className="data-table">
              <thead><tr><th>계정</th><th>접속 위치</th><th>대상 PC</th><th>상태</th><th>시작 시각</th></tr></thead>
              <tbody>{remoteSessionsQuery.data?.map((session) => <tr key={session.sessionId}>
                <td><strong>{session.username || 'system'}</strong></td>
                <td><span className="stacked-cell"><strong>{session.sourceIp || '-'}</strong><small>{clientEnvironmentLabel(session.userAgent)}</small></span></td>
                <td><span className="stacked-cell"><strong>{session.pcName || session.macAddress}</strong><small>{session.targetIp || '-'} · {session.macAddress}</small></span></td>
                <td><span className="status-badge running">{remoteViewerStatusLabel(session.status)}</span></td>
                <td>{formatCommandDate(session.startedAt)}</td>
              </tr>)}</tbody>
            </table>
          </div>
        )}
      </section>

      {!taskId && (
        <>
          <div className="command-summary" aria-label="최근 명령 상태 요약">
            <button type="button" onClick={() => { setStatusFilter('ACTIVE'); setPage(0) }}><span>진행 중</span><strong>{summary.queued + summary.delivered + summary.acked}</strong><small>Q {summary.queued} · D {summary.delivered} · A {summary.acked}</small></button>
            <button type="button" onClick={() => { setStatusFilter('VERIFYING'); setPage(0) }}><span>검증 대기</span><strong>{summary.verifying}</strong><small>종료·재접속 확인</small></button>
            <button type="button" onClick={() => { setStatusFilter('SCMATE_PENDING'); setPage(0) }}><span>SCMATE 대기</span><strong>{summary.scmatePending}</strong><small>시점 저장 필요</small></button>
            <button type="button" onClick={() => { setStatusFilter('COMPLETED'); setPage(0) }}><span>완료</span><strong>{summary.completed}</strong><small>최근 스냅샷</small></button>
            <button type="button" onClick={() => { setStatusFilter('FAILED'); setPage(0) }}><span>실패</span><strong>{summary.failed}</strong><small>확인 필요</small></button>
          </div>

          <div className="commands-filter-panel">
            <label className="commands-filter commands-search-filter">PC·MAC·강의실 검색
              <span><Search size={15} /><input value={search} onChange={(event) => { setSearch(event.target.value); setPage(0) }} placeholder="PC명, MAC, IP, 강의실, 명령" /></span>
            </label>
            <label className="commands-filter">상태
              <select value={statusFilter} onChange={(event) => { setStatusFilter(event.target.value as CommandStatusFilter); setPage(0) }}>
                <option value="ALL">전체</option><option value="ACTIVE">진행 중</option><option value="COMPLETED">완료</option><option value="FAILED">실패</option><option value="VERIFYING">검증 중</option><option value="SCMATE_PENDING">SCMATE 대기</option><option value="CANCELLED">취소</option>
              </select>
            </label>
            <details className="command-type-filter">
              <summary>명령 종류{hiddenTypes.size ? ` (${hiddenTypes.size}개 숨김)` : ''}</summary>
              <div>
                {availableTypes.length ? availableTypes.map((type) => <label key={type}><input type="checkbox" checked={!hiddenTypes.has(type)} onChange={() => toggleType(type)} />{type}</label>) : <span>조회된 종류 없음</span>}
              </div>
            </details>
            <label className="commands-filter">강의실
              <select value={availableGroups.includes(groupFilter) ? groupFilter : 'ALL'} onChange={(event) => { setGroupFilter(event.target.value); setPage(0) }}>
                <option value="ALL">전체</option>{availableGroups.map((group) => <option key={group} value={group}>{group}</option>)}
              </select>
            </label>
            <label className="commands-filter">정렬
              <select value={sort} onChange={(event) => { setSort(event.target.value as CommandSort); setPage(0) }}>
                <option value="NEWEST">최신순</option><option value="OLDEST">오래된순</option><option value="GROUP">강의실순</option><option value="PC">PC명순</option><option value="STATUS">상태순</option><option value="TYPE">명령 종류순</option>
              </select>
            </label>
            <label className="commands-filter">표시 수
              <select value={pageSize} onChange={(event) => { setPageSize(Number(event.target.value)); setPage(0) }}>
                <option value="20">20개</option><option value="50">50개</option><option value="100">100개</option>
              </select>
            </label>
            <button className="button secondary command-reset" type="button" onClick={resetFilters}><RotateCcw size={15} />초기화</button>
          </div>
        </>
      )}

      {!visibleCommands.length ? <EmptyState message="조건에 맞는 명령 이력이 없습니다." /> : (
        <div className="table-scroll">
          <table className="data-table commands-table">
            <thead><tr><th>작업 ID</th><th>대상</th><th>강의실</th><th>명령</th><th>발행 계정</th><th>상태</th><th>최근 시각</th><th>결과</th></tr></thead>
            <tbody>
              {visibleCommands.map((command) => (
                <tr key={command.taskId} tabIndex={0} onClick={() => setSelectedTaskId(command.taskId)} onKeyDown={(event) => { if (event.key === 'Enter') setSelectedTaskId(command.taskId) }}>
                  <td><strong>#{command.taskId}</strong></td>
                  <td><span className="command-target"><strong>{commandTargetLabel(command)}</strong><small>{command.ipAddress || '-'} · {command.macAddress}</small></span></td>
                  <td>{command.groupName || command.groupId || '-'}</td>
                  <td><span className="command-target"><strong>{commandTypeLabel(command.commandType)}</strong><small>{commandDisplayType(command)}</small></span></td>
                  <td><span className="command-target"><strong>{command.requestedBy || '기록 없음'}</strong><small>{command.requestedIp || '-'}</small></span></td>
                  <td><span className="command-target"><StatusBadge status={command.status} /><small>{detailedCommandStatus(command)}</small></span></td>
                  <td>{formatCommandDate(command.completedAt || command.ackedAt || command.deliveredAt || command.createdAt)}</td>
                  <td>{command.status === 'FAILED' || (command.exitCode != null && command.exitCode !== 0) ? <span className="command-result failed"><CircleAlert size={14} />{command.exitCode ?? '실패'}</span> : isFinalCommandStatus(command.status) ? <span className="command-result success"><CheckCircle2 size={14} />{command.exitCode ?? 0}</span> : '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {!taskId && (
        <footer className="commands-pagination">
          <span>스냅샷 {snapshot.length.toLocaleString('ko-KR')}건 중 필터 결과 {filteredCommands.length.toLocaleString('ko-KR')}건</span>
          <div className="commands-pagination-actions">
            <button className="icon-button bordered" onClick={() => setPage((value) => Math.max(0, value - 1))} disabled={currentPage === 0} aria-label="이전 페이지"><ChevronLeft size={17} /></button>
            <strong>{totalLocalPages ? currentPage + 1 : 0} / {totalLocalPages}</strong>
            <button className="icon-button bordered" onClick={() => setPage((value) => value + 1)} disabled={!totalLocalPages || currentPage >= totalLocalPages - 1} aria-label="다음 페이지"><ChevronRight size={17} /></button>
          </div>
        </footer>
      )}

      {selectedTaskId !== null && (
        <CommandDetail
          command={selectedCommand}
          loading={detailQuery.isPending}
          error={detailQuery.isError ? (detailQuery.error instanceof Error ? detailQuery.error.message : '명령 상세를 불러오지 못했습니다.') : null}
          downloadBusy={downloadMutation.isPending}
          cancelBusy={cancelMutation.isPending}
          onDownload={(command) => downloadMutation.mutate(command)}
          onCancel={(command) => setPendingCancelTaskId(command.taskId)}
          onClose={() => setSelectedTaskId(null)}
        />
      )}
      {issueOpen && (
        <IssueCommandDialog
          draft={draft}
          errors={draftErrors}
          busy={issueMutation.isPending}
          onChange={(next) => { setDraft(next); setDraftErrors({}) }}
          onSubmit={prepareIssue}
          onClose={() => { if (!issueMutation.isPending) { setIssueOpen(false); setDraftErrors({}) } }}
        />
      )}
      <ConfirmDialog
        open={Boolean(pendingIssue)}
        title="명령 발행 확인"
        description={pendingIssue ? `${confirmationTarget}에 ${confirmationType} 명령을 발행합니다.` : ''}
        confirmLabel="명령 발행"
        danger={confirmationDanger}
        busy={issueMutation.isPending}
        onCancel={() => { if (!issueMutation.isPending) setPendingIssue(null) }}
        onConfirm={() => { if (pendingIssue && !issueMutation.isPending) issueMutation.mutate(pendingIssue) }}
      />
      <ConfirmDialog
        open={pendingCancelTaskId !== null}
        title="대기 명령 취소"
        description={pendingCancelTaskId !== null ? `작업 #${pendingCancelTaskId}을 취소합니다. 취소한 명령은 PC가 나중에 온라인이 되어도 실행되지 않습니다.` : ''}
        confirmLabel="명령 취소"
        danger
        busy={cancelMutation.isPending}
        onCancel={() => { if (!cancelMutation.isPending) setPendingCancelTaskId(null) }}
        onConfirm={() => { if (pendingCancelTaskId !== null && !cancelMutation.isPending) cancelMutation.mutate(pendingCancelTaskId) }}
      />
    </section>
  )
}
