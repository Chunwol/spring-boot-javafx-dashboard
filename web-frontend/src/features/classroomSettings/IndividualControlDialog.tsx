import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Power, PowerOff, RotateCw, X } from 'lucide-react'
import { ConfirmDialog } from '../../components/ui/ConfirmDialog'
import { EmptyState, PageError, PageLoading } from '../../components/ui/PageState'
import { useToast } from '../../components/ui/ToastProvider'
import { useDialogFocus } from '../../components/ui/useDialogFocus'
import { classroomSettingsApi, type IpRange } from './api'
import { ipInRange } from './model'
import { routerWolFailureMessage } from '../commands/routerWolMessage'
import type { PcStatus } from '../../types/dashboard'

type PowerCommand = 'SHUTDOWN' | 'REBOOT' | 'WOL'

export function IndividualControlDialog({ range, onClose }: { range: IpRange | null; onClose: () => void }) {
  const { showToast } = useToast()
  const queryClient = useQueryClient()
  const [pending, setPending] = useState<{ pc: PcStatus; type: PowerCommand } | null>(null)
  const pcsQuery = useQuery({
    queryKey: ['classroom-pcs', range?.groupId],
    queryFn: () => classroomSettingsApi.classroomPcs(range!.groupId),
    enabled: Boolean(range),
  })
  const pcs = useMemo(() => (pcsQuery.data || []).filter((pc) => ipInRange(pc.ipAddress, range || {})), [pcsQuery.data, range])
  const commandMutation = useMutation({
    mutationFn: ({ pc, type }: { pc: PcStatus; type: PowerCommand }) => classroomSettingsApi.commandToIp(pc.ipAddress || '', type),
    onSuccess: async (result, target) => {
      setPending(null)
      await queryClient.invalidateQueries({ queryKey: ['commands'] })
      const succeeded = (result.queued || 0) > 0
      const successMessage = target.type === 'WOL' && (result.routerSignals || 0) > 0
        ? `${target.pc.pcName}에 공유기 WOL 신호를 전송했습니다.`
        : `${target.pc.pcName}에 명령을 전송했습니다.`
      showToast(succeeded ? successMessage : routerWolFailureMessage(result) || result.message || '명령 대상이 없습니다.', succeeded ? 'success' : 'error')
    },
    onError: (error) => showToast(error instanceof Error ? error.message : '명령을 전송하지 못했습니다.', 'error'),
  })
  const dialogRef = useDialogFocus<HTMLElement>(onClose, commandMutation.isPending || Boolean(pending))
  if (!range) return null

  const rangeLabel = range.cidr || `${range.startIp || '-'} ~ ${range.endIp || '-'}`
  const label = pending?.type === 'SHUTDOWN' ? '종료' : pending?.type === 'REBOOT' ? '재시작' : '켜기'
  return <>
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !commandMutation.isPending) onClose() }}>
      <section ref={dialogRef} className="individual-control-dialog" role="dialog" aria-modal="true" aria-labelledby="individual-control-title" tabIndex={-1}>
        <header><div><h2 id="individual-control-title">IP 범위 개별 제어</h2><p>{range.groupName || `강의실 #${range.groupId}`} · {rangeLabel}</p></div><button className="icon-button" onClick={onClose} aria-label="닫기"><X size={18} /></button></header>
        {pcsQuery.isLoading ? <PageLoading /> : pcsQuery.isError ? <PageError message={pcsQuery.error instanceof Error ? pcsQuery.error.message : 'PC 목록을 불러오지 못했습니다.'} onRetry={() => void pcsQuery.refetch()} /> : !pcs.length ? <EmptyState message="이 IP 범위에 등록된 PC가 없습니다." /> : <div className="table-scroll"><table className="data-table"><thead><tr><th>PC 이름</th><th>IP 주소</th><th>상태</th><th>명령</th></tr></thead><tbody>{pcs.map((pc) => {
          const online = pc.online || pc.status?.toUpperCase() === 'ONLINE'
          return <tr key={pc.macAddress}><td><strong>{pc.pcName}</strong></td><td>{pc.ipAddress || '-'}</td><td><span className={`status-badge ${online ? 'online' : 'offline'}`}>{online ? '온라인' : '오프라인'}</span></td><td><div className="row-actions">{online ? <><button className="icon-button bordered" onClick={() => setPending({ pc, type: 'REBOOT' })} aria-label={`${pc.pcName} 재시작`} title="재시작"><RotateCw size={15} /></button><button className="icon-button danger-icon" onClick={() => setPending({ pc, type: 'SHUTDOWN' })} aria-label={`${pc.pcName} 종료`} title="종료"><PowerOff size={15} /></button></> : <button className="icon-button bordered" onClick={() => setPending({ pc, type: 'WOL' })} aria-label={`${pc.pcName} 켜기`} title="켜기"><Power size={15} /></button>}</div></td></tr>
        })}</tbody></table></div>}
      </section>
    </div>
    <ConfirmDialog open={Boolean(pending)} title={`${label} 명령 확인`} description={`${pending?.pc.pcName || ''} (${pending?.pc.ipAddress || '-'})에 ${label} 명령을 전송합니다.`} confirmLabel={label} danger={pending?.type !== 'WOL'} busy={commandMutation.isPending} onCancel={() => setPending(null)} onConfirm={() => pending && commandMutation.mutate(pending)} />
  </>
}
