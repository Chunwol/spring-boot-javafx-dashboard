import { describe, expect, it } from 'vitest'
import { calculateRemoteViewport, detectRemoteDisplayLayout } from './viewport'

describe('remote viewport layout', () => {
  it('detects a horizontal dual-monitor framebuffer', () => {
    expect(detectRemoteDisplayLayout(3840, 1080)).toBe('horizontal')
    expect(detectRemoteDisplayLayout(1920, 1080)).toBe('single')
  })

  it('fits the complete dual framebuffer without overflow', () => {
    expect(calculateRemoteViewport({
      framebufferWidth: 3840,
      framebufferHeight: 1080,
      viewportWidth: 1600,
      viewportHeight: 900,
      mode: 'all',
      fit: true,
    })).toMatchObject({
      displayWidth: 1600,
      displayHeight: 450,
      canvasWidth: 1600,
      canvasHeight: 450,
      canvasLeft: 0,
      canvasTop: 0,
    })
  })

  it('crops and fits the second monitor independently', () => {
    expect(calculateRemoteViewport({
      framebufferWidth: 3840,
      framebufferHeight: 1080,
      viewportWidth: 1600,
      viewportHeight: 900,
      mode: 'second',
      fit: true,
    })).toMatchObject({
      displayLayout: 'horizontal',
      displayWidth: 1600,
      displayHeight: 900,
      canvasWidth: 3200,
      canvasHeight: 900,
      canvasLeft: -1600,
      canvasTop: 0,
    })
  })

  it('keeps actual pixels when fit is disabled', () => {
    expect(calculateRemoteViewport({
      framebufferWidth: 3840,
      framebufferHeight: 1080,
      viewportWidth: 1600,
      viewportHeight: 900,
      mode: 'second',
      fit: false,
    })).toMatchObject({
      displayWidth: 1920,
      displayHeight: 1080,
      canvasWidth: 3840,
      canvasHeight: 1080,
      canvasLeft: -1920,
    })
  })
})
