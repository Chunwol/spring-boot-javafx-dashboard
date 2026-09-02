import { useMemo, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Download, LoaderCircle, RefreshCw, RotateCw, Save, Shield, ShieldCheck, X } from 'lucide-react'
import { ConfirmDialog } from '../../components/ui/ConfirmDialog'
import { useToast } from '../../components/ui/ToastProvider'
import { useDialogFocus } from '../../components/ui/useDialogFocus'
import type { PcStatus, ScmateRecoveryPoint } from '../../types/dashboard'
import { sendScmateAction, sendScmateActions, type ScmateActionFailure } from './api'
import {
  allowedScmateActions,
  scmateActionConfirmation,
  scmateActionDescription,
  SCMATE_ACTIONS,
  SCMATE_ACTION_LABELS,
  scmateActionTargets,
  scmateAvailabilityLabel,
  scmateDisplayError,
  scmateRecoveryPointLabel,
  SCMATE_RECOVERY_POINT_LABELS,
  SCMATE_RECOVERY_POINTS,
  scmateScheduleLabel,
  scmateState,
  scmateStateSourceLabel,
  scmateWorkflowStageLabel,
  type ScmateAction,
} from './model'
import './pcs.css'

interface Props {
  pc: PcStatus
  classroomPcs?: PcStatus[]
  canAct: boolean
  canPerform?: (action: ScmateAction) => boolean
  onClose: () => void
}

function formatObservedAt(value: string | null | undefined) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('ko-KR')
}

function formatBootTime(value: number | null | undefined) {
  if (value == null || !Number.isFinite(value) || value <= 0) return '-'
  return new Date(value * 1_000).toLocaleString('ko-KR')
}

function usesRecoveryPoint(action: ScmateAction) {
  return action === 'UPDATE_BASELINE' || action === 'REQUEST_PROTECTED' || action === 'RESTORE_BASELINE'
}

function actionIcon(action: ScmateAction) {
  if (action === 'REFRESH_STATUS_DETAIL') return <RefreshCw size={16} />
  if (action === 'INSTALL_RECOVERY_MODULE') return <Download size={16} />
  if (action === 'UPDATE_BASELINE') return <Save size={16} />
  if (action === 'REBOOT_FOR_BASELINE' || action === 'REBOOT_TO_OPERATOR' || action === 'REBOOT_TO_PROTECTED' || action === 'RESTORE_BASELINE') return <RotateCw size={16} />
  return action === 'REQUEST_PROTECTED' ? <ShieldCheck size={16} /> : <Shield size={16} />
}

function classroomActionDescription(
  groupName: string,
  action: ScmateAction,
  targets: number,
  excluded: number,
  recoveryPoint: ScmateRecoveryPoint,
) {
  const excludedText = excluded > 0 ? ` 상태가 맞지 않거나 교사 PC인 ${excluded}대는 제외됩니다.` : ''
  const point = SCMATE_RECOVERY_POINT_LABELS[recoveryPoint]
  const pointDestination = recoveryPoint === 'BASELINE' ? `${point}으로` : `${point}로`
  if (action === 'REQUEST_OPERATOR') return `${groupName}의 실행 가능한 학생 PC ${targets}대를 운영자 모드로 전환합니다.${excludedText}`
  if (action === 'REBOOT_FOR_BASELINE' || action === 'REBOOT_TO_OPERATOR') return `${groupName}의 학생 PC ${targets}대를 운영자 모드로 재부팅합니다. 저장하지 않은 작업이 사라질 수 있습니다.${excludedText}`
  if (action === 'UPDATE_BASELINE') return `${groupName}의 학생 PC ${targets}대에서 현재 상태를 ${pointDestination} 저장합니다. 완료될 때까지 전원을 끄지 마세요.${excludedText}`
  if (action === 'REQUEST_PROTECTED') return `${groupName}의 학생 PC ${targets}대를 ${point} 기준 매 부팅 복구 모드로 전환합니다.${excludedText}`
  if (action === 'INSTALL_RECOVERY_MODULE') return `${groupName}의 복구모듈 설치안됨 학생 PC ${targets}대에 복구모듈을 설치합니다. 자동 재부팅은 하지 않습니다.${excludedText}`
  if (action === 'RESTORE_BASELINE') return `${groupName}의 학생 PC ${targets}대를 ${pointDestination} 즉시 복구하며 대상 PC가 재부팅됩니다. 저장하지 않은 모든 변경 사항은 삭제됩니다.${excludedText}`
  if (action === 'REFRESH_STATUS_DETAIL') return `${groupName}의 학생 PC ${targets}대에서 SCMATE 상세 상태를 새로 조회합니다.${excludedText}`
  return `${groupName}의 학생 PC ${targets}대를 재부팅해 저장된 복구 시점으로 되돌립니다. 현재 변경 사항이 사라집니다.${excludedText}`
}

export function ScmateStatusBadge({ pc, busy = false }: { pc: PcStatus; busy?: boolean }) {
  const state = scmateState(pc)
  return (
    <span className={`scmate-status scmate-${state.tone}`}>
      {(state.tone === 'transition' || busy) && <LoaderCircle size={12} className="spin-icon" />}
      {state.label}
    </span>
  )
}

export function ScmateControlDialog({ pc, classroomPcs, canAct, canPerform, onClose }: Props) {
  const queryClient = useQueryClient()
  const { showToast } = useToast()
  const [pendingAction, setPendingAction] = useState<ScmateAction | null>(null)
  const [recoveryPoint, setRecoveryPoint] = useState<ScmateRecoveryPoint>('BASELINE')
  const [bulkErrors, setBulkErrors] = useState<ScmateActionFailure[]>([])
  const classroomMode = Boolean(classroomPcs)
  const classroomPreviews = useMemo(
    () => new Map(SCMATE_ACTIONS.map((action) => [action, scmateActionTargets(classroomPcs || [], action)])),
    [classroomPcs],
  )
  const allowedActions = useMemo(
    () => classroomMode
      ? SCMATE_ACTIONS.filter((action) => (classroomPreviews.get(action)?.targets.length || 0) > 0)
      : allowedScmateActions(pc),
    [classroomMode, classroomPreviews, pc],
  )
  const refreshAllowed = allowedActions.includes('REFRESH_STATUS_DETAIL')
  const actionButtons = allowedActions.filter((action) => action !== 'REFRESH_STATUS_DETAIL' && (!canPerform || canPerform(action)))
  const canRefresh = canAct && (!canPerform || canPerform('REFRESH_STATUS_DETAIL'))
  const showsRecoveryPoint = actionButtons.some(usesRecoveryPoint)

  const actionMutation = useMutation({
    mutationFn: async ({ action, point }: { action: ScmateAction; point: ScmateRecoveryPoint }) => {
      if (!classroomMode) return sendScmateAction(pc.macAddress, action, point)
      const targets = classroomPreviews.get(action)?.targets || []
      return sendScmateActions(targets, action, point)
    },
    onMutate: () => setBulkErrors([]),
    onSuccess: (result, { action }) => {
      if (classroomMode && result && 'failed' in result) {
        if (result.failed === 0) showToast(`${SCMATE_ACTION_LABELS[action]} 요청 ${result.queued}건을 전송했습니다.`)
        else {
          setBulkErrors(result.errors)
          const firstError = result.errors[0]?.message
          showToast(`${SCMATE_ACTION_LABELS[action]} 요청 ${result.queued}건 전송, ${result.failed}건 실패했습니다.${firstError ? ` ${firstError}` : ''}`, 'error')
        }
      } else {
        showToast((result && 'message' in result && result.message) || `${SCMATE_ACTION_LABELS[action]} 요청을 전송했습니다.`)
      }
      setPendingAction(null)
      void queryClient.invalidateQueries({ queryKey: ['pcs'] })
      void queryClient.invalidateQueries({ queryKey: ['classrooms'] })
      if (action === 'REFRESH_STATUS_DETAIL') {
        window.setTimeout(() => void queryClient.invalidateQueries({ queryKey: ['pcs'] }), 1_500)
      }
    },
    onError: (error) => {
      showToast(error instanceof Error ? error.message : 'SCMATE 작업을 요청하지 못했습니다.', 'error')
      setPendingAction(null)
    },
  })
  const blocked = Boolean(pendingAction) || actionMutation.isPending
  const dialogRef = useDialogFocus<HTMLElement>(onClose, blocked)

  const confirmAction = () => {
    if (!pendingAction) return
    if (!allowedActions.includes(pendingAction)) {
      showToast('현재 허용되지 않은 SCMATE 동작입니다. 상태를 새로고침해 주세요.', 'error')
      setPendingAction(null)
      return
    }
    actionMutation.mutate({ action: pendingAction, point: recoveryPoint })
  }

  const state = scmateState(pc)
  const statusError = scmateDisplayError(pc)
  return (
    <>
      <div className="modal-backdrop" role="presentation" onMouseDown={(event) => {
        if (event.target === event.currentTarget && !blocked) onClose()
      }}>
        <section ref={dialogRef} className="scmate-dialog" role="dialog" aria-modal="true" aria-labelledby="scmate-dialog-title" tabIndex={-1}>
          <header>
            <div><h2 id="scmate-dialog-title">{classroomMode ? '강의실 SCMATE' : 'SCMATE 상태'}</h2><p>{classroomMode ? `${pc.groupName || '-'} · 학생 PC 일괄 제어` : `${pc.groupName || '-'} · ${pc.pcName || pc.macAddress} · ${pc.ipAddress || '-'}`}</p></div>
            <button className="icon-button" onClick={onClose} disabled={blocked} aria-label="닫기"><X size={18} /></button>
          </header>

          {classroomMode ? (
            <div className="scmate-state-panel" aria-live="polite">
              <div className="scmate-state-heading">
                <ScmateStatusBadge pc={pc} busy={actionMutation.isPending} />
                {canRefresh && refreshAllowed && (
                  <button type="button" className="button secondary scmate-refresh-button" disabled={actionMutation.isPending} onClick={() => actionMutation.mutate({ action: 'REFRESH_STATUS_DETAIL', point: recoveryPoint })}>
                    <RefreshCw size={15} className={actionMutation.isPending ? 'spin-icon' : ''} /><span>상세 상태 새로고침</span><small>{classroomPreviews.get('REFRESH_STATUS_DETAIL')?.targets.length || 0}대</small>
                  </button>
                )}
              </div>
              <p className="scmate-classroom-summary">학생 PC {classroomPcs?.filter((target) => target.role?.toUpperCase() === 'STUDENT').length || 0}대</p>
            </div>
          ) : (
            <div className={`scmate-state-panel scmate-${state.tone}`} aria-live="polite">
              <div className="scmate-state-heading">
                <ScmateStatusBadge pc={pc} busy={actionMutation.isPending} />
                {canRefresh && refreshAllowed && (
                  <button type="button" className="button secondary scmate-refresh-button" disabled={actionMutation.isPending} onClick={() => actionMutation.mutate({ action: 'REFRESH_STATUS_DETAIL', point: recoveryPoint })}>
                    <RefreshCw size={15} className={actionMutation.isPending ? 'spin-icon' : ''} /><span>상세 상태 새로고침</span>
                  </button>
                )}
              </div>
              <dl>
                <div><dt>설치 상태</dt><dd>{scmateAvailabilityLabel(pc)}</dd></div>
                <div><dt>제품 유형</dt><dd>{pc.scmateProductVariant || '-'}</dd></div>
                <div><dt>현재 일정</dt><dd>{scmateScheduleLabel(pc.scmateSchedule)}</dd></div>
                <div><dt>선택 복구시점</dt><dd>{scmateRecoveryPointLabel(pc.scmateSelectedPoint)}</dd></div>
                <div><dt>기본 시점 생성</dt><dd>{formatObservedAt(pc.scmateBaselineCreatedAt)}</dd></div>
                <div><dt>시점 1 생성</dt><dd>{formatObservedAt(pc.scmateSnap1CreatedAt)}</dd></div>
                <div><dt>시점 2 생성</dt><dd>{formatObservedAt(pc.scmateSnap2CreatedAt)}</dd></div>
                <div><dt>시스템 부팅</dt><dd>{formatBootTime(pc.systemBootTimeEpochSeconds)}</dd></div>
                <div><dt>상태 출처</dt><dd>{scmateStateSourceLabel(pc.scmateStateSource)}</dd></div>
                <div><dt>상태 확인</dt><dd>{formatObservedAt(pc.scmateObservedAt)}</dd></div>
                <div><dt>워크플로 단계</dt><dd>{scmateWorkflowStageLabel(pc.scmateWorkflowStage)}</dd></div>
                <div><dt>원본 상태</dt><dd>{pc.scmateRawState ?? '-'}</dd></div>
              </dl>
              {statusError && <p className="scmate-error" role="alert">{statusError}</p>}
            </div>
          )}

          {canAct && showsRecoveryPoint && (
            <fieldset className="scmate-point-selector">
              <legend>복구 시점</legend>
              <div role="radiogroup" aria-label="복구 시점 선택">
                {SCMATE_RECOVERY_POINTS.map((point) => (
                  <button key={point} type="button" role="radio" aria-checked={recoveryPoint === point} className={recoveryPoint === point ? 'active' : ''} disabled={actionMutation.isPending} onClick={() => setRecoveryPoint(point)}>
                    {SCMATE_RECOVERY_POINT_LABELS[point]}
                  </button>
                ))}
              </div>
            </fieldset>
          )}

          {canAct && actionButtons.length > 0 && (
            <div className="scmate-actions" aria-label="허용된 SCMATE 동작">
              {actionButtons.map((action) => {
                const guarded = Boolean(scmateActionConfirmation(action))
                return (
                  <button key={action} className={`button ${guarded ? 'danger' : 'secondary'}`} disabled={actionMutation.isPending} onClick={() => setPendingAction(action)}>
                    {actionIcon(action)}<span className="scmate-action-label">{SCMATE_ACTION_LABELS[action]}</span>{classroomMode && <span className="scmate-action-count">{classroomPreviews.get(action)?.targets.length || 0}대</span>}
                  </button>
                )
              })}
            </div>
          )}

          {bulkErrors.length > 0 && (
            <div className="scmate-bulk-errors" role="alert">
              <strong>전송 실패 {bulkErrors.length}대</strong>
              <ul>{bulkErrors.map((error, index) => <li key={`${error.pcName}-${index}`}><span>{error.pcName}</span><p>{error.message}</p></li>)}</ul>
            </div>
          )}
        </section>
      </div>

      <ConfirmDialog
        open={Boolean(pendingAction)}
        title={pendingAction ? `${classroomMode ? `${pc.groupName || '-'} ` : ''}${SCMATE_ACTION_LABELS[pendingAction]} 확인` : 'SCMATE 작업 확인'}
        description={pendingAction ? (classroomMode
          ? classroomActionDescription(pc.groupName || '선택한 강의실', pendingAction, classroomPreviews.get(pendingAction)?.targets.length || 0, classroomPreviews.get(pendingAction)?.excluded.length || 0, recoveryPoint)
          : scmateActionDescription(pc, pendingAction, recoveryPoint)) : ''}
        confirmLabel={pendingAction ? SCMATE_ACTION_LABELS[pendingAction] : '확인'}
        danger={Boolean(pendingAction && scmateActionConfirmation(pendingAction))}
        requiredText={pendingAction ? scmateActionConfirmation(pendingAction) : undefined}
        requiredTextLabel={pendingAction ? `계속하려면 '${scmateActionConfirmation(pendingAction) || ''}' 입력` : undefined}
        busy={actionMutation.isPending}
        onCancel={() => { if (!actionMutation.isPending) setPendingAction(null) }}
        onConfirm={confirmAction}
      />
    </>
  )
}
