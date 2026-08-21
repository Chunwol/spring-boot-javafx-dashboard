export type RemoteScreenMode = 'all' | 'first' | 'second'
export type RemoteDisplayLayout = 'single' | 'horizontal' | 'vertical'

export interface RemoteViewportLayout {
  displayLayout: RemoteDisplayLayout
  displayWidth: number
  displayHeight: number
  canvasWidth: number
  canvasHeight: number
  canvasLeft: number
  canvasTop: number
}

export function detectRemoteDisplayLayout(width: number, height: number): RemoteDisplayLayout {
  if (width <= 0 || height <= 0) return 'single'
  if (width / height >= 2.6) return 'horizontal'
  if (height / width >= 2.6) return 'vertical'
  return 'single'
}

export function calculateRemoteViewport({
  framebufferWidth,
  framebufferHeight,
  viewportWidth,
  viewportHeight,
  mode,
  fit,
}: {
  framebufferWidth: number
  framebufferHeight: number
  viewportWidth: number
  viewportHeight: number
  mode: RemoteScreenMode
  fit: boolean
}): RemoteViewportLayout {
  const displayLayout = detectRemoteDisplayLayout(framebufferWidth, framebufferHeight)
  const singleScreen = mode !== 'all' && displayLayout !== 'single'
  const sourceWidth = singleScreen && displayLayout === 'horizontal'
    ? framebufferWidth / 2
    : framebufferWidth
  const sourceHeight = singleScreen && displayLayout === 'vertical'
    ? framebufferHeight / 2
    : framebufferHeight
  const sourceX = singleScreen && mode === 'second' && displayLayout === 'horizontal'
    ? sourceWidth
    : 0
  const sourceY = singleScreen && mode === 'second' && displayLayout === 'vertical'
    ? sourceHeight
    : 0
  const scale = fit
    ? Math.min(viewportWidth / sourceWidth, viewportHeight / sourceHeight)
    : 1

  return {
    displayLayout,
    displayWidth: sourceWidth * scale,
    displayHeight: sourceHeight * scale,
    canvasWidth: framebufferWidth * scale,
    canvasHeight: framebufferHeight * scale,
    canvasLeft: sourceX === 0 ? 0 : -sourceX * scale,
    canvasTop: sourceY === 0 ? 0 : -sourceY * scale,
  }
}
