import { type FormEvent, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Pencil, Plus, RefreshCw, Search, Wrench, X } from 'lucide-react'
import { useAuth } from '../auth/AuthProvider'
import { ConfirmDialog } from '../components/ui/ConfirmDialog'
import { EmptyState, PageError, PageLoading } from '../components/ui/PageState'
import { useToast } from '../components/ui/ToastProvider'
import { useDialogFocus } from '../components/ui/useDialogFocus'
import { brokenPcApi, type BrokenPcInput } from '../features/broken/api'
import { getAllPcs, getClassrooms } from '../features/classrooms/api'
import { sortPcs } from '../features/classrooms/model'
import type { PcStatus } from '../types/dashboard'

type RegisterMode = 'existing' | 'unused'
const IPV4_PATTERN = /^(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)$/

function formatDate(value?: string | null) {
  return value ? new Date(value).toLocaleString('ko-KR') : '-'
}

function NoteDialog({ pc, busy, onClose, onSave }: {
  pc: PcStatus | null
  busy: boolean
  onClose: () => void
  onSave: (note: string) => void
}) {
  const [note, setNote] = useState(pc?.brokenNote || '')
  const dialogRef = useDialogFocus<HTMLFormElement>(onClose, busy)
  if (!pc) return null
  return <div className="modal-backdrop" role="presentation"><form ref={dialogRef} className="form-dialog" role="dialog" aria-modal="true" aria-labelledby="broken-note-title" tabIndex={-1} onSubmit={(event) => { event.preventDefault(); onSave(note.trim()) }}>
    <button type="button" className="icon-button modal-close" onClick={onClose} disabled={busy} aria-label="닫기"><X size={18} /></button>
    <span className="confirm-icon"><Wrench size={21} /></span><h2 id="broken-note-title">고장 사유 수정</h2><p>{pc.groupName || '-'} · {pc.pcName}</p>
    <label className="field-label">고장 사유<textarea value={note} onChange={(event) => setNote(event.target.value)} maxLength={500} rows={4} autoFocus /></label>
    <div className="modal-actions"><button type="button" className="button secondary" onClick={onClose} disabled={busy}>취소</button><button className="button primary" disabled={!note.trim() || busy}>{busy ? '저장 중' : '저장'}</button></div>
  </form></div>
}

export function BrokenPcsPage() {
  const { hasPermission } = useAuth()
  const canEdit = hasPermission('BROKEN_PC_REGISTER_EDIT')
  const canClear = hasPermission('BROKEN_PC_CLEAR')
  const { showToast } = useToast()
  const queryClient = useQueryClient()
  const [mode, setMode] = useState<RegisterMode>('existing')
  const [groupId, setGroupId] = useState<number | ''>('')
  const [selectedMac, setSelectedMac] = useState('')
  const [ipAddress, setIpAddress] = useState('')
  const [seatNumber, setSeatNumber] = useState('')
  const [note, setNote] = useState('')
  const [search, setSearch] = useState('')
  const [filterGroupId, setFilterGroupId] = useState<number | 'all'>('all')
  const [kindFilter, setKindFilter] = useState<'all' | 'registered' | 'unused'>('all')
  const [editing, setEditing] = useState<PcStatus | null>(null)
  const [clearing, setClearing] = useState<PcStatus | null>(null)

  const brokenQuery = useQuery({ queryKey: ['broken-pcs'], queryFn: brokenPcApi.list })
  const pcsQuery = useQuery({ queryKey: ['pcs'], queryFn: getAllPcs })
  const roomsQuery = useQuery({ queryKey: ['classrooms'], queryFn: getClassrooms })
  const invalidate = () => Promise.all([
    queryClient.invalidateQueries({ queryKey: ['broken-pcs'] }),
    queryClient.invalidateQueries({ queryKey: ['pcs'] }),
    queryClient.invalidateQueries({ queryKey: ['classrooms'] }),
  ])

  const availablePcs = useMemo(() => sortPcs((pcsQuery.data || []).filter((pc) => {
    const broken = pc.status?.toUpperCase() === 'BROKEN' || pc.status?.toUpperCase() === 'FAILED'
    return !broken && (groupId === '' || pc.groupId === groupId)
  })), [pcsQuery.data, groupId])
  const filtered = useMemo(() => {
    const needle = search.trim().toLocaleLowerCase('ko-KR')
    return sortPcs((brokenQuery.data || []).filter((pc) => {
      if (filterGroupId !== 'all' && pc.groupId !== filterGroupId) return false
      const unused = pc.macAddress.startsWith('BROKEN-IP::')
      if (kindFilter === 'unused' && !unused) return false
      if (kindFilter === 'registered' && unused) return false
      if (!needle) return true
      return [pc.pcName, pc.ipAddress, pc.macAddress, pc.groupName, pc.brokenNote].some((value) => value?.toLocaleLowerCase('ko-KR').includes(needle))
    }))
  }, [brokenQuery.data, filterGroupId, kindFilter, search])

  const createMutation = useMutation({
    mutationFn: brokenPcApi.create,
    onSuccess: async () => {
      await invalidate(); showToast('고장 PC를 등록했습니다.')
      setSelectedMac(''); setIpAddress(''); setSeatNumber(''); setNote('')
    },
    onError: (error) => showToast(error instanceof Error ? error.message : '고장 PC를 등록하지 못했습니다.', 'error'),
  })
  const noteMutation = useMutation({
    mutationFn: ({ mac, value }: { mac: string; value: string }) => brokenPcApi.updateNote(mac, value),
    onSuccess: async () => { await invalidate(); setEditing(null); showToast('고장 사유를 수정했습니다.') },
    onError: (error) => showToast(error instanceof Error ? error.message : '고장 사유를 수정하지 못했습니다.', 'error'),
  })
  const clearMutation = useMutation({
    mutationFn: (pc: PcStatus) => brokenPcApi.clear(pc.macAddress),
    onSuccess: async () => { await invalidate(); setClearing(null); showToast('고장 상태를 해제했습니다.') },
    onError: (error) => showToast(error instanceof Error ? error.message : '고장 상태를 해제하지 못했습니다.', 'error'),
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!canEdit) return
    if (!note.trim()) return showToast('고장 또는 미사용 사유를 입력하세요.', 'error')
    let input: BrokenPcInput
    if (mode === 'existing') {
      if (!selectedMac) return showToast('고장 처리할 PC를 선택하세요.', 'error')
      input = { macAddress: selectedMac, note: note.trim() }
    } else {
      const seat = Number(seatNumber)
      if (!groupId || !IPV4_PATTERN.test(ipAddress.trim()) || !Number.isInteger(seat) || seat <= 0) return showToast('강의실, IPv4 주소, 좌석 번호를 확인하세요.', 'error')
      input = { groupId: String(groupId), ipAddress: ipAddress.trim(), seatNumber: String(seat), note: note.trim() }
    }
    createMutation.mutate(input)
  }

  const loading = (!brokenQuery.data && brokenQuery.isLoading) || (!pcsQuery.data && pcsQuery.isLoading) || (!roomsQuery.data && roomsQuery.isLoading)
  const error = (!brokenQuery.data && brokenQuery.error) || (!pcsQuery.data && pcsQuery.error) || (!roomsQuery.data && roomsQuery.error)
  if (loading) return <section className="content-section"><PageLoading /></section>
  if (error) return <section className="content-section"><PageError message={error instanceof Error ? error.message : '고장 PC 정보를 불러오지 못했습니다.'} onRetry={() => void invalidate()} /></section>

  return <section className="content-section">
    <header className="page-header"><div><h1>고장 PC</h1><p>현재 고장 또는 미사용으로 표시된 PC {brokenQuery.data?.length || 0}대</p></div><button className="icon-button bordered" onClick={() => void invalidate()} aria-label="새로고침"><RefreshCw size={17} /></button></header>
    {(brokenQuery.isError || pcsQuery.isError || roomsQuery.isError) && <div className="form-error" role="alert">새 데이터 갱신에 실패해 마지막으로 받은 목록을 표시하고 있습니다.</div>}
    {canEdit && <form className="management-form" onSubmit={submit}>
      <div className="management-form-header"><div><h2>고장 PC 등록</h2><p>기존 PC의 고장 처리 또는 빈 좌석을 지정합니다.</p></div><div className="segmented-text"><button type="button" className={mode === 'existing' ? 'active' : ''} onClick={() => setMode('existing')}>기존 PC</button><button type="button" className={mode === 'unused' ? 'active' : ''} onClick={() => setMode('unused')}>미사용 좌석</button></div></div>
      <div className="form-grid">
        <label><span>강의실</span><select value={groupId} onChange={(event) => { setGroupId(event.target.value ? Number(event.target.value) : ''); setSelectedMac('') }} required={mode === 'unused'}><option value="">{mode === 'existing' ? '전체 강의실' : '강의실 선택'}</option>{(roomsQuery.data || []).map((room) => <option key={room.groupId} value={room.groupId}>{room.groupName}</option>)}</select></label>
        {mode === 'existing' ? <label className="form-span-2"><span>PC 이름</span><select value={selectedMac} onChange={(event) => setSelectedMac(event.target.value)} required><option value="">PC 선택</option>{availablePcs.map((pc) => <option key={pc.macAddress} value={pc.macAddress}>{pc.pcName} · {pc.ipAddress || '-'} · {pc.macAddress}</option>)}</select></label> : <><label><span>IP 주소</span><input value={ipAddress} onChange={(event) => setIpAddress(event.target.value)} placeholder="예: 192.168.10.25" required /></label><label><span>좌석 번호</span><input type="number" min="1" value={seatNumber} onChange={(event) => setSeatNumber(event.target.value)} placeholder="예: 9" required /></label></>}
        <label className="form-span-2"><span>고장 또는 미사용 사유</span><input value={note} onChange={(event) => setNote(event.target.value)} maxLength={500} placeholder="예: 메인보드 고장" required /></label>
        <button className="button primary" disabled={createMutation.isPending}><Plus size={16} />{createMutation.isPending ? '등록 중' : '등록'}</button>
      </div>
    </form>}

    <div className="section-toolbar"><div><h2>고장 PC 목록</h2><p>{filtered.length}대 표시</p></div><div className="toolbar-actions"><label className="toolbar-search"><Search size={16} /><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="PC, IP, 사유 검색" /></label><select value={kindFilter} onChange={(event) => setKindFilter(event.target.value as typeof kindFilter)} aria-label="등록 유형 필터"><option value="all">전체 유형</option><option value="registered">기존 PC</option><option value="unused">미사용 좌석</option></select><select value={filterGroupId} onChange={(event) => setFilterGroupId(event.target.value === 'all' ? 'all' : Number(event.target.value))}><option value="all">전체 강의실</option>{(roomsQuery.data || []).map((room) => <option key={room.groupId} value={room.groupId}>{room.groupName}</option>)}</select></div></div>
    {!filtered.length ? <EmptyState message="등록된 고장 PC가 없습니다." /> : <div className="table-scroll"><table className="data-table"><thead><tr><th>강의실</th><th>PC 이름</th><th>IP 주소</th><th>MAC 주소</th><th>고장 사유</th><th>마지막 연결</th><th aria-label="관리" /></tr></thead><tbody>{filtered.map((pc) => <tr key={pc.macAddress}><td>{pc.groupName || '-'}</td><td><strong>{pc.pcName}</strong></td><td>{pc.ipAddress || '-'}</td><td>{pc.macAddress}</td><td>{pc.brokenNote || '-'}</td><td>{formatDate(pc.lastSeen)}</td><td><div className="row-actions">{canEdit && <button className="icon-button bordered" onClick={() => setEditing(pc)} aria-label={`${pc.pcName} 고장 사유 수정`} title="고장 사유 수정"><Pencil size={15} /></button>}{canClear && <button className="button secondary compact" onClick={() => setClearing(pc)}><Wrench size={15} />고장 해제</button>}</div></td></tr>)}</tbody></table></div>}
    <NoteDialog key={editing?.macAddress || 'none'} pc={editing} busy={noteMutation.isPending} onClose={() => setEditing(null)} onSave={(value) => editing && noteMutation.mutate({ mac: editing.macAddress, value })} />
    <ConfirmDialog open={Boolean(clearing)} title="고장 상태 해제" description={`${clearing?.groupName || '-'} ${clearing?.pcName || ''}의 고장 상태를 해제합니다. 미사용 좌석 항목은 목록에서 삭제됩니다.`} confirmLabel="고장 해제" requiredText={clearing?.pcName || ''} requiredTextLabel={`PC 이름 '${clearing?.pcName || ''}' 입력`} busy={clearMutation.isPending} onCancel={() => setClearing(null)} onConfirm={() => clearing && clearMutation.mutate(clearing)} />
  </section>
}
