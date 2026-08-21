import type { IpRange } from './api'

export function ipv4Number(value: string | null | undefined) {
  if (!value) return null
  const parts = value.trim().split('.')
  if (parts.length !== 4) return null
  const numbers = parts.map(Number)
  if (numbers.some((part, index) => !Number.isInteger(part) || part < 0 || part > 255 || String(part) !== parts[index])) return null
  return (((numbers[0] << 24) >>> 0) + (numbers[1] << 16) + (numbers[2] << 8) + numbers[3]) >>> 0
}

export function isValidIpv4(value: string) {
  return ipv4Number(value) !== null
}

export function isValidCidr(value: string) {
  const [address, prefixText, ...extra] = value.trim().split('/')
  const prefix = Number(prefixText)
  return extra.length === 0 && ipv4Number(address) !== null && /^\d+$/.test(prefixText || '') && prefix >= 0 && prefix <= 32
}

export function ipInRange(ipAddress: string | null | undefined, range: Pick<IpRange, 'cidr' | 'startIp' | 'endIp'>) {
  const ip = ipv4Number(ipAddress)
  if (ip == null) return false
  if (range.cidr) {
    if (!isValidCidr(range.cidr)) return false
    const [address, prefixText] = range.cidr.split('/')
    const network = ipv4Number(address) as number
    const prefix = Number(prefixText)
    const mask = prefix === 0 ? 0 : (0xffffffff << (32 - prefix)) >>> 0
    return (ip & mask) === (network & mask)
  }
  const start = ipv4Number(range.startIp)
  const end = ipv4Number(range.endIp)
  return start != null && end != null && start <= ip && ip <= end
}
