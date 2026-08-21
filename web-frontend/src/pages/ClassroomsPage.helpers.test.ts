import { describe, expect, it } from 'vitest'
import { commandDeliveryMessage } from './ClassroomsPage'

describe('classroom command result message', () => {
  it('separates WOL target count from router signal count', () => {
    expect(commandDeliveryMessage('102호', 'WOL', {
      queued: 1,
      acceptedTargets: 40,
      routerSignals: 1,
      failedTargets: 0,
    })).toEqual({
      message: '102호 전체 켜기 40대 요청 (신호 대상 40대 / 공유기 1건)',
      isError: false,
    })
  })

  it('shows requested, queued and skipped shutdown counts together', () => {
    expect(commandDeliveryMessage('102호', 'SHUTDOWN', {
      requested: 41,
      queued: 39,
      offlineSkipped: 1,
      duplicateSkipped: 1,
    })).toEqual({
      message: '102호 전체 종료 명령 41대 요청 (명령 39건 등록 / 중복 제외 1대 / 오프라인 제외 1대)',
      isError: false,
    })
  })
})
