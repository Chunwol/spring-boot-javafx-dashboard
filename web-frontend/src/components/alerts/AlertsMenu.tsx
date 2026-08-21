import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bell, CheckCheck } from 'lucide-react'
import { useAuth } from '../../auth/AuthProvider'
import { useToast } from '../ui/ToastProvider'
import { alertsApi } from '../../features/alerts/api'
import { settingsApi } from '../../features/settings/api'
import { parseHiddenAlertTypes } from '../../features/settings/alertFilter'

function severityLabel(severity: string) {
  if (severity.toUpperCase() === 'CRITICAL') return '심각'
  if (['WARN', 'WARNING'].includes(severity.toUpperCase())) return '경고'
  return '정보'
}

export function AlertsMenu() {
  const { canWrite } = useAuth()
  const { showToast } = useToast()
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const alertsQuery = useQuery({ queryKey: ['alerts', 'list'], queryFn: alertsApi.list, refetchInterval: 30_000 })
  const filterQuery = useQuery({ queryKey: ['setting', 'alert-type-filter'], queryFn: () => settingsApi.get('alert-type-filter') })
  const hidden = useMemo(() => parseHiddenAlertTypes(filterQuery.data?.value || ''), [filterQuery.data])
  const visibleAlerts = useMemo(() => (alertsQuery.data || []).filter((alert) => (
    alert.alertType !== 'METRICS_ONLINE_RATIO_DROP' && !hidden.has(alert.alertType)
  )), [alertsQuery.data, hidden])
  const visibleUnreadIds = useMemo(() => visibleAlerts.filter((alert) => !alert.acknowledged).map((alert) => alert.id), [visibleAlerts])
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['alerts'] })
  const acknowledgeMutation = useMutation({ mutationFn: alertsApi.acknowledge, onSuccess: () => void invalidate(), onError: (error) => showToast(error instanceof Error ? error.message : '알림을 확인 처리하지 못했습니다.', 'error') })
  const allMutation = useMutation({ mutationFn: alertsApi.acknowledgeSelected, onSuccess: async (result) => { await invalidate(); showToast(`표시된 알림 ${result.acknowledged}건을 확인 처리했습니다.`) }, onError: (error) => showToast(error instanceof Error ? error.message : '알림을 확인 처리하지 못했습니다.', 'error') })
  const unread = visibleUnreadIds.length

  return <div className="alerts-menu">
    <button className="icon-button alerts-trigger" onClick={() => setOpen((value) => !value)} aria-label={`알림${unread ? ` ${unread}건` : ''}`} aria-expanded={open}><Bell size={18} />{unread > 0 && <span className="alert-count">{unread > 99 ? '99+' : unread}</span>}</button>
    {open && <div className="alerts-popover"><header><div><strong>운영 알림</strong><small>{unread}건 미확인</small></div>{canWrite && unread > 0 && <button className="button secondary compact" onClick={() => allMutation.mutate(visibleUnreadIds)} disabled={allMutation.isPending}><CheckCheck size={14} />표시 알림 확인</button>}</header><div className="alerts-list">{alertsQuery.isLoading ? <p className="alerts-empty">알림을 불러오는 중입니다.</p> : alertsQuery.isError && !alertsQuery.data ? <p className="alerts-empty error">알림을 불러오지 못했습니다.</p> : !visibleAlerts.length ? <p className="alerts-empty">표시할 알림이 없습니다.</p> : visibleAlerts.map((alert) => <button key={alert.id} className={`alert-row${alert.acknowledged ? ' acknowledged' : ''}`} onClick={() => canWrite && !alert.acknowledged && acknowledgeMutation.mutate(alert.id)} disabled={!canWrite || alert.acknowledged}><span className={`alert-severity ${alert.severity.toLowerCase()}`}>{severityLabel(alert.severity)}</span><span><strong>{alert.message}</strong><small>{new Date(alert.createdAt).toLocaleString('ko-KR')}{alert.sourceMac ? ` · ${alert.sourceMac}` : ''}</small></span></button>)}</div></div>}
  </div>
}
