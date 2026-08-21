import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../auth/AuthProvider'

const WS_URL = import.meta.env.VITE_WS_URL || '/ws'
const SOCKJS_TRANSPORTS = ['websocket', 'xhr-polling']

export function useRealtimeUpdates() {
  const { token } = useAuth()
  const queryClient = useQueryClient()

  useEffect(() => {
    if (!token) return
    let disposed = false
    let dashboardTimer: number | undefined
    let alertTimer: number | undefined
    let deactivate: (() => Promise<void>) | undefined
    void Promise.all([import('@stomp/stompjs'), import('sockjs-client')]).then(([stomp, sockjs]) => {
      if (disposed) return
      const client = new stomp.Client({
        webSocketFactory: () => new sockjs.default(WS_URL, undefined, { transports: SOCKJS_TRANSPORTS }),
        connectHeaders: { Authorization: `Bearer ${token}` },
        reconnectDelay: 5_000,
        heartbeatIncoming: 20_000,
        heartbeatOutgoing: 20_000,
        onConnect: () => {
          client.subscribe('/topic/dashboard', () => {
            window.clearTimeout(dashboardTimer)
            dashboardTimer = window.setTimeout(() => {
              void queryClient.invalidateQueries({ queryKey: ['classrooms'] })
              void queryClient.invalidateQueries({ queryKey: ['pcs'] })
              void queryClient.invalidateQueries({ queryKey: ['network'] })
            }, 250)
          })
          client.subscribe('/topic/alerts', () => {
            window.clearTimeout(alertTimer)
            alertTimer = window.setTimeout(() => {
              void queryClient.invalidateQueries({ queryKey: ['alerts'] })
            }, 250)
          })
        },
      })
      deactivate = () => client.deactivate()
      client.activate()
    })
    return () => {
      disposed = true
      window.clearTimeout(dashboardTimer)
      window.clearTimeout(alertTimer)
      if (deactivate) void deactivate()
    }
  }, [queryClient, token])
}
