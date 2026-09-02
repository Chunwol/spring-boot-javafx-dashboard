import { useEffect, useMemo, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import {
  Activity,
  AlertTriangle,
  BarChart3,
  ChevronDown,
  Command,
  KeyRound,
  LogOut,
  Menu,
  MonitorCog,
  Network,
  FolderUp,
  FileCode2,
  School,
  Search,
  Settings,
  ShieldCheck,
  Users,
  X,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { useAuth } from '../auth/AuthProvider'
import type { FeaturePermission } from '../auth/permissions'
import { AlertsMenu } from '../components/alerts/AlertsMenu'
import { ConfirmDialog } from '../components/ui/ConfirmDialog'
import { useRealtimeUpdates } from '../features/realtime/useRealtimeUpdates'

type NavigationItem = { to: string; label: string; icon: LucideIcon; permission?: FeaturePermission; adminOnly?: boolean }

const navigation: ReadonlyArray<NavigationItem> = [
  { to: '/classrooms', label: '강의실 현황', icon: Activity, permission: 'CLASSROOM_VIEW' },
  { to: '/pcs', label: 'PC 관리', icon: MonitorCog, permission: 'PC_VIEW' },
  { to: '/commands', label: '명령 발행 / 이력', icon: Command, permission: 'COMMAND_VIEW' },
  { to: '/script-library', label: '스크립트 보관함', icon: FileCode2, permission: 'SCRIPT_LIBRARY_VIEW' },
  { to: '/tokens', label: '토큰 관리', icon: KeyRound, permission: 'TOKEN_VIEW' },
  { to: '/broken', label: '고장 PC', icon: AlertTriangle, permission: 'BROKEN_PC_VIEW' },
  { to: '/network', label: '네트워크', icon: Network, permission: 'NETWORK_VIEW' },
  { to: '/reports', label: '보고서', icon: BarChart3, permission: 'REPORT_VIEW' },
  { to: '/files', label: '파일 배포', icon: FolderUp, permission: 'FILE_DISTRIBUTION_VIEW' },
]

const settingsNavigation: ReadonlyArray<NavigationItem> = [
  { to: '/settings/classrooms', label: '강의실 설정', icon: School, permission: 'CLASSROOM_SETTINGS_VIEW' },
  { to: '/settings/users', label: '사용자 관리', icon: Users, adminOnly: true },
  { to: '/settings/system', label: '시스템 설정', icon: Settings, permission: 'SYSTEM_SETTINGS_VIEW' },
]

export function AppShell() {
  const { user, logout, isAdmin, hasPermission } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const [profileOpen, setProfileOpen] = useState(false)
  const [logoutOpen, setLogoutOpen] = useState(false)
  const [search, setSearch] = useState('')
  useRealtimeUpdates()

  const closeSidebar = () => setSidebarOpen(false)
  const visibleNavigation = useMemo(() => navigation.filter((item) => item.permission && hasPermission(item.permission)), [hasPermission])
  const visibleSettings = useMemo(() => settingsNavigation.filter((item) => item.adminOnly ? isAdmin : Boolean(item.permission && hasPermission(item.permission))), [hasPermission, isAdmin])
  const roleLabel = user?.role === 'ADMIN' ? '관리자' : user?.role === 'USER' ? '운영자' : '보기 권한'

  useEffect(() => {
    if (!location.pathname.startsWith('/classrooms')) return
    setSearch(new URLSearchParams(location.search).get('q') || '')
  }, [location.pathname, location.search])

  const searchClassrooms = () => {
    const query = search.trim()
    if (!query) return
    navigate(`/classrooms?q=${encodeURIComponent(query)}`)
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <button className="icon-button mobile-menu" onClick={() => setSidebarOpen(true)} aria-label="메뉴 열기">
          <Menu size={20} />
        </button>
        <div className="topbar-title">통합 강의실 관제</div>
        <div className="status-legend" aria-label="PC 상태 범례">
          <span><i className="status-dot online" />온라인</span>
          <span><i className="status-dot running" />명령 수행 중</span>
          <span><i className="status-dot warning" />경고</span>
          <span><i className="status-dot offline" />오프라인</span>
        </div>
        <div className="topbar-actions">
          {hasPermission('CLASSROOM_VIEW') && <label className="global-search">
            <Search size={17} aria-hidden="true" />
            <input type="search" value={search} onChange={(event) => { const value = event.target.value; setSearch(value); if (!value.trim() && location.pathname.startsWith('/classrooms')) navigate('/classrooms', { replace: true }) }} onKeyDown={(event) => { if (event.key === 'Enter') { event.preventDefault(); searchClassrooms() } }} placeholder="PC/강의실 검색" aria-label="PC 또는 강의실 검색" />
          </label>}
          <AlertsMenu />
          <div className="profile-menu">
            <button className="profile-trigger" onClick={() => setProfileOpen((open) => !open)} aria-expanded={profileOpen}>
              <span className="profile-avatar"><ShieldCheck size={16} /></span>
              <span className="profile-copy"><strong>{user?.username}</strong><small>{roleLabel}</small></span>
              <ChevronDown size={15} />
            </button>
            {profileOpen && (
              <div className="profile-popover">
                <button onClick={() => { setProfileOpen(false); setLogoutOpen(true) }}><LogOut size={16} />로그아웃</button>
              </div>
            )}
          </div>
        </div>
      </header>

      {sidebarOpen && <button className="sidebar-backdrop" onClick={closeSidebar} aria-label="메뉴 닫기" />}
      <aside className={`sidebar${sidebarOpen ? ' open' : ''}`}>
        <div className="sidebar-user">
          <span className="profile-avatar"><ShieldCheck size={16} /></span>
          <span><strong>{user?.username}</strong><small>{roleLabel}</small></span>
          <button className="icon-button sidebar-close" onClick={closeSidebar} aria-label="메뉴 닫기"><X size={18} /></button>
        </div>
        <nav className="sidebar-nav" aria-label="주 메뉴">
          {visibleNavigation.map(({ to, label, icon: Icon }) => (
            <NavLink key={to} to={to} onClick={closeSidebar} className={({ isActive }) => isActive ? 'active' : ''}>
              <Icon size={18} /><span>{label}</span>
            </NavLink>
          ))}
          <div className="nav-divider" />
          <div className="nav-section-label">설정</div>
          {visibleSettings.map(({ to, label, icon: Icon }) => (
            <NavLink key={to} to={to} onClick={closeSidebar} className={({ isActive }) => isActive ? 'active' : ''}>
              <Icon size={18} /><span>{label}</span>
            </NavLink>
          ))}
        </nav>
        <button className="sidebar-logout" onClick={() => setLogoutOpen(true)}><LogOut size={17} />로그아웃</button>
      </aside>

      <main className="app-content"><Outlet /></main>
      <ConfirmDialog open={logoutOpen} title="로그아웃 확인" description="현재 대시보드 세션을 종료하고 로그인 화면으로 돌아갑니다." confirmLabel="로그아웃" onCancel={() => setLogoutOpen(false)} onConfirm={logout} />
    </div>
  )
}
