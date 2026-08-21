import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ConfirmDialog } from './ConfirmDialog'

function DialogHarness({ onCancel = vi.fn() }: { onCancel?: () => void }) {
  const [open, setOpen] = useState(false)

  return (
    <>
      <button onClick={() => setOpen(true)}>삭제 열기</button>
      <ConfirmDialog
        open={open}
        title="워크스테이션 삭제"
        description="삭제할까요?"
        confirmLabel="삭제"
        onConfirm={vi.fn()}
        onCancel={() => {
          onCancel()
          setOpen(false)
        }}
      />
    </>
  )
}

describe('ConfirmDialog accessibility', () => {
  afterEach(cleanup)

  it('places initial focus on the safe cancel action', async () => {
    const user = userEvent.setup()
    render(<DialogHarness />)

    await user.click(screen.getByRole('button', { name: '삭제 열기' }))

    expect(within(screen.getByRole('alertdialog')).getByRole('button', { name: '취소' })).toHaveFocus()
  })

  it('traps Tab and Shift+Tab focus inside the dialog', async () => {
    const user = userEvent.setup()
    render(<DialogHarness />)
    await user.click(screen.getByRole('button', { name: '삭제 열기' }))

    const dialog = screen.getByRole('alertdialog')
    const close = within(dialog).getByRole('button', { name: '닫기' })
    const cancel = within(dialog).getByRole('button', { name: '취소' })
    const confirm = within(dialog).getByRole('button', { name: '삭제' })

    expect(cancel).toHaveFocus()
    await user.tab()
    expect(confirm).toHaveFocus()
    await user.tab()
    expect(close).toHaveFocus()
    await user.tab({ shift: true })
    expect(confirm).toHaveFocus()

    close.focus()
    await user.tab({ shift: true })
    expect(confirm).toHaveFocus()
  })

  it('cancels on Escape and restores focus to the trigger after closing', async () => {
    const user = userEvent.setup()
    const onCancel = vi.fn()
    render(<DialogHarness onCancel={onCancel} />)
    const trigger = screen.getByRole('button', { name: '삭제 열기' })
    await user.click(trigger)

    await user.keyboard('{Escape}')

    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })
})
