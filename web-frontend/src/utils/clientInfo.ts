export function clientEnvironmentLabel(userAgent?: string | null) {
  if (!userAgent?.trim()) return '-'
  const value = userAgent.trim()
  const browser = value.includes('Edg/') ? 'Edge'
    : value.includes('Chrome/') ? 'Chrome'
      : value.includes('Firefox/') ? 'Firefox'
        : value.includes('Safari/') ? 'Safari'
          : '기타 브라우저'
  const os = value.includes('Windows') ? 'Windows'
    : value.includes('Android') ? 'Android'
      : value.includes('iPhone') || value.includes('iPad') ? 'iOS'
        : value.includes('Mac OS') ? 'macOS'
          : value.includes('Linux') ? 'Linux'
            : ''
  return os ? `${browser} · ${os}` : browser
}
