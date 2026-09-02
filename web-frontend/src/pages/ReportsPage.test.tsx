import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ReportsPage } from './ReportsPage'

const mocks = vi.hoisted(() => ({
  isAdmin: false,
  regenerateMonthlyReport: vi.fn(),
  showToast: vi.fn(),
}))

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ hasPermission: () => mocks.isAdmin }),
}))

vi.mock('../components/ui/ToastProvider', () => ({
  useToast: () => ({ showToast: mocks.showToast }),
}))

vi.mock('../features/classrooms/api', () => ({
  getClassrooms: vi.fn().mockResolvedValue([]),
}))

vi.mock('../features/reports/api', () => ({
  getMonthlyReports: vi.fn().mockResolvedValue([{
    yearMonth: '2026-08',
    totalPcs: 850,
    avgOnlinePcs: 800,
    peakOnlinePcs: 820,
    onlineRatio: 94.1,
    totalCommands: 10,
    failedCommands: 1,
    totalAlerts: 2,
    totalTrafficGb: 12,
  }]),
  getTrafficReport: vi.fn().mockResolvedValue({}),
  getUptimeReport: vi.fn().mockResolvedValue({}),
  getPerformanceStatus: vi.fn().mockResolvedValue({ pc: { total: 850, online: 800, onlineRatio: 94.1 } }),
  regenerateMonthlyReport: mocks.regenerateMonthlyReport,
  downloadReportPdf: vi.fn(),
}))

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}><ReportsPage /></QueryClientProvider>)
}

describe('ReportsPage', () => {
  afterEach(cleanup)

  beforeEach(() => {
    mocks.isAdmin = false
    mocks.regenerateMonthlyReport.mockReset()
    mocks.showToast.mockReset()
  })

  it('shows performance status but hides monthly regeneration from non-admin users', async () => {
    renderPage()

    expect(await screen.findByText('실시간 운영 성능')).toBeInTheDocument()
    expect(await screen.findByText('2026년 8월')).toBeInTheDocument()
    expect(screen.queryByText('재생성 대상 월')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '재생성' })).not.toBeInTheDocument()
  })
})
