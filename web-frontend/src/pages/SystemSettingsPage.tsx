import { type FormEvent, useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bell, KeyRound, Save } from 'lucide-react'
import { useAuth } from '../auth/AuthProvider'
import { PageError, PageLoading } from '../components/ui/PageState'
import { useToast } from '../components/ui/ToastProvider'
import { settingsApi } from '../features/settings/api'
import { ALERT_FILTERS, alertFilterSelected, parseHiddenAlertTypes, toggleAlertFilter } from '../features/settings/alertFilter'

export function SystemSettingsPage() {
  const { isAdmin } = useAuth()
  const { showToast } = useToast()
  const queryClient = useQueryClient()
  const [hiddenTypes, setHiddenTypes] = useState<Set<string>>(() => new Set(['PC_OFFLINE', 'TRAFFIC_HIGH']))
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const filterQuery = useQuery({ queryKey: ['setting', 'alert-type-filter'], queryFn: () => settingsApi.get('alert-type-filter') })

  useEffect(() => {
    if (filterQuery.data) setHiddenTypes(parseHiddenAlertTypes(filterQuery.data.value))
  }, [filterQuery.data])

  const saveMutation = useMutation({
    mutationFn: () => settingsApi.put('alert-type-filter', JSON.stringify([...hiddenTypes].sort())),
    onSuccess: async () => { await queryClient.invalidateQueries({ queryKey: ['setting', 'alert-type-filter'] }); showToast('알림 표시 설정을 저장했습니다.') },
    onError: (error) => showToast(error instanceof Error ? error.message : '설정을 저장하지 못했습니다.', 'error'),
  })
  const passwordMutation = useMutation({
    mutationFn: () => settingsApi.changePassword(currentPassword, newPassword),
    onSuccess: () => { setCurrentPassword(''); setNewPassword(''); setConfirmPassword(''); showToast('비밀번호를 변경했습니다.') },
    onError: (error) => showToast(error instanceof Error ? error.message : '비밀번호를 변경하지 못했습니다.', 'error'),
  })
  const submitPassword = (event: FormEvent) => {
    event.preventDefault()
    if (newPassword.length < 8) return showToast('새 비밀번호는 8자 이상이어야 합니다.', 'error')
    if (newPassword !== confirmPassword) return showToast('새 비밀번호가 일치하지 않습니다.', 'error')
    passwordMutation.mutate()
  }

  if (filterQuery.isLoading) return <section className="content-section"><PageLoading /></section>
  if (filterQuery.isError) return <section className="content-section"><PageError message={filterQuery.error instanceof Error ? filterQuery.error.message : '설정을 불러오지 못했습니다.'} onRetry={() => void filterQuery.refetch()} /></section>

  return <section className="content-section">
    <header className="page-header"><div><h1>시스템 설정</h1><p>현재 계정의 알림 표시 설정을 관리합니다.</p></div></header>
    <div className="settings-stack">
      <section className="settings-section"><header><span className="summary-icon"><Bell size={19} /></span><div><h2>알림 표시 필터</h2><p>상단 알림 센터에 표시할 운영 알림을 선택합니다.</p></div><button className="button primary" onClick={() => saveMutation.mutate()} disabled={saveMutation.isPending}><Save size={16} />{saveMutation.isPending ? '저장 중' : '저장'}</button></header><div className="settings-check-grid">{ALERT_FILTERS.map((item) => <label className="setting-check" key={item.key}><input type="checkbox" checked={alertFilterSelected(item.key, hiddenTypes)} onChange={(event) => setHiddenTypes(toggleAlertFilter(hiddenTypes, item.key, event.target.checked))} /><span><strong>{item.label}</strong><small>{item.description}</small></span></label>)}</div></section>
      {isAdmin && <section className="settings-section"><header><span className="summary-icon"><KeyRound size={19} /></span><div><h2>관리자 비밀번호 변경</h2><p>현재 비밀번호를 확인한 뒤 새 비밀번호를 적용합니다.</p></div></header><form className="password-form" onSubmit={submitPassword}><label><span>현재 비밀번호</span><input type="password" value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} autoComplete="current-password" required /></label><label><span>새 비밀번호</span><input type="password" minLength={8} value={newPassword} onChange={(event) => setNewPassword(event.target.value)} autoComplete="new-password" required /></label><label><span>새 비밀번호 확인</span><input type="password" minLength={8} value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} autoComplete="new-password" required /></label><button className="button primary" disabled={passwordMutation.isPending}>{passwordMutation.isPending ? '변경 중' : '비밀번호 변경'}</button></form></section>}
    </div>
  </section>
}
