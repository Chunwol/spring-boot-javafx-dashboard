import { describe, expect, it } from 'vitest'
import { remoteControlWebSocketUrl } from './api'

describe('remote control API helpers', () => {
  it('uses a secure same-origin websocket URL in production', () => {
    expect(remoteControlWebSocketUrl('/vnc-relay/session?ticket=abc', {
      href: 'https://pelmon.kro.kr/reports',
      protocol: 'https:',
    })).toBe('wss://pelmon.kro.kr/vnc-relay/session?ticket=abc')
  })

  it('uses the Vite websocket proxy during local development', () => {
    expect(remoteControlWebSocketUrl('/vnc-relay/session?ticket=abc', {
      href: 'http://localhost:5173/pcs',
      protocol: 'http:',
    })).toBe('ws://localhost:5173/vnc-relay/session?ticket=abc')
  })
})
