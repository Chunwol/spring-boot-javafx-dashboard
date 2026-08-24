import type { ScriptExecutionStatus, ScriptRisk, ScriptTemplateDraft } from './types'

export const EMPTY_SCRIPT_DRAFT: ScriptTemplateDraft = {
  name: '',
  description: '',
  category: '사용자',
  riskLevel: 'MEDIUM',
  requiresReboot: false,
  requiresOperatorMode: false,
  powershellRequirement: 'WINDOWS_POWERSHELL_5_1',
  successMarker: '',
  inverseTemplateId: '',
  file: null,
}

export const RISK_LABELS: Record<ScriptRisk, string> = {
  LOW: '낮음',
  MEDIUM: '보통',
  HIGH: '높음',
  CRITICAL: '매우 높음',
}

export const EXECUTION_STATUS_LABELS: Record<ScriptExecutionStatus, string> = {
  QUEUED: '대기',
  IN_PROGRESS: '실행 중',
  COMPLETED: '완료',
  FAILED: '실패',
  PARTIAL_FAILED: '일부 실패',
}

export function scriptDraftError(draft: ScriptTemplateDraft, editing: boolean) {
  if (!draft.name.trim()) return '스크립트 이름을 입력하세요.'
  if (!draft.category.trim()) return '분류를 입력하세요.'
  if (!editing && !draft.file) return '등록할 PS1 파일을 선택하세요.'
  if (draft.file && !draft.file.name.toLowerCase().endsWith('.ps1')) return '.ps1 파일만 등록할 수 있습니다.'
  if (draft.file && draft.file.size > 1024 * 1024) return 'PS1 파일은 1MB를 초과할 수 없습니다.'
  return null
}

export function requiresExecutionAcknowledgement(risk: ScriptRisk, requiresReboot: boolean) {
  return risk === 'HIGH' || risk === 'CRITICAL' || requiresReboot
}
