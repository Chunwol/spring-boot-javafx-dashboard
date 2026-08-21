import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as networkApi from '../features/network/api'
import { NetworkPage } from './NetworkPage'

vi.mock('../features/network/api')
vi.mock('recharts', () => ({
  Area: () => null,
  AreaChart: ({ children }: { children: React.ReactNode }) => <svg data-testid="traffic-chart">{children}</svg>,
  CartesianGrid: () => null,
  Legend: () => null,
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Tooltip: () => null,
  XAxis: () => null,
  YAxis: () => null,
}))

const classrooms = [
  { groupId: 1, groupName: '217호', onlinePcs: 35, totalPcs: 35, runningCommandCount: 0 },
  { groupId: 2, groupName: '218호', onlinePcs: 40, totalPcs: 41, runningCommandCount: 0 },
]

const trafficByClassroom = {
  1: [{
    pcName: '217-01',
    ipAddress: '10.0.1.1',
    macAddress: '00:00:00:00:00:01',
    status: 'ONLINE',
    inboundMbps: 10,
    outboundMbps: 500,
    updatedAt: '2026-08-07T10:00:00',
  }],
  2: [{
    pcName: '218-01',
    ipAddress: '10.0.2.1',
    macAddress: '00:00:00:00:00:02',
    status: 'ONLINE',
    inboundMbps: 90,
    outboundMbps: 1,
    updatedAt: '2026-08-07T10:00:00',
  }],
}

function setup(initialPath = '/network/1') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  })
  const router = createMemoryRouter([
    { path: '/network', element: <NetworkPage /> },
    { path: '/network/:groupId', element: <NetworkPage /> },
  ], { initialEntries: [initialPath] })
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

describe('NetworkPage', () => {
  beforeEach(() => {
    vi.mocked(networkApi.getNetworkTraffic).mockResolvedValue({ inboundMbps: 10, outboundMbps: 1_000 })
    vi.mocked(networkApi.getTrafficThresholds).mockResolvedValue({ globalMbps: 100, classroomMbps: 100, pcMbps: 100 })
    vi.mocked(networkApi.getNetworkClassrooms).mockResolvedValue(classrooms)
    vi.mocked(networkApi.getClassroomTrafficSummaries).mockResolvedValue([
      { groupId: 2, groupName: '218호', onlinePcs: 40, totalPcs: 41, inboundMbps: 90, outboundMbps: 1, updatedAt: '2026-08-07T10:00:00' },
      { groupId: 1, groupName: '217호', onlinePcs: 35, totalPcs: 35, inboundMbps: 10, outboundMbps: 500, updatedAt: '2026-08-07T10:00:00' },
    ])
    vi.mocked(networkApi.getClassroomPcTraffic).mockImplementation(async (groupId) => (
      trafficByClassroom[groupId as keyof typeof trafficByClassroom]
    ))
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('judges global, classroom, and PC status using inbound traffic only', async () => {
    setup()

    const globalSummary = await screen.findByLabelText('전체 네트워크 요약')
    expect(within(globalSummary).getByText('정상')).toBeInTheDocument()

    const classroomTable = await screen.findByRole('table', { name: '강의실별 네트워크 부하' })
    const roomRow = within(classroomTable).getByText('217호').closest('tr')
    expect(roomRow).not.toBeNull()
    expect(within(roomRow as HTMLTableRowElement).getByText('정상')).toBeInTheDocument()
    expect(within(roomRow as HTMLTableRowElement).getByText('10.0%')).toBeInTheDocument()

    expect(screen.getByText('인 10.00 Mbps · 아웃 500.00 Mbps')).toBeInTheDocument()

    const pcTable = await screen.findByRole('table', { name: '선택 강의실 PC별 네트워크 부하' })
    const pcRow = within(pcTable).getByText('217-01').closest('tr')
    expect(pcRow).not.toBeNull()
    expect(within(pcRow as HTMLTableRowElement).getByText('10.0%')).toBeInTheDocument()
  })

  it('keeps the selected classroom synchronized with browser navigation', async () => {
    const router = setup('/network/1')

    await screen.findByRole('table', { name: '강의실별 네트워크 부하' })
    await act(async () => { await router.navigate('/network/2') })
    await waitFor(() => expect(router.state.location.pathname).toBe('/network/2'))
    expect(screen.getByRole('combobox')).toHaveValue('2')

    await act(async () => { await router.navigate(-1) })
    await waitFor(() => expect(router.state.location.pathname).toBe('/network/1'))
    await waitFor(() => expect(screen.getByRole('combobox')).toHaveValue('1'))
  })

  it('shows a partial failure instead of displaying a failed traffic request as zero', async () => {
    vi.mocked(networkApi.getNetworkTraffic).mockRejectedValue(new Error('traffic unavailable'))
    setup()

    expect(await screen.findByText('일부 정보를 갱신하지 못했습니다.')).toBeInTheDocument()
    expect(screen.getAllByText('조회 실패').length).toBeGreaterThan(0)
    expect(screen.queryByText('0.00 Mbps')).not.toBeInTheDocument()
  })
})
