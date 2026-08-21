interface RouterWolResponse {
  routerMessage?: string
  routerAttempts?: number
}

export function routerWolFailureMessage(result: RouterWolResponse) {
  switch (result.routerMessage) {
    case 'router-wol-no-valid-mac':
      return 'WOL 대상의 MAC 주소가 올바르지 않습니다.'
    case 'router-wol-credentials-not-configured':
    case 'router-wol-auth-failed':
      return '공유기 WOL 인증에 실패했습니다. 서버 설정을 확인해 주세요.'
    case 'router-wol-gateway-unreachable':
    case 'router-wol-landing-failed':
    case 'router-wol-signal-failed':
    case 'router-wol-interrupted':
      return '같은 대역의 공유기 WOL 서버에 연결할 수 없습니다.'
    case 'router-wol-disabled':
    case 'router-wol-route-not-configured':
    case 'router-wol-gateway-not-configured':
      return '같은 대역에 온라인 릴레이 에이전트 또는 사용 가능한 공유기가 없습니다.'
    default:
      return (result.routerAttempts || 0) > 0
        ? '공유기 WOL 요청을 완료하지 못했습니다.'
        : null
  }
}
