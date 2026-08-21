import { lazy, Suspense } from 'react'
import { createBrowserRouter, Navigate } from 'react-router-dom'
import { RequireAuth } from '../auth/RequireAuth'
import { RequireRole } from '../auth/RequireRole'
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
const UsersPage = lazy(() => import('../pages/UsersPage').then((module) => ({ default: module.UsersPage })))
const ClassroomSettingsPage = lazy(() => import('../pages/ClassroomSettingsPage').then((module) => ({ default: module.ClassroomSettingsPage })))
const SystemSettingsPage = lazy(() => import('../pages/SystemSettingsPage').then((module) => ({ default: module.SystemSettingsPage })))

const screen = (element: React.ReactNode) => <Suspense fallback={<section className="content-section"><PageLoading /></section>}>{element}</Suspense>
const adminScreen = (element: React.ReactNode) => <RequireRole roles={['ADMIN']}>{screen(element)}</RequireRole>

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage />, errorElement: <RouteErrorBoundary /> },
  {
    element: <RequireAuth />,
    errorElement: <RouteErrorBoundary />,
    children: [{
      element: <AppShell />,
      children: [
        { index: true, element: <Navigate to="/classrooms" replace /> },
        { path: '/classrooms', element: screen(<ClassroomsPage />) },
        { path: '/classrooms/:groupId', element: screen(<ClassroomsPage />) },
        { path: '/pcs', element: screen(<PcsPage />) },
        { path: '/pc', element: <Navigate to="/pcs" replace /> },
        { path: '/pc/monitor', element: <Navigate to="/pcs" replace /> },
        { path: '/commands', element: adminScreen(<CommandsPage />) },
        { path: '/pc/commands', element: <Navigate to="/commands" replace /> },
        { path: '/tokens', element: adminScreen(<TokensPage />) },
        { path: '/pc/tokens', element: <Navigate to="/tokens" replace /> },
        { path: '/broken', element: screen(<BrokenPcsPage />) },
        { path: '/pc/broken', element: <Navigate to="/broken" replace /> },
        { path: '/network', element: screen(<NetworkPage />) },
        { path: '/network/:groupId', element: screen(<NetworkPage />) },
        { path: '/reports', element: screen(<ReportsPage />) },
        { path: '/files', element: adminScreen(<FilesPage />) },
        { path: '/settings', element: <Navigate to="/settings/classrooms" replace /> },
        { path: '/settings/classrooms', element: screen(<ClassroomSettingsPage />) },
        { path: '/settings/ip-ranges', element: <Navigate to="/settings/classrooms?tab=ranges" replace /> },
        { path: '/settings/teacher-pcs', element: <Navigate to="/settings/classrooms?tab=teachers" replace /> },
        { path: '/settings/users', element: adminScreen(<UsersPage />) },
        { path: '/settings/system', element: screen(<SystemSettingsPage />) },
        { path: '/forbidden', element: <ForbiddenPage /> },
      ],
    }],
  },
  { path: '*', element: <NotFoundPage />, errorElement: <RouteErrorBoundary /> },
])
