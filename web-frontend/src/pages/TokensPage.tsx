import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Clipboard, Download, Eye, EyeOff, KeyRound, RefreshCw, RotateCw, Search, ShieldOff, Trash2 } from 'lucide-react'
import { ConfirmDialog } from '../components/ui/ConfirmDialog'
import { useAuth } from '../auth/AuthProvider'
import { EmptyState, PageError, PageLoading } from '../components/ui/PageState'
import { useToast } from '../components/ui/ToastProvider'
import { tokenApi, type AgentAdminView } from '../features/tokens/api'

type PendingAction = { kind: 'rotate' | 'revoke' | 'delete'; agents?: AgentAdminView[] }
const PAGE_SIZE = 100

function formatDate(value?: string | null) {
  return value ? new Date(value).toLocaleString('ko-KR') : '-'
}

export function TokensPage() {
  const { hasPermission } = useAuth()
  const canToggleRegistration = hasPermission('AGENT_REGISTRATION_TOGGLE')
  const canRotateKey = hasPermission('INSTALL_KEY_ROTATE')
  const canRevoke = hasPermission('AGENT_TOKEN_REVOKE')
  const canDelete = hasPermission('AGENT_DELETE')
  const canDownloadConfig = hasPermission('AGENT_CONFIG_DOWNLOAD')
  const queryClient = useQueryClient()
  const { showToast } = useToast()
  const [search, setSearch] = useState('')
  const [groupFilter, setGroupFilter] = useState('all')
  const [agentFilter, setAgentFilter] = useState('all')
  const [tokenFilter, setTokenFilter] = useState('all')
  const [selectedMacs, setSelectedMacs] = useState<Set<string>>(() => new Set())
  const [showInstallKey, setShowInstallKey] = useState(false)
  const [page, setPage] = useState(0)
  const [pending, setPending] = useState<PendingAction | null>(null)
  const statusQuery = useQuery({ queryKey: ['registration-status'], queryFn: tokenApi.getRegistrationStatus, refetchInterval: 60_000 })
  const keyQuery = useQuery({ queryKey: ['install-key'], queryFn: tokenApi.getInstallKey })
  const agentsQuery = useQuery({ queryKey: ['admin-agents'], queryFn: tokenApi.getAgents, staleTime: 30_000, refetchInterval: 60_000 })
  const allAgents = agentsQuery.data || []

  const groups = useMemo(() => [...new Set(allAgents.map((agent) => agent.groupName).filter((value): value is string => Boolean(value && value !== '-')))]
    .sort((left, right) => left.localeCompare(right, 'ko', { numeric: true })), [allAgents])
  const agents = useMemo(() => {
    const needle = search.trim().toLocaleLowerCase('ko-KR')
    return allAgents.filter((agent) => {
      if (needle && ![agent.pcName, agent.ipAddress, agent.macAddress, agent.groupName]
        .some((value) => value?.toLocaleLowerCase('ko-KR').includes(needle))) return false
      if (groupFilter !== 'all' && agent.groupName !== groupFilter) return false
      const online = agent.status?.toUpperCase() === 'ONLINE'
      if (agentFilter === 'online' && !online) return false
      if (agentFilter === 'offline' && online) return false
      if (tokenFilter === 'active' && !agent.tokenActive) return false
      if (tokenFilter === 'revoked' && agent.tokenActive) return false
      return true
    })
  }, [agentFilter, allAgents, groupFilter, search, tokenFilter])
  const pageCount = Math.max(1, Math.ceil(agents.length / PAGE_SIZE))
  const visibleAgents = agents.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE)
  const selectedAgents = useMemo(() => allAgents.filter((agent) => selectedMacs.has(agent.macAddress)), [allAgents, selectedMacs])
  const allFilteredSelected = agents.length > 0 && agents.every((agent) => selectedMacs.has(agent.macAddress))

  useEffect(() => setPage(0), [search, groupFilter, agentFilter, tokenFilter])
  useEffect(() => {
    if (page >= pageCount) setPage(pageCount - 1)
  }, [page, pageCount])
  useEffect(() => {
    const valid = new Set(allAgents.map((agent) => agent.macAddress))
    setSelectedMacs((current) => {
      const next = new Set([...current].filter((mac) => valid.has(mac)))
      return next.size === current.size ? current : next
    })
  }, [allAgents])

  const invalidate = () => Promise.all([
    queryClient.invalidateQueries({ queryKey: ['registration-status'] }),
    queryClient.invalidateQueries({ queryKey: ['install-key'] }),
    queryClient.invalidateQueries({ queryKey: ['admin-agents'] }),
  ])
  const toggleMutation = useMutation({
    mutationFn: (enabled: boolean) => tokenApi.setRegistrationStatus(enabled),
    onSuccess: async (_, enabled) => { await invalidate(); showToast(`신규 등록을 ${enabled ? '허용' : '차단'}했습니다.`) },
    onError: (error) => showToast(error instanceof Error ? error.message : '등록 상태를 변경하지 못했습니다.', 'error'),
  })
  const actionMutation = useMutation({
    mutationFn: async (action: PendingAction) => {
      if (action.kind === 'rotate') return tokenApi.rotateInstallKey()
      const targets = action.agents || []
      if (!targets.length) throw new Error('대상 에이전트가 없습니다.')
      const macs = targets.map((agent) => agent.macAddress)
      if (action.kind === 'revoke') return macs.length === 1 ? tokenApi.revokeAgent(macs[0]) : tokenApi.revokeAgents(macs)
      return macs.length === 1 ? tokenApi.deleteAgent(macs[0]) : tokenApi.deleteAgents(macs)
    },
    onSuccess: async (_, action) => {
      await invalidate()
      if (action.kind === 'rotate') setShowInstallKey(true)
      else setSelectedMacs(new Set())
      showToast(action.kind === 'rotate' ? '설치 키를 새로 발급했습니다.' : action.kind === 'revoke' ? `${action.agents?.length || 0}대의 토큰을 폐기했습니다.` : `${action.agents?.length || 0}대의 에이전트를 삭제했습니다.`)
      setPending(null)
    },
    onError: (error) => showToast(error instanceof Error ? error.message : '요청을 처리하지 못했습니다.', 'error'),
  })
  const downloadMutation = useMutation({
    mutationFn: tokenApi.downloadConfig,
    onSuccess: () => showToast('에이전트 설정 파일을 내려받았습니다.'),
    onError: (error) => showToast(error instanceof Error ? error.message : '설정 파일을 내려받지 못했습니다.', 'error'),
  })

  const copyInstallKey = async () => {
    const key = keyQuery.data?.key
    if (!key || key === '-') return showToast('복사할 설치 키가 없습니다.', 'error')
    try {
      await navigator.clipboard.writeText(key)
      showToast('설치 키를 클립보드에 복사했습니다.')
    } catch {
      showToast('브라우저에서 클립보드 접근을 허용해 주세요.', 'error')
    }
  }
  const toggleAgent = (mac: string) => setSelectedMacs((current) => {
    const next = new Set(current)
    if (next.has(mac)) next.delete(mac)
    else next.add(mac)
    return next
  })
  const toggleFiltered = () => setSelectedMacs((current) => {
    const next = new Set(current)
    agents.forEach((agent) => allFilteredSelected ? next.delete(agent.macAddress) : next.add(agent.macAddress))
    return next
  })

  if ((!statusQuery.data && statusQuery.isLoading) || (!keyQuery.data && keyQuery.isLoading) || (!agentsQuery.data && agentsQuery.isLoading)) return <section className="content-section"><PageLoading /></section>
  if ((!statusQuery.data && statusQuery.isError) || (!keyQuery.data && keyQuery.isError) || (!agentsQuery.data && agentsQuery.isError)) {
    const error = statusQuery.error || keyQuery.error || agentsQuery.error
    return <section className="content-section"><PageError message={error instanceof Error ? error.message : '토큰 정보를 불러오지 못했습니다.'} onRetry={() => void invalidate()} /></section>
  }

  const installKey = keyQuery.data?.key || '-'
  const targets = pending?.agents || []
  const singleName = targets[0]?.pcName || targets[0]?.macAddress || ''
  const deleteText = targets.length > 1 ? `DELETE ${targets.length}` : singleName
  return (
    <section className="content-section">
      <header className="page-header">
        <div><h1>토큰 관리</h1><p>에이전트 등록 키와 발급된 토큰을 관리합니다.</p></div>
        <button className="icon-button bordered" onClick={() => void invalidate()} aria-label="새로고침"><RefreshCw size={17} /></button>
      </header>
      {(statusQuery.isError || keyQuery.isError || agentsQuery.isError) && <div className="form-error" role="alert">새 데이터 갱신에 실패해 마지막으로 받은 토큰 정보를 표시하고 있습니다.</div>}

      <div className="summary-grid summary-grid-3">
        <article className="summary-panel"><span className="summary-icon"><KeyRound size={19} /></span><div><small>설치 키</small><strong>{showInstallKey ? installKey : keyQuery.data?.keyPreview || '미설정'}</strong></div><button className="icon-button bordered" onClick={() => setShowInstallKey((value) => !value)} aria-label={showInstallKey ? '설치 키 숨기기' : '설치 키 보기'}>{showInstallKey ? <EyeOff size={15} /> : <Eye size={15} />}</button><button className="icon-button bordered" onClick={() => void copyInstallKey()} aria-label="설치 키 복사"><Clipboard size={15} /></button>{canRotateKey && <button className="button secondary" onClick={() => setPending({ kind: 'rotate' })}><RotateCw size={15} />재발급</button>}</article>
        <article className="summary-panel"><div><small>신규 에이전트 등록</small><strong>{statusQuery.data?.enabled ? '허용 중' : '차단됨'}</strong></div>{canToggleRegistration && <label className="switch"><input type="checkbox" checked={Boolean(statusQuery.data?.enabled)} disabled={toggleMutation.isPending} onChange={(event) => toggleMutation.mutate(event.target.checked)} aria-label="신규 에이전트 등록 허용" /><span /></label>}</article>
        <article className="summary-panel"><div><small>활성 토큰</small><strong>{allAgents.filter((agent) => agent.tokenActive).length} / {allAgents.length}</strong></div>{canDownloadConfig && <button className="button secondary" onClick={() => downloadMutation.mutate()} disabled={downloadMutation.isPending}><Download size={15} />설정 다운로드</button>}</article>
      </div>

      <div className="section-toolbar">
        <div><h2>등록 에이전트</h2><p>{agents.length}개 표시 · {selectedAgents.length}개 선택</p></div>
        <div className="toolbar-actions">
          <label className="toolbar-search"><Search size={16} /><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="PC, IP, MAC 검색" /></label>
          <select value={groupFilter} onChange={(event) => setGroupFilter(event.target.value)} aria-label="강의실 필터"><option value="all">전체 강의실</option>{groups.map((group) => <option key={group} value={group}>{group}</option>)}</select>
          <select value={agentFilter} onChange={(event) => setAgentFilter(event.target.value)} aria-label="에이전트 상태 필터"><option value="all">전체 상태</option><option value="online">온라인</option><option value="offline">오프라인</option></select>
          <select value={tokenFilter} onChange={(event) => setTokenFilter(event.target.value)} aria-label="토큰 상태 필터"><option value="all">전체 토큰</option><option value="active">활성</option><option value="revoked">폐기됨</option></select>
          {canRevoke && <button className="button secondary" disabled={!selectedAgents.length || actionMutation.isPending} onClick={() => setPending({ kind: 'revoke', agents: selectedAgents })}><ShieldOff size={15} />선택 폐기</button>}
          {canDelete && <button className="button danger" disabled={!selectedAgents.length || actionMutation.isPending} onClick={() => setPending({ kind: 'delete', agents: selectedAgents })}><Trash2 size={15} />선택 삭제</button>}
        </div>
      </div>
      {!agents.length ? <EmptyState message="조건에 맞는 에이전트가 없습니다." /> : (
        <div className="table-scroll"><table className="data-table"><thead><tr><th><input type="checkbox" checked={allFilteredSelected} onChange={toggleFiltered} aria-label="검색 결과 전체 선택" /></th><th>PC</th><th>강의실</th><th>IP / MAC</th><th>상태</th><th>토큰</th><th>마지막 사용</th><th aria-label="관리" /></tr></thead><tbody>
          {visibleAgents.map((agent) => <tr key={agent.macAddress}>
            <td><input type="checkbox" checked={selectedMacs.has(agent.macAddress)} onChange={() => toggleAgent(agent.macAddress)} aria-label={`${agent.pcName || agent.macAddress} 선택`} /></td>
            <td><strong>{agent.pcName || '-'}</strong>{agent.orphan && <span className="inline-warning">미연결</span>}</td>
            <td>{agent.groupName || '-'}</td>
            <td><span className="stacked-cell"><span>{agent.ipAddress || '-'}</span><small>{agent.macAddress}</small></span></td>
            <td><span className={`status-badge ${agent.status?.toUpperCase() === 'ONLINE' ? 'online' : 'offline'}`}>{agent.status || 'OFFLINE'}</span></td>
            <td><span className={`status-badge ${agent.tokenActive ? 'online' : 'warning'}`}>{agent.tokenActive ? '활성' : '폐기됨'}</span></td>
            <td>{formatDate(agent.tokenLastUsedAt || agent.lastSeen)}</td>
            <td><div className="row-actions">{canRevoke && <button className="icon-button bordered" disabled={!agent.tokenActive} onClick={() => setPending({ kind: 'revoke', agents: [agent] })} title="토큰 폐기" aria-label={`${agent.pcName || agent.macAddress} 토큰 폐기`}><ShieldOff size={16} /></button>}{canDelete && <button className="icon-button danger-icon" onClick={() => setPending({ kind: 'delete', agents: [agent] })} title="에이전트 삭제" aria-label={`${agent.pcName || agent.macAddress} 삭제`}><Trash2 size={16} /></button>}</div></td>
          </tr>)}
        </tbody></table></div>
      )}
      {agents.length > PAGE_SIZE && <footer className="commands-pagination"><span>{page * PAGE_SIZE + 1}-{Math.min((page + 1) * PAGE_SIZE, agents.length)} / {agents.length}</span><div className="commands-pagination-actions"><button className="button secondary" disabled={page === 0} onClick={() => setPage((current) => Math.max(0, current - 1))}>이전</button><button className="button secondary" disabled={page >= pageCount - 1} onClick={() => setPage((current) => Math.min(pageCount - 1, current + 1))}>다음</button></div></footer>}
      <ConfirmDialog open={pending?.kind === 'rotate'} title="설치 키 재발급" description="기존 설치 키는 즉시 무효화됩니다. 이미 등록된 에이전트 토큰에는 영향이 없습니다." confirmLabel="재발급" danger busy={actionMutation.isPending} onCancel={() => setPending(null)} onConfirm={() => pending && actionMutation.mutate(pending)} />
      <ConfirmDialog open={pending?.kind === 'revoke'} title="에이전트 토큰 폐기" description={targets.length > 1 ? `선택한 ${targets.length}대의 토큰을 폐기합니다. 해당 PC는 새 토큰을 받기 전까지 연결할 수 없습니다.` : `${singleName}의 토큰을 폐기합니다. 해당 PC는 새 토큰을 발급받기 전까지 서버에 연결할 수 없습니다.`} confirmLabel="토큰 폐기" danger busy={actionMutation.isPending} onCancel={() => setPending(null)} onConfirm={() => pending && actionMutation.mutate(pending)} />
      <ConfirmDialog open={pending?.kind === 'delete'} title="에이전트 삭제" description={targets.length > 1 ? `선택한 ${targets.length}대의 등록 정보와 토큰을 삭제합니다.` : `${singleName}의 등록 정보와 토큰을 삭제합니다.`} confirmLabel="에이전트 삭제" danger requiredText={deleteText} requiredTextLabel={`확인을 위해 '${deleteText}' 입력`} busy={actionMutation.isPending} onCancel={() => setPending(null)} onConfirm={() => pending && actionMutation.mutate(pending)} />
    </section>
  )
}
