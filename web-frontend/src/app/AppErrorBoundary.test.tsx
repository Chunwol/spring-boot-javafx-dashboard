import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppErrorBoundary } from './AppErrorBoundary'

function BrokenScreen(): never {
  throw new Error('render failed')
}

describe('AppErrorBoundary', () => {
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('keeps a render failure from blanking the entire page', () => {
    render(
      <AppErrorBoundary>
        <BrokenScreen />
      </AppErrorBoundary>,
    )

    expect(screen.getByRole('alert')).toHaveTextContent('화면을 표시하지 못했습니다')
    expect(screen.getByRole('button', { name: '새로고침' })).toBeEnabled()
  })
})
