import { lazy, Suspense } from 'react'
import { createBrowserRouter, Navigate } from 'react-router-dom'
import { RequireAuth } from '../auth/RequireAuth'
import { RequireRole } from '../auth/RequireRole'
import { RequirePermission } from '../auth/RequirePermission'
import { useAuth } from '../auth/AuthProvider'
import type { FeaturePermission } from '../auth/permissions'
import { AppShell } from '../layout/AppShell'
import { LoginPage } from '../pages/LoginPage'
import { NotFoundPage } from '../pages/NotFoundPage'
import { ForbiddenPage } from '../pages/ForbiddenPage'
import { PageLoading } from '../components/ui/PageState'
import { RouteErrorBoundary } from './RouteErrorBoundary'

const ClassroomsPage = lazy(() => import('../pages/ClassroomsPage').then((module) => ({ default: module.ClassroomsPage })))
const PcsPage = lazy(() => import('../pages/PcsPage').then((module) => ({ default: module.PcsPage })))
const CommandsPage = lazy(() => import('../pages/CommandsPage').then((module) => ({ default: module.CommandsPage })))
const TokensPage = lazy(() => import('../pages/TokensPage').then((module) => ({ default: module.TokensPage })))
const BrokenPcsPage = lazy(() => import('../pages/BrokenPcsPage').then((module) => ({ default: module.BrokenPcsPage })))
const NetworkPage = lazy(() => import('../pages/NetworkPage').then((module) => ({ default: module.NetworkPage })))
const ReportsPage = lazy(() => import('../pages/ReportsPage').then((module) => ({ default: module.ReportsPage })))
const FilesPage = lazy(() => import('../pages/FilesPage').then((module) => ({ default: module.FilesPage })))
const ScriptLibraryPage = lazy(() => import('../pages/ScriptLibraryPage').then((module) => ({ default: module.ScriptLibraryPage })))
const UsersPage = lazy(() => import('../pages/UsersPage').then((module) => ({ default: module.UsersPage })))
const ClassroomSettingsPage = lazy(() => import('../pages/ClassroomSettingsPage').then((module) => ({ default: module.ClassroomSettingsPage })))
const SystemSettingsPage = lazy(() => import('../pages/SystemSettingsPage').then((module) => ({ default: module.SystemSettingsPage })))

const screen = (element: React.ReactNode) => <Suspense fallback={<section className="content-section"><PageLoading /></section>}>{element}</Suspense>
const adminScreen = (element: React.ReactNode) => <RequireRole roles={['ADMIN']}>{screen(element)}</RequireRole>
const permissionScreen = (permission: FeaturePermission, element: React.ReactNode) => <RequirePermission permission={permission}>{screen(element)}</RequirePermission>

function HomeRedirect() {
  const { hasPermission } = useAuth()
  const first = [
    ['CLASSROOMS', '/classrooms'], ['PCS', '/pcs'], ['COMMANDS', '/commands'],
    ['SCRIPT_LIBRARY', '/script-library'], ['TOKENS', '/tokens'], ['BROKEN_PCS', '/broken'],
    ['NETWORK', '/network'], ['REPORTS', '/reports'], ['FILES', '/files'],
    ['CLASSROOM_SETTINGS', '/settings/classrooms'], ['SYSTEM_SETTINGS', '/settings/system'],
  ] as const
  const destination = first.find(([permission]) => hasPermission(permission))?.[1] || '/forbidden'
  return <Navigate to={destination} replace />
}

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage />, errorElement: <RouteErrorBoundary /> },
  {
    element: <RequireAuth />,
    errorElement: <RouteErrorBoundary />,
    children: [{
      element: <AppShell />,
      children: [
        { index: true, element: <HomeRedirect /> },
        { path: '/classrooms', element: permissionScreen('CLASSROOMS', <ClassroomsPage />) },
        { path: '/classrooms/:groupId', element: permissionScreen('CLASSROOMS', <ClassroomsPage />) },
        { path: '/pcs', element: permissionScreen('PCS', <PcsPage />) },
        { path: '/pc', element: <Navigate to="/pcs" replace /> },
        { path: '/pc/monitor', element: <Navigate to="/pcs" replace /> },
        { path: '/commands', element: permissionScreen('COMMANDS', <CommandsPage />) },
        { path: '/pc/commands', element: <Navigate to="/commands" replace /> },
        { path: '/tokens', element: permissionScreen('TOKENS', <TokensPage />) },
        { path: '/pc/tokens', element: <Navigate to="/tokens" replace /> },
        { path: '/broken', element: permissionScreen('BROKEN_PCS', <BrokenPcsPage />) },
        { path: '/pc/broken', element: <Navigate to="/broken" replace /> },
        { path: '/network', element: permissionScreen('NETWORK', <NetworkPage />) },
        { path: '/network/:groupId', element: permissionScreen('NETWORK', <NetworkPage />) },
        { path: '/reports', element: permissionScreen('REPORTS', <ReportsPage />) },
        { path: '/files', element: permissionScreen('FILES', <FilesPage />) },
        { path: '/script-library', element: permissionScreen('SCRIPT_LIBRARY', <ScriptLibraryPage />) },
        { path: '/settings', element: <Navigate to="/settings/classrooms" replace /> },
        { path: '/settings/classrooms', element: permissionScreen('CLASSROOM_SETTINGS', <ClassroomSettingsPage />) },
        { path: '/settings/ip-ranges', element: <Navigate to="/settings/classrooms?tab=ranges" replace /> },
        { path: '/settings/teacher-pcs', element: <Navigate to="/settings/classrooms?tab=teachers" replace /> },
        { path: '/settings/users', element: adminScreen(<UsersPage />) },
        { path: '/settings/system', element: permissionScreen('SYSTEM_SETTINGS', <SystemSettingsPage />) },
        { path: '/forbidden', element: <ForbiddenPage /> },
      ],
    }],
  },
  { path: '*', element: <NotFoundPage />, errorElement: <RouteErrorBoundary /> },
])
