import { describe, expect, it } from 'vitest'
import { ipInRange, isValidCidr, isValidIpv4 } from './model'

describe('classroom settings model', () => {
  it('validates strict IPv4 and CIDR input', () => {
    expect(isValidIpv4('192.168.10.25')).toBe(true)
    expect(isValidIpv4('192.168.010.25')).toBe(false)
    expect(isValidIpv4('300.1.1.1')).toBe(false)
    expect(isValidCidr('192.168.10.0/24')).toBe(true)
    expect(isValidCidr('192.168.10.0/33')).toBe(false)
  })

  it('matches addresses in CIDR and explicit ranges', () => {
    expect(ipInRange('192.168.10.25', { cidr: '192.168.10.0/24' })).toBe(true)
    expect(ipInRange('192.168.11.25', { cidr: '192.168.10.0/24' })).toBe(false)
    expect(ipInRange('10.0.0.5', { startIp: '10.0.0.3', endIp: '10.0.0.8' })).toBe(true)
  })
})
