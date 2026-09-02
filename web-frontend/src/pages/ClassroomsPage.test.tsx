import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ClassroomsPage } from './ClassroomsPage'

const mocks = vi.hoisted(() => ({
  getClassroomDashboard: vi.fn(),
  sendRoomCommand: vi.fn(),
  sendPcCommands: vi.fn(),
  showToast: vi.fn(),
}))

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ hasPermission: () => true }),
}))

vi.mock('../components/ui/ToastProvider', () => ({
  useToast: () => ({ showToast: mocks.showToast }),
}))

vi.mock('../features/classrooms/api', () => ({
  getClassroomDashboard: mocks.getClassroomDashboard,
  sendRoomCommand: mocks.sendRoomCommand,
}))

vi.mock('../features/pcs/api', () => ({
  sendPcCommands: mocks.sendPcCommands,
}))

vi.mock('../features/remoteControl/ClassroomScreenWall', () => ({
  ClassroomScreenWall: () => null,
}))

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/classrooms']}>
        <Routes>
          <Route path="/classrooms" element={<ClassroomsPage />} />
          <Route path="/classrooms/:groupId" element={<ClassroomsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ClassroomsPage', () => {
  afterEach(cleanup)

  beforeEach(() => {
    mocks.getClassroomDashboard.mockReset().mockResolvedValue({
      fetchedAt: '2026-08-19T15:20:00+09:00',
      rooms: [{
        groupId: 102,
        groupName: '102호',
        onlinePcs: 2,
        totalPcs: 3,
        teacherPcStatus: 'ONLINE',
        runningCommandCount: 0,
      }],
      pcs: [
        { groupId: 102, macAddress: '00:00:00:00:00:01', pcName: '102-01', online: true, status: 'ONLINE', role: 'STUDENT' },
        { groupId: 102, macAddress: '00:00:00:00:00:02', pcName: '102-02', online: false, status: 'OFFLINE', role: 'STUDENT' },
        { groupId: 102, macAddress: 'BROKEN-3', pcName: '미사용 PC 3', online: false, status: 'BROKEN', role: 'STUDENT' },
      ],
    })
    mocks.sendRoomCommand.mockReset().mockResolvedValue({ requested: 3, queued: 1, offlineSkipped: 1 })
    mocks.sendPcCommands.mockReset().mockResolvedValue({ requested: 1, queued: 1, duplicateSkipped: 0, failed: 0, messages: [] })
    mocks.showToast.mockReset()
  })

  it('uses the same command-eligible online count in the summary and shutdown confirmation', async () => {
    const user = userEvent.setup()
    renderPage()

    expect(await screen.findByText(/온라인 PC 1대/)).toBeInTheDocument()
    expect(screen.getByText(/갱신/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '102호 상세 보기' }))
    await user.click(await screen.findByRole('button', { name: '전체 종료' }))

    const dialog = await screen.findByRole('alertdialog')
    expect(within(dialog).getByText(/온라인 PC 1대를 종료합니다/)).toBeInTheDocument()
  })

  it('sends a shutdown only to the online teacher PC from classroom list view', async () => {
    const user = userEvent.setup()
    const teacher = { groupId: 102, macAddress: '00:00:00:00:00:10', pcName: '102_T', online: true, status: 'ONLINE', role: 'TEACHER' }
    mocks.getClassroomDashboard.mockResolvedValue({
      fetchedAt: '2026-08-19T15:20:00+09:00',
      rooms: [{ groupId: 102, groupName: '102호', onlinePcs: 1, totalPcs: 1, teacherPcStatus: 'ONLINE', runningCommandCount: 0 }],
      pcs: [teacher],
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: '목록 보기' }))
    await user.click(screen.getByRole('button', { name: '102_T 끄기' }))

    const dialog = await screen.findByRole('alertdialog')
    expect(within(dialog).getByText(/102_T 한 대에 종료 명령/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: '끄기' }))

    await waitFor(() => expect(mocks.sendPcCommands).toHaveBeenCalledWith([teacher], 'SHUTDOWN'))
    expect(mocks.sendRoomCommand).not.toHaveBeenCalled()
  })

  it('sends a wake signal only to the offline teacher PC from classroom list view', async () => {
    const user = userEvent.setup()
    const teacher = { groupId: 102, macAddress: '00:00:00:00:00:10', pcName: '102_T', online: false, status: 'OFFLINE', role: 'TEACHER' }
    mocks.getClassroomDashboard.mockResolvedValue({
      fetchedAt: '2026-08-19T15:20:00+09:00',
      rooms: [{ groupId: 102, groupName: '102호', onlinePcs: 0, totalPcs: 1, teacherPcStatus: 'OFFLINE', runningCommandCount: 0 }],
      pcs: [teacher],
    })
    renderPage()

    await user.click(await screen.findByRole('button', { name: '목록 보기' }))
    await user.click(screen.getByRole('button', { name: '102_T 켜기' }))
    const dialog = await screen.findByRole('alertdialog')
    await user.click(within(dialog).getByRole('button', { name: '켜기' }))

    await waitFor(() => expect(mocks.sendPcCommands).toHaveBeenCalledWith([teacher], 'PCON'))
    expect(mocks.sendRoomCommand).not.toHaveBeenCalled()
  })
})
