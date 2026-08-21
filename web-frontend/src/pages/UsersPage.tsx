import { type FormEvent, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Pencil, Plus, RefreshCw, Search, Trash2, UserCog, X } from 'lucide-react'
import { useAuth } from '../auth/AuthProvider'
import { ConfirmDialog } from '../components/ui/ConfirmDialog'
import { EmptyState, PageError, PageLoading } from '../components/ui/PageState'
import { useToast } from '../components/ui/ToastProvider'
import { useDialogFocus } from '../components/ui/useDialogFocus'
import {
  usersApi,
  type CreateUserInput,
  type ManagedUser,
  type UpdateUserInput,
  type UserRole,
} from '../features/users/api'
import {
  filterAndSortUsers,
  isCurrentAccount,
  ROLE_OPTIONS,
  roleLabel,
  validateCreateUser,
  validateUpdateUser,
  type UserFormErrors,
} from '../features/users/model'

type EditorState = { mode: 'create' } | { mode: 'edit'; user: ManagedUser }
type SaveAction =
  | { mode: 'create'; input: CreateUserInput }
  | { mode: 'edit'; id: number; input: UpdateUserInput }

function UserEditor({ state, busy, onCancel, onSave }: {
  state: EditorState
  busy: boolean
  onCancel: () => void
  onSave: (action: SaveAction) => void
}) {
  const editing = state.mode === 'edit' ? state.user : null
  const [username, setUsername] = useState(editing?.username || '')
  const [email, setEmail] = useState(editing?.email || '')
  const [password, setPassword] = useState('')
  const [role, setRole] = useState<UserRole>(editing?.role || 'USER')
  const [errors, setErrors] = useState<UserFormErrors>({})
  const dialogRef = useDialogFocus<HTMLFormElement>(onCancel, busy)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (state.mode === 'create') {
      const result = validateCreateUser({ username, email, password, role })
      setErrors(result.errors)
      if (result.success) onSave({ mode: 'create', input: result.data })
      return
    }
    const result = validateUpdateUser({ username, email, role })
    setErrors(result.errors)
    if (result.success) onSave({ mode: 'edit', id: state.user.id, input: result.data })
  }

  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !busy) onCancel()
    }}>
      <form ref={dialogRef} className="confirm-dialog" style={{ textAlign: 'left', width: 'min(100%, 520px)' }} onSubmit={submit} role="dialog" aria-modal="true" aria-labelledby="user-editor-title" tabIndex={-1}>
        <button type="button" className="icon-button modal-close" onClick={onCancel} disabled={busy} aria-label="닫기"><X size={18} /></button>
        <span className="confirm-icon"><UserCog size={21} /></span>
        <h2 id="user-editor-title" style={{ textAlign: 'center' }}>{editing ? '사용자 수정' : '사용자 추가'}</h2>
        <p style={{ textAlign: 'center' }}>{editing ? '로그인 ID, 이메일과 역할을 수정합니다.' : '새 대시보드 계정을 등록합니다.'}</p>

        <label className="field-label" htmlFor="managed-username">사용자명</label>
        <div className="input-with-icon"><input id="managed-username" value={username} onChange={(event) => setUsername(event.target.value)} maxLength={50} autoComplete="off" autoFocus aria-invalid={Boolean(errors.username)} /></div>
        {errors.username && <p className="field-error">{errors.username}</p>}

        <label className="field-label" htmlFor="managed-email">이메일</label>
        <div className="input-with-icon"><input id="managed-email" type="email" value={email} onChange={(event) => setEmail(event.target.value)} maxLength={200} autoComplete="email" aria-invalid={Boolean(errors.email)} /></div>
        {errors.email && <p className="field-error">{errors.email}</p>}

        {!editing && <>
          <label className="field-label" htmlFor="managed-password">초기 비밀번호</label>
          <div className="input-with-icon"><input id="managed-password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} minLength={8} maxLength={100} autoComplete="new-password" aria-invalid={Boolean(errors.password)} /></div>
          {errors.password && <p className="field-error">{errors.password}</p>}
        </>}

        <label className="field-label" htmlFor="managed-role">역할</label>
        <select id="managed-role" value={role} onChange={(event) => setRole(event.target.value as UserRole)} aria-invalid={Boolean(errors.role)} style={{ width: '100%', minHeight: 43, padding: '0 11px', border: '1px solid var(--border-strong)', borderRadius: 6, background: 'white' }}>
          {ROLE_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
        </select>
        <p style={{ margin: '6px 0 0', color: 'var(--muted)', fontSize: 11 }}>{ROLE_OPTIONS.find((option) => option.value === role)?.description}</p>
        {errors.role && <p className="field-error">{errors.role}</p>}

        <div className="modal-actions">
          <button type="button" className="button secondary" onClick={onCancel} disabled={busy}>취소</button>
          <button className="button primary" disabled={busy}>{busy ? '저장 중' : editing ? '변경 저장' : '사용자 추가'}</button>
        </div>
      </form>
    </div>
  )
}

export function UsersPage() {
  const { isAdmin, user: signedInUser } = useAuth()
  const { showToast } = useToast()
  const queryClient = useQueryClient()
  const [search, setSearch] = useState('')
  const [editor, setEditor] = useState<EditorState | null>(null)
  const [deleting, setDeleting] = useState<ManagedUser | null>(null)

  const usersQuery = useQuery({
    queryKey: ['users'],
    queryFn: usersApi.list,
    enabled: isAdmin,
  })
  const displayedUsers = useMemo(
    () => filterAndSortUsers(usersQuery.data || [], search),
    [search, usersQuery.data],
  )
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['users'] })

  const saveMutation = useMutation({
    mutationFn: (action: SaveAction) => action.mode === 'create'
      ? usersApi.create(action.input)
      : usersApi.update(action.id, action.input),
    onSuccess: async (_, action) => {
      await refresh()
      setEditor(null)
      showToast(action.mode === 'create' ? '사용자를 추가했습니다.' : '사용자 정보를 수정했습니다.')
    },
    onError: (error) => showToast(error instanceof Error ? error.message : '사용자 정보를 저장하지 못했습니다.', 'error'),
  })
  const deleteMutation = useMutation({
    mutationFn: (target: ManagedUser) => {
      if (isCurrentAccount(target, signedInUser?.username)) {
        throw new Error('현재 로그인한 계정은 삭제할 수 없습니다.')
      }
      return usersApi.delete(target.id)
    },
    onSuccess: async () => {
      await refresh()
      setDeleting(null)
      showToast('사용자를 삭제했습니다.')
    },
    onError: (error) => showToast(error instanceof Error ? error.message : '사용자를 삭제하지 못했습니다.', 'error'),
  })

  if (!isAdmin) {
    return <section className="content-section"><PageError message="사용자 관리는 관리자 계정만 이용할 수 있습니다." /></section>
  }
  if (usersQuery.isLoading) return <section className="content-section"><PageLoading /></section>
  if (usersQuery.isError) {
    return <section className="content-section"><PageError message={usersQuery.error instanceof Error ? usersQuery.error.message : '사용자 목록을 불러오지 못했습니다.'} onRetry={() => void refresh()} /></section>
  }

  return (
    <section className="content-section">
      <header className="page-header">
        <div><h1>사용자 관리</h1><p>대시보드 계정 {usersQuery.data?.length || 0}개</p></div>
        <div className="toolbar-actions">
          <button className="icon-button bordered" onClick={() => void refresh()} disabled={usersQuery.isFetching} aria-label="새로고침"><RefreshCw className={usersQuery.isFetching ? 'spin-icon' : ''} size={17} /></button>
          <button className="button primary" onClick={() => setEditor({ mode: 'create' })}><Plus size={16} />사용자 추가</button>
        </div>
      </header>

      <div className="section-toolbar">
        <div><h2>계정 목록</h2><p>{displayedUsers.length}개 표시</p></div>
        <label className="toolbar-search"><Search size={16} /><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="사용자명, 이메일, 역할 검색" /></label>
      </div>

      {!displayedUsers.length ? <EmptyState message={search.trim() ? '검색 조건에 맞는 사용자가 없습니다.' : '등록된 사용자가 없습니다.'} /> : (
        <div className="table-scroll">
          <table className="data-table">
            <thead><tr><th>사용자명</th><th>이메일</th><th>역할</th><th>계정 상태</th><th aria-label="관리" /></tr></thead>
            <tbody>{displayedUsers.map((managedUser) => {
              const current = isCurrentAccount(managedUser, signedInUser?.username)
              const roleClass = managedUser.role === 'ADMIN' ? 'warning' : managedUser.role === 'VIEWER' ? 'offline' : 'online'
              return <tr key={managedUser.id}>
                <td><strong>{managedUser.username}</strong></td>
                <td>{managedUser.email}</td>
                <td><span className={`status-badge ${roleClass}`}>{roleLabel(managedUser.role)}</span></td>
                <td>{current ? <span className="status-badge running">현재 로그인</span> : '-'}</td>
                <td><div className="row-actions">
                  <button className="icon-button bordered" onClick={() => setEditor({ mode: 'edit', user: managedUser })} title="사용자 수정" aria-label={`${managedUser.username} 수정`}><Pencil size={15} /></button>
                  <button className="icon-button danger-icon" onClick={() => !current && setDeleting(managedUser)} disabled={current} title={current ? '현재 로그인한 계정은 삭제할 수 없습니다.' : '사용자 삭제'} aria-label={`${managedUser.username} 삭제`}><Trash2 size={15} /></button>
                </div></td>
              </tr>
            })}</tbody>
          </table>
        </div>
      )}

      {editor && <UserEditor key={editor.mode === 'edit' ? editor.user.id : 'create'} state={editor} busy={saveMutation.isPending} onCancel={() => setEditor(null)} onSave={(action) => saveMutation.mutate(action)} />}
      <ConfirmDialog
        open={Boolean(deleting)}
        title="사용자 삭제"
        description={`${deleting?.username || ''} 계정을 삭제합니다. 삭제 후에는 복구할 수 없습니다.`}
        confirmLabel="사용자 삭제"
        danger
        requiredText={deleting?.username || ''}
        requiredTextLabel={`사용자명 '${deleting?.username || ''}' 입력`}
        busy={deleteMutation.isPending}
        onCancel={() => setDeleting(null)}
        onConfirm={() => deleting && deleteMutation.mutate(deleting)}
      />
    </section>
  )
}
