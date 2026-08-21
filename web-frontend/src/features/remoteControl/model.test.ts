import { describe, expect, it } from 'vitest'
import { isRemoteControlTerminal, remoteControlStatusLabel, remoteControlWebSocketUrl } from './model'

describe('remote control model', () => {
  it('uses a secure same-origin WebSocket URL and preserves the ticket', () => {
    expect(remoteControlWebSocketUrl('/vnc-relay/session?ticket=once', {
      origin: 'https://dashboard.example',
      protocol: 'https:',
    })).toBe('wss://dashboard.example/vnc-relay/session?ticket=once')
  })

  it('does not rewrite an absolute WebSocket URL', () => {
    expect(remoteControlWebSocketUrl('wss://relay.example/ws?ticket=once', {
      origin: 'http://localhost:5173',
      protocol: 'http:',
    })).toBe('wss://relay.example/ws?ticket=once')
  })

  it('labels lifecycle states and identifies terminal states', () => {
    expect(remoteControlStatusLabel('PENDING')).toBe('에이전트 준비 대기')
    expect(isRemoteControlTerminal('READY')).toBe(false)
    expect(isRemoteControlTerminal('FAILED')).toBe(true)
  })
})
