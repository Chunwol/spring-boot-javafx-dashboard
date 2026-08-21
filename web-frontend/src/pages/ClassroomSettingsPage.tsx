import { type FormEvent, useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { MonitorCog, Network, Pencil, Plus, RefreshCw, Trash2, UserRoundCog } from 'lucide-react'
import { useAuth } from '../auth/AuthProvider'
import { ConfirmDialog } from '../components/ui/ConfirmDialog'
import { EmptyState, PageError, PageLoading } from '../components/ui/PageState'
import { useToast } from '../components/ui/ToastProvider'
import { getClassrooms } from '../features/classrooms/api'
import { classroomSettingsApi, type ClassroomGroup, type IpRange, type TeacherDevice } from '../features/classroomSettings/api'
import { IndividualControlDialog } from '../features/classroomSettings/IndividualControlDialog'
import { isValidCidr, isValidIpv4 } from '../features/classroomSettings/model'

type Tab = 'rooms' | 'ranges' | 'teachers'
type DeleteTarget = { type: 'room'; room: ClassroomGroup } | { type: 'range'; range: IpRange } | { type: 'teacher'; device: TeacherDevice }

export function ClassroomSettingsPage() {
  const { isAdmin, canWrite } = useAuth()
  const { showToast } = useToast()
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()
  const requestedTab = searchParams.get('tab')
  const [tab, setTab] = useState<Tab>(requestedTab === 'ranges' || requestedTab === 'teachers' ? requestedTab : isAdmin ? 'rooms' : 'ranges')
  const [selectedGroupId, setSelectedGroupId] = useState<number | null>(null)
  const [roomName, setRoomName] = useState('')
  const [roomDescription, setRoomDescription] = useState('')
  const [editingRoomId, setEditingRoomId] = useState<number | null>(null)
  const [rangeMode, setRangeMode] = useState<'cidr' | 'pair'>('cidr')
  const [cidr, setCidr] = useState('')
  const [startIp, setStartIp] = useState('')
  const [endIp, setEndIp] = useState('')
  const [rangeDescription, setRangeDescription] = useState('')
  const [teacherIp, setTeacherIp] = useState('')
  const [teacherMac, setTeacherMac] = useState('')
  const [teacherDescription, setTeacherDescription] = useState('')
  const [deleting, setDeleting] = useState<DeleteTarget | null>(null)
  const [controlRange, setControlRange] = useState<IpRange | null>(null)

  const summariesQuery = useQuery({ queryKey: ['classrooms'], queryFn: getClassrooms })
  const groupsQuery = useQuery({ queryKey: ['groups'], queryFn: classroomSettingsApi.groups, enabled: isAdmin })
  const rooms = useMemo<ClassroomGroup[]>(() => isAdmin && groupsQuery.data
    ? groupsQuery.data
    : (summariesQuery.data || []).map((room) => ({ id: room.groupId, name: room.groupName })), [groupsQuery.data, isAdmin, summariesQuery.data])
  useEffect(() => {
    if (selectedGroupId == null && rooms.length) setSelectedGroupId(rooms[0].id)
    if (selectedGroupId != null && rooms.length && !rooms.some((room) => room.id === selectedGroupId)) setSelectedGroupId(rooms[0].id)
  }, [rooms, selectedGroupId])
  useEffect(() => {
    const next = searchParams.get('tab')
    if (next === 'ranges' || next === 'teachers' || (next === 'rooms' && isAdmin)) setTab(next)
  }, [isAdmin, searchParams])

  const selectTab = (next: Tab) => {
    setTab(next)
    setSearchParams(next === (isAdmin ? 'rooms' : 'ranges') ? {} : { tab: next }, { replace: true })
  }

  const rangesQuery = useQuery({ queryKey: ['ip-ranges', selectedGroupId], queryFn: () => classroomSettingsApi.ipRanges(selectedGroupId!), enabled: selectedGroupId != null && tab === 'ranges' })
  const teachersQuery = useQuery({ queryKey: ['teacher-devices', selectedGroupId], queryFn: () => classroomSettingsApi.teacherDevices(selectedGroupId!), enabled: selectedGroupId != null && tab === 'teachers' })
  const invalidateRooms = () => Promise.all([queryClient.invalidateQueries({ queryKey: ['groups'] }), queryClient.invalidateQueries({ queryKey: ['classrooms'] })])

  const roomMutation = useMutation({
    mutationFn: () => editingRoomId ? classroomSettingsApi.updateGroup(editingRoomId, { name: roomName.trim(), description: roomDescription.trim() }) : classroomSettingsApi.createGroup({ name: roomName.trim(), description: roomDescription.trim() }),
    onSuccess: async () => { await invalidateRooms(); showToast(editingRoomId ? '강의실 정보를 수정했습니다.' : '강의실을 추가했습니다.'); setRoomName(''); setRoomDescription(''); setEditingRoomId(null) },
    onError: (error) => showToast(error instanceof Error ? error.message : '강의실을 저장하지 못했습니다.', 'error'),
  })
  const rangeMutation = useMutation({
    mutationFn: () => classroomSettingsApi.createIpRange(selectedGroupId!, rangeMode === 'cidr' ? { cidr: cidr.trim(), description: rangeDescription.trim() } : { startIp: startIp.trim(), endIp: endIp.trim(), description: rangeDescription.trim() }),
    onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: ['ip-ranges', selectedGroupId] }); showToast('IP 범위를 추가했습니다.'); setCidr(''); setStartIp(''); setEndIp(''); setRangeDescription('') },
    onError: (error) => showToast(error instanceof Error ? error.message : 'IP 범위를 추가하지 못했습니다.', 'error'),
  })
  const teacherMutation = useMutation({
    mutationFn: () => classroomSettingsApi.createTeacherDevice(selectedGroupId!, { ipAddress: teacherIp.trim() || undefined, macAddress: teacherMac.trim() || undefined, description: teacherDescription.trim() }),
    onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: ['teacher-devices', selectedGroupId] }); showToast('교사 PC를 추가했습니다.'); setTeacherIp(''); setTeacherMac(''); setTeacherDescription('') },
    onError: (error) => showToast(error instanceof Error ? error.message : '교사 PC를 추가하지 못했습니다.', 'error'),
  })
  const deleteMutation = useMutation({
    mutationFn: async (target: DeleteTarget) => {
      if (target.type === 'room') return classroomSettingsApi.deleteGroup(target.room.id)
      if (target.type === 'range') return classroomSettingsApi.deleteIpRange(target.range.id)
      return classroomSettingsApi.deleteTeacherDevice(target.device.id)
    },
    onSuccess: async (_, target) => {
      if (target.type === 'room') await invalidateRooms()
      if (target.type === 'range') await queryClient.invalidateQueries({ queryKey: ['ip-ranges', selectedGroupId] })
      if (target.type === 'teacher') await queryClient.invalidateQueries({ queryKey: ['teacher-devices', selectedGroupId] })
      showToast('삭제했습니다.'); setDeleting(null)
    },
    onError: (error) => showToast(error instanceof Error ? error.message : '삭제하지 못했습니다.', 'error'),
  })

  const roomSubmit = (event: FormEvent) => { event.preventDefault(); if (roomName.trim()) roomMutation.mutate() }
  const rangeSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (!selectedGroupId) return
    if (rangeMode === 'cidr' && !isValidCidr(cidr)) return showToast('올바른 CIDR을 입력하세요.', 'error')
    if (rangeMode === 'pair' && (!isValidIpv4(startIp) || !isValidIpv4(endIp))) return showToast('올바른 시작/끝 IPv4 주소를 입력하세요.', 'error')
    rangeMutation.mutate()
  }
  const teacherSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (!selectedGroupId || !isValidIpv4(teacherIp)) return showToast('교사 PC의 올바른 IPv4 주소를 입력하세요.', 'error')
    teacherMutation.mutate()
  }
  const loading = summariesQuery.isLoading || (isAdmin && groupsQuery.isLoading)
  const error = summariesQuery.error || groupsQuery.error
  if (loading) return <section className="content-section"><PageLoading /></section>
  if (error) return <section className="content-section"><PageError message={error instanceof Error ? error.message : '강의실 설정을 불러오지 못했습니다.'} onRetry={() => void invalidateRooms()} /></section>

  const deleteName = deleting?.type === 'room' ? deleting.room.name : '확인했습니다'
  return <section className="content-section">
    <header className="page-header"><div><h1>강의실 설정</h1><p>강의실, IP 범위, 교사 PC를 한곳에서 관리합니다.</p></div><button className="icon-button bordered" onClick={() => void invalidateRooms()} aria-label="새로고침"><RefreshCw size={17} /></button></header>
    <div className="page-tabs" role="tablist">{isAdmin && <button className={tab === 'rooms' ? 'active' : ''} onClick={() => selectTab('rooms')}>강의실</button>}<button className={tab === 'ranges' ? 'active' : ''} onClick={() => selectTab('ranges')}>IP 범위</button><button className={tab === 'teachers' ? 'active' : ''} onClick={() => selectTab('teachers')}>교사 PC</button></div>

    {tab === 'rooms' && isAdmin && <><form className="management-form" onSubmit={roomSubmit}><div className="management-form-header"><div><h2>{editingRoomId ? '강의실 수정' : '강의실 추가'}</h2><p>이름은 강의실 목록과 PC 필터에 표시됩니다.</p></div></div><div className="form-grid"><label><span>강의실 이름</span><input value={roomName} onChange={(event) => setRoomName(event.target.value)} maxLength={100} required /></label><label className="form-span-2"><span>설명</span><input value={roomDescription} onChange={(event) => setRoomDescription(event.target.value)} maxLength={255} /></label><button className="button primary" disabled={roomMutation.isPending}><Plus size={16} />{editingRoomId ? '수정' : '추가'}</button>{editingRoomId && <button type="button" className="button secondary" onClick={() => { setEditingRoomId(null); setRoomName(''); setRoomDescription('') }}>취소</button>}</div></form>{!rooms.length ? <EmptyState message="등록된 강의실이 없습니다." /> : <div className="table-scroll"><table className="data-table"><thead><tr><th>강의실</th><th>설명</th><th>생성 일시</th><th aria-label="관리" /></tr></thead><tbody>{rooms.map((room) => <tr key={room.id}><td><strong>{room.name}</strong></td><td>{room.description || '-'}</td><td>{room.createdAt || '-'}</td><td><div className="row-actions"><button className="icon-button bordered" onClick={() => { setEditingRoomId(room.id); setRoomName(room.name); setRoomDescription(room.description || '') }} aria-label={`${room.name} 수정`}><Pencil size={15} /></button><button className="icon-button danger-icon" onClick={() => setDeleting({ type: 'room', room })} aria-label={`${room.name} 삭제`}><Trash2 size={15} /></button></div></td></tr>)}</tbody></table></div>}</>}

    {(tab === 'ranges' || tab === 'teachers') && <><div className="section-toolbar"><div><h2>{tab === 'ranges' ? 'IP 범위' : '교사 PC'}</h2><p>대상 강의실을 선택하세요.</p></div><select value={selectedGroupId || ''} onChange={(event) => setSelectedGroupId(Number(event.target.value))}>{rooms.map((room) => <option key={room.id} value={room.id}>{room.name}</option>)}</select></div>{!canWrite ? <div className="permission-panel">보기 권한 계정은 설정을 변경할 수 없습니다.</div> : tab === 'ranges' ? <form className="management-form" onSubmit={rangeSubmit}><div className="management-form-header"><div><h2>IP 범위 추가</h2><p>하나의 CIDR 또는 시작/끝 IP를 입력합니다.</p></div><div className="segmented-text"><button type="button" className={rangeMode === 'cidr' ? 'active' : ''} onClick={() => setRangeMode('cidr')}>CIDR</button><button type="button" className={rangeMode === 'pair' ? 'active' : ''} onClick={() => setRangeMode('pair')}>시작/끝</button></div></div><div className="form-grid">{rangeMode === 'cidr' ? <label><span>CIDR</span><input value={cidr} onChange={(event) => setCidr(event.target.value)} placeholder="192.168.10.0/24" required /></label> : <><label><span>시작 IP</span><input value={startIp} onChange={(event) => setStartIp(event.target.value)} required /></label><label><span>끝 IP</span><input value={endIp} onChange={(event) => setEndIp(event.target.value)} required /></label></>}<label className="form-span-2"><span>설명</span><input value={rangeDescription} onChange={(event) => setRangeDescription(event.target.value)} /></label><button className="button primary" disabled={rangeMutation.isPending}><Network size={16} />추가</button></div></form> : <form className="management-form" onSubmit={teacherSubmit}><div className="management-form-header"><div><h2>교사 PC 추가</h2><p>IP 주소는 필수이며 MAC 주소와 설명은 선택입니다.</p></div></div><div className="form-grid"><label><span>IP 주소</span><input value={teacherIp} onChange={(event) => setTeacherIp(event.target.value)} placeholder="192.168.10.2" required /></label><label><span>MAC 주소</span><input value={teacherMac} onChange={(event) => setTeacherMac(event.target.value)} /></label><label className="form-span-2"><span>설명</span><input value={teacherDescription} onChange={(event) => setTeacherDescription(event.target.value)} /></label><button className="button primary" disabled={teacherMutation.isPending}><UserRoundCog size={16} />추가</button></div></form>}
      {tab === 'ranges' ? rangesQuery.isLoading ? <PageLoading /> : rangesQuery.isError ? <PageError message={rangesQuery.error instanceof Error ? rangesQuery.error.message : 'IP 범위를 불러오지 못했습니다.'} onRetry={() => void rangesQuery.refetch()} /> : !(rangesQuery.data || []).length ? <EmptyState message="등록된 IP 범위가 없습니다." /> : <div className="table-scroll"><table className="data-table"><thead><tr><th>범위</th><th>설명</th><th aria-label="관리" /></tr></thead><tbody>{rangesQuery.data?.map((range) => <tr key={range.id}><td>{range.cidr || `${range.startIp || '-'} ~ ${range.endIp || '-'}`}</td><td>{range.description || '-'}</td><td><div className="row-actions">{isAdmin && <button className="icon-button bordered" onClick={() => setControlRange(range)} aria-label="IP 범위 개별 제어" title="개별 PC 제어"><MonitorCog size={15} /></button>}{canWrite && <button className="icon-button danger-icon" onClick={() => setDeleting({ type: 'range', range })} aria-label="IP 범위 삭제"><Trash2 size={15} /></button>}</div></td></tr>)}</tbody></table></div> : teachersQuery.isLoading ? <PageLoading /> : teachersQuery.isError ? <PageError message={teachersQuery.error instanceof Error ? teachersQuery.error.message : '교사 PC를 불러오지 못했습니다.'} onRetry={() => void teachersQuery.refetch()} /> : !(teachersQuery.data || []).length ? <EmptyState message="등록된 교사 PC가 없습니다." /> : <div className="table-scroll"><table className="data-table"><thead><tr><th>IP 주소</th><th>MAC 주소</th><th>설명</th><th aria-label="관리" /></tr></thead><tbody>{teachersQuery.data?.map((device) => <tr key={device.id}><td>{device.ipAddress || '-'}</td><td>{device.macAddress || '-'}</td><td>{device.description || '-'}</td><td>{canWrite && <button className="icon-button danger-icon" onClick={() => setDeleting({ type: 'teacher', device })} aria-label="교사 PC 삭제"><Trash2 size={15} /></button>}</td></tr>)}</tbody></table></div>}</>}
    {controlRange && <IndividualControlDialog range={controlRange} onClose={() => setControlRange(null)} />}
    <ConfirmDialog open={Boolean(deleting)} title={deleting?.type === 'room' ? '강의실 삭제' : deleting?.type === 'range' ? 'IP 범위 삭제' : '교사 PC 삭제'} description={deleting?.type === 'room' ? '소속 PC가 미배정 상태로 바뀌고 진행 중인 명령이 취소되며, IP 범위와 교사 PC 정보도 삭제됩니다.' : '선택한 항목을 삭제합니다. 실수 방지를 위해 확인 문구를 입력하세요.'} confirmLabel="삭제" danger requiredText={deleteName} requiredTextLabel={deleting?.type === 'room' ? `강의실 이름 '${deleteName}' 입력` : `'확인했습니다' 입력`} busy={deleteMutation.isPending} onCancel={() => setDeleting(null)} onConfirm={() => deleting && deleteMutation.mutate(deleting)} />
  </section>
}
