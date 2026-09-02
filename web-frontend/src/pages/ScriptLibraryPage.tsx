import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FileCode2, Pencil, Play, Plus, RefreshCw, RotateCcw, ShieldAlert, Trash2, X } from 'lucide-react'
import { ConfirmDialog } from '../components/ui/ConfirmDialog'
import { useAuth } from '../auth/AuthProvider'
import { EmptyState, PageError, PageLoading } from '../components/ui/PageState'
import { useToast } from '../components/ui/ToastProvider'
import { getPcs } from '../features/pcs/api'
import {
  createScriptTemplate,
  deleteScriptTemplate,
  executeScriptTemplate,
  getRecentScriptExecutions,
  getScriptTemplates,
  rerunScriptExecution,
  retryFailedScriptExecution,
  updateScriptTemplate,
} from '../features/scriptLibrary/api'
import {
  EMPTY_SCRIPT_DRAFT,
  EXECUTION_STATUS_LABELS,
  RISK_LABELS,
  requiresExecutionAcknowledgement,
  scriptDraftError,
} from '../features/scriptLibrary/model'
import type { ScriptExecution, ScriptTemplate, ScriptTemplateDraft } from '../features/scriptLibrary/types'
import '../features/scriptLibrary/script-library.css'

type PendingAction =
  | { type: 'execute'; template: ScriptTemplate }
  | { type: 'rerun'; execution: ScriptExecution }
  | { type: 'retry'; execution: ScriptExecution }
  | { type: 'delete'; template: ScriptTemplate }

function templateDraft(template?: ScriptTemplate): ScriptTemplateDraft {
  if (!template) return { ...EMPTY_SCRIPT_DRAFT }
  return {
    name: template.name,
    description: template.description || '',
    category: template.category,
    riskLevel: template.riskLevel,
    requiresReboot: template.requiresReboot,
    requiresOperatorMode: template.requiresOperatorMode,
    powershellRequirement: template.powershellRequirement,
    successMarker: template.successMarker || '',
    inverseTemplateId: template.inverseTemplateId ? String(template.inverseTemplateId) : '',
    file: null,
  }
}

function TemplateEditor({ template, templates, busy, onClose, onSave }: {
  template?: ScriptTemplate
  templates: ScriptTemplate[]
  busy: boolean
  onClose: () => void
  onSave: (draft: ScriptTemplateDraft) => void
}) {
  const [draft, setDraft] = useState(() => templateDraft(template))
  const error = scriptDraftError(draft, Boolean(template))
  const set = <K extends keyof ScriptTemplateDraft>(key: K, value: ScriptTemplateDraft[K]) => setDraft((current) => ({ ...current, [key]: value }))
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !busy) onClose() }}>
      <section className="script-editor" role="dialog" aria-modal="true" aria-labelledby="script-editor-title">
        <header><div><h2 id="script-editor-title">{template ? '스크립트 새 버전 등록' : '스크립트 등록'}</h2><p>실행 시 이 버전과 SHA-256이 이력에 고정됩니다.</p></div><button className="icon-button" onClick={onClose} disabled={busy} aria-label="닫기"><X size={18} /></button></header>
        <div className="script-editor-grid">
          <label><span>이름</span><input value={draft.name} onChange={(event) => set('name', event.target.value)} maxLength={120} /></label>
          <label><span>분류</span><input value={draft.category} onChange={(event) => set('category', event.target.value)} maxLength={64} /></label>
          <label className="full"><span>설명</span><textarea value={draft.description} onChange={(event) => set('description', event.target.value)} maxLength={2000} rows={3} /></label>
          <label><span>위험도</span><select value={draft.riskLevel} onChange={(event) => set('riskLevel', event.target.value as ScriptTemplateDraft['riskLevel'])}><option value="LOW">낮음</option><option value="MEDIUM">보통</option><option value="HIGH">높음</option><option value="CRITICAL">매우 높음</option></select></label>
          <label><span>요구 PowerShell</span><select value={draft.powershellRequirement} onChange={(event) => set('powershellRequirement', event.target.value as ScriptTemplateDraft['powershellRequirement'])}><option value="WINDOWS_POWERSHELL_5_1">Windows PowerShell 5.1</option><option value="POWERSHELL_7">PowerShell 7 전용(현재 실행 불가)</option></select></label>
          <label className="full"><span>성공 확인 문구</span><input value={draft.successMarker} onChange={(event) => set('successMarker', event.target.value)} maxLength={300} placeholder="stdout에 이 문구가 있어야 성공 처리 (선택)" /></label>
          <label><span>반대 작업 연결</span><select value={draft.inverseTemplateId} onChange={(event) => set('inverseTemplateId', event.target.value)}><option value="">없음</option>{templates.filter((item) => item.id !== template?.id).map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
          <label><span>PS1 파일</span><input type="file" accept=".ps1,text/plain" onChange={(event) => set('file', event.target.files?.[0] || null)} />{template && <small>파일을 비우면 기존 본문을 유지한 새 버전이 생성됩니다.</small>}</label>
        </div>
        <div className="script-checks"><label><input type="checkbox" checked={draft.requiresOperatorMode} onChange={(event) => set('requiresOperatorMode', event.target.checked)} />운영자 모드 필요</label><label><input type="checkbox" checked={draft.requiresReboot} onChange={(event) => set('requiresReboot', event.target.checked)} />재부팅 필요</label></div>
        {draft.powershellRequirement === 'POWERSHELL_7' && <p className="script-warning">현재 에이전트 RUN_PS1은 Windows PowerShell 5.1 실행 기준입니다. PowerShell 7 전용 스크립트는 보관할 수 있지만 실행은 차단됩니다.</p>}
        {error && <p className="form-error">{error}</p>}
        <div className="modal-actions"><button className="button secondary" onClick={onClose} disabled={busy}>취소</button><button className="button primary" onClick={() => onSave(draft)} disabled={Boolean(error) || busy}>{busy ? '저장 중' : template ? '새 버전 저장' : '등록'}</button></div>
      </section>
    </div>
  )
}

export function ScriptLibraryPage() {
  const { hasPermission } = useAuth()
  const canEdit = hasPermission('SCRIPT_CREATE_EDIT')
  const canDelete = hasPermission('SCRIPT_DELETE')
  const canRun = hasPermission('SCRIPT_EXECUTE')
  const queryClient = useQueryClient()
  const { showToast } = useToast()
  const [selectedTemplateId, setSelectedTemplateId] = useState<number | null>(null)
  const [selectedMacs, setSelectedMacs] = useState<Set<string>>(new Set())
  const [pcSearch, setPcSearch] = useState('')
  const [groupFilter, setGroupFilter] = useState('ALL')
  const [runMode, setRunMode] = useState<'FOREGROUND' | 'BACKGROUND'>('FOREGROUND')
  const [windowMode, setWindowMode] = useState<'HIDDEN' | 'VISIBLE'>('HIDDEN')
  const [argumentsText, setArgumentsText] = useState('')
  const [acknowledged, setAcknowledged] = useState(false)
  const [editing, setEditing] = useState<ScriptTemplate | 'new' | null>(null)
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null)

  const templatesQuery = useQuery({ queryKey: ['script-library', 'templates'], queryFn: getScriptTemplates })
  const pcsQuery = useQuery({ queryKey: ['pcs', 'script-library'], queryFn: getPcs })
  const executionsQuery = useQuery({
    queryKey: ['script-library', 'executions', 10],
    queryFn: () => getRecentScriptExecutions(10),
    refetchInterval: (query) => query.state.data?.some((row) => row.activeCount > 0) ? 5_000 : 20_000,
  })
  const templates = templatesQuery.data || []
  const selectedTemplate = templates.find((item) => item.id === selectedTemplateId) || null
  const pcs = (pcsQuery.data || []).filter((pc) => pc.online && !pc.brokenNote)
  const groups = useMemo(() => [...new Set(pcs.map((pc) => pc.groupName || (pc.groupId != null ? `그룹 ${pc.groupId}` : '미배정')))].sort((a, b) => a.localeCompare(b, 'ko-KR', { numeric: true })), [pcs])
  const visiblePcs = useMemo(() => {
    const query = pcSearch.trim().toLocaleLowerCase('ko-KR')
    return pcs.filter((pc) => {
      const group = pc.groupName || (pc.groupId != null ? `그룹 ${pc.groupId}` : '미배정')
      if (groupFilter !== 'ALL' && group !== groupFilter) return false
      return !query || [pc.pcName, pc.macAddress, pc.ipAddress, group].filter(Boolean).join(' ').toLocaleLowerCase('ko-KR').includes(query)
    }).sort((a, b) => (a.groupName || '').localeCompare(b.groupName || '', 'ko-KR', { numeric: true }) || a.pcName.localeCompare(b.pcName, 'ko-KR', { numeric: true }))
  }, [groupFilter, pcSearch, pcs])

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['script-library'] })
  }
  const saveMutation = useMutation({
    mutationFn: ({ template, draft }: { template?: ScriptTemplate; draft: ScriptTemplateDraft }) => template ? updateScriptTemplate(template.id, draft) : createScriptTemplate(draft),
    onSuccess: (saved) => { showToast(`${saved.name} v${saved.currentVersion} 저장 완료`, 'success'); setEditing(null); setSelectedTemplateId(saved.id); refresh() },
    onError: (error) => showToast(error instanceof Error ? error.message : '스크립트를 저장하지 못했습니다.', 'error'),
  })
  const executeMutation = useMutation({
    mutationFn: ({ template, kind, execution }: { template?: ScriptTemplate; kind: 'execute' | 'rerun' | 'retry'; execution?: ScriptExecution }) => {
      if (kind === 'rerun' && execution) return rerunScriptExecution(execution.id, true)
      if (kind === 'retry' && execution) return retryFailedScriptExecution(execution.id, true)
      if (!template) throw new Error('실행할 스크립트가 없습니다.')
      return executeScriptTemplate(template.id, { targetMacs: [...selectedMacs], runMode, windowMode, arguments: argumentsText.trim() || undefined, acknowledged })
    },
    onSuccess: (execution) => { showToast(`${execution.templateName} 실행 ${execution.targetCount}대 등록`, 'success'); setPendingAction(null); setAcknowledged(false); refresh() },
    onError: (error) => { setPendingAction(null); showToast(error instanceof Error ? error.message : '스크립트를 실행하지 못했습니다.', 'error') },
  })
  const deleteMutation = useMutation({
    mutationFn: deleteScriptTemplate,
    onSuccess: () => { showToast('스크립트를 삭제했습니다.', 'success'); setPendingAction(null); setSelectedTemplateId(null); refresh() },
    onError: (error) => { setPendingAction(null); showToast(error instanceof Error ? error.message : '스크립트를 삭제하지 못했습니다.', 'error') },
  })

  if (templatesQuery.isPending || pcsQuery.isPending) return <section className="content-section"><PageLoading label="스크립트 보관함을 불러오는 중입니다." /></section>
  if (templatesQuery.isError || pcsQuery.isError) return <section className="content-section"><PageError message="스크립트 보관함 또는 PC 목록을 불러오지 못했습니다." onRetry={() => { void templatesQuery.refetch(); void pcsQuery.refetch() }} /></section>

  const allVisibleSelected = visiblePcs.length > 0 && visiblePcs.every((pc) => selectedMacs.has(pc.macAddress))
  const toggleVisible = () => setSelectedMacs((current) => {
    const next = new Set(current)
    if (allVisibleSelected) visiblePcs.forEach((pc) => next.delete(pc.macAddress))
    else visiblePcs.forEach((pc) => next.add(pc.macAddress))
    return next
  })
  const needsAck = selectedTemplate ? requiresExecutionAcknowledgement(selectedTemplate.riskLevel, selectedTemplate.requiresReboot) : false
  const canExecute = Boolean(canRun && selectedTemplate && selectedMacs.size && selectedTemplate.powershellRequirement === 'WINDOWS_POWERSHELL_5_1' && (!needsAck || acknowledged))

  return (
    <section className="content-section script-library-page">
      <header className="page-header"><div><h1>스크립트 보관함</h1><p>에이전트 변경 없이 기존 RUN_PS1로 버전 고정 스크립트를 실행합니다.</p></div><div className="script-header-actions"><button className="icon-button bordered" onClick={() => { void templatesQuery.refetch(); void executionsQuery.refetch() }} aria-label="새로고침"><RefreshCw size={17} /></button>{canEdit && <button className="button primary" onClick={() => setEditing('new')}><Plus size={16} />스크립트 등록</button>}</div></header>

      <div className="script-library-layout">
        <section className="script-panel"><header><h2>등록 스크립트</h2><span>{templates.length}개</span></header>{templates.length ? <div className="script-template-list">{templates.map((template) => <article key={template.id} className={`script-template-card${selectedTemplateId === template.id ? ' selected' : ''}`} onClick={() => { setSelectedTemplateId(template.id); setAcknowledged(false) }}><div className="script-template-title"><FileCode2 size={18} /><div><strong>{template.name}</strong><small>{template.category} · v{template.currentVersion}</small></div><span className={`risk-badge ${template.riskLevel.toLowerCase()}`}>{RISK_LABELS[template.riskLevel]}</span></div><p>{template.description || '설명 없음'}</p><div className="script-tags">{template.builtIn && <span>기본 제공</span>}{template.requiresOperatorMode && <span>운영자 모드</span>}{template.requiresReboot && <span>재부팅</span>}<span>{template.powershellRequirement === 'POWERSHELL_7' ? 'PowerShell 7' : 'Windows PS 5.1'}</span></div>{!template.builtIn && (canEdit || canDelete) && <div className="script-card-actions">{canEdit && <button className="icon-button" onClick={(event) => { event.stopPropagation(); setEditing(template) }} aria-label={`${template.name} 수정`}><Pencil size={15} /></button>}{canDelete && <button className="icon-button danger" onClick={(event) => { event.stopPropagation(); setPendingAction({ type: 'delete', template }) }} aria-label={`${template.name} 삭제`}><Trash2 size={15} /></button>}</div>}</article>)}</div> : <EmptyState message="등록된 스크립트가 없습니다." />}</section>

        <section className="script-panel script-run-panel"><header><h2>대상 선택 및 실행</h2><span>{selectedMacs.size}대 선택</span></header>{!selectedTemplate ? <EmptyState message="왼쪽에서 실행할 스크립트를 선택하세요." /> : <><div className="selected-script-summary"><strong>{selectedTemplate.name} v{selectedTemplate.currentVersion}</strong><code>{selectedTemplate.sha256.slice(0, 16)}…</code>{selectedTemplate.inverseTemplateId && <span>반대 작업: {templates.find((item) => item.id === selectedTemplate.inverseTemplateId)?.name || `#${selectedTemplate.inverseTemplateId}`}</span>}</div><div className="script-target-filters"><input value={pcSearch} onChange={(event) => setPcSearch(event.target.value)} placeholder="PC명·MAC·강의실 검색" /><select value={groupFilter} onChange={(event) => setGroupFilter(event.target.value)}><option value="ALL">전체 강의실</option>{groups.map((group) => <option key={group}>{group}</option>)}</select><button className="button secondary" onClick={toggleVisible}>{allVisibleSelected ? '현재 목록 해제' : '현재 목록 전체 선택'}</button></div><div className="script-pc-list">{visiblePcs.map((pc) => <label key={pc.macAddress} className={selectedMacs.has(pc.macAddress) ? 'selected' : ''}><input type="checkbox" checked={selectedMacs.has(pc.macAddress)} onChange={() => setSelectedMacs((current) => { const next = new Set(current); if (next.has(pc.macAddress)) next.delete(pc.macAddress); else next.add(pc.macAddress); return next })} /><span><strong>{pc.pcName}</strong><small>{pc.groupName || `그룹 ${pc.groupId ?? '-'}`} · {pc.macAddress}</small></span>{selectedTemplate.requiresOperatorMode && <em className={pc.scmateMode === 'OPERATOR' ? 'ok' : 'bad'}>{pc.scmateMode || 'UNKNOWN'}</em>}</label>)}</div><div className="script-run-options"><label><span>실행 방식</span><select value={runMode} onChange={(event) => setRunMode(event.target.value as typeof runMode)}><option value="FOREGROUND">완료까지 대기</option><option value="BACKGROUND">즉시 실행</option></select></label><label><span>창 표시</span><select value={windowMode} onChange={(event) => setWindowMode(event.target.value as typeof windowMode)}><option value="HIDDEN">숨김</option><option value="VISIBLE">표시</option></select></label><label className="full"><span>인수</span><input value={argumentsText} onChange={(event) => setArgumentsText(event.target.value)} maxLength={1000} placeholder="선택 입력" /></label></div>{needsAck && <label className="script-ack"><input type="checkbox" checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)} /><ShieldAlert size={16} />위험도와 재부팅·복구 영향 및 선택한 대상 PC를 확인했습니다.</label>}{selectedTemplate.powershellRequirement === 'POWERSHELL_7' && <p className="script-warning">PowerShell 7 전용 스크립트는 현재 RUN_PS1 엔진에서 실행할 수 없습니다.</p>}<button className="button primary script-run-button" disabled={!canExecute || executeMutation.isPending} onClick={() => setPendingAction({ type: 'execute', template: selectedTemplate })}><Play size={16} />선택 {selectedMacs.size}대 실행</button></>}</section>
      </div>

      <section className="script-panel recent-script-runs"><header><div><h2>최근 실행 10개</h2><p>실행 당시 버전과 SHA-256, 대상별 task ID가 그대로 보존됩니다.</p></div></header>{executionsQuery.isPending ? <PageLoading label="최근 실행을 불러오는 중입니다." /> : !(executionsQuery.data || []).length ? <EmptyState message="스크립트 실행 이력이 없습니다." /> : <div className="table-scroll"><table><thead><tr><th>시각</th><th>스크립트</th><th>상태</th><th>대상</th>{canRun && <th>작업</th>}</tr></thead><tbody>{(executionsQuery.data || []).map((execution) => <tr key={execution.id}><td>{new Date(execution.createdAt).toLocaleString('ko-KR')}</td><td><strong>{execution.templateName}</strong><small>v{execution.version} · {execution.sha256.slice(0, 12)}…</small></td><td><span className={`execution-status ${execution.status.toLowerCase()}`}>{EXECUTION_STATUS_LABELS[execution.status]}</span></td><td><details><summary>완료 {execution.completedCount} / 실패 {execution.failedCount} / 진행 {execution.activeCount}</summary><ul>{execution.targets.map((target) => <li key={target.taskId}><span>{target.pcName || target.macAddress}</span><code>#{target.taskId}</code><em>{target.status}</em>{target.stderr && <small>{target.stderr.split(/\r?\n/, 1)[0]}</small>}</li>)}</ul></details></td>{canRun && <td><div className="run-row-actions"><button className="button secondary compact" onClick={() => setPendingAction({ type: 'rerun', execution })}><RotateCcw size={14} />같은 대상</button><button className="button secondary compact" disabled={!execution.failedCount} onClick={() => setPendingAction({ type: 'retry', execution })}>실패만 재실행</button></div></td>}</tr>)}</tbody></table></div>}</section>

      {editing && <TemplateEditor template={editing === 'new' ? undefined : editing} templates={templates} busy={saveMutation.isPending} onClose={() => setEditing(null)} onSave={(draft) => saveMutation.mutate({ template: editing === 'new' ? undefined : editing, draft })} />}
      <ConfirmDialog open={Boolean(pendingAction)} title={pendingAction?.type === 'delete' ? '스크립트 삭제' : pendingAction?.type === 'retry' ? '실패 PC만 재실행' : pendingAction?.type === 'rerun' ? '같은 대상에 다시 실행' : '스크립트 실행 확인'} description={pendingAction?.type === 'delete' ? `${pendingAction.template.name}을 보관함에서 삭제합니다. 기존 실행 이력은 유지됩니다.` : pendingAction?.type === 'retry' ? `${pendingAction.execution.templateName}의 실패 대상 ${pendingAction.execution.failedCount}대에 실행 당시 버전을 다시 실행합니다.` : pendingAction?.type === 'rerun' ? `${pendingAction.execution.templateName} v${pendingAction.execution.version}을 기존 대상 ${pendingAction.execution.targetCount}대에 다시 실행합니다.` : pendingAction?.type === 'execute' ? `${pendingAction.template.name}을 선택한 ${selectedMacs.size}대에 실행합니다.` : ''} confirmLabel={pendingAction?.type === 'delete' ? '삭제' : '실행'} danger={pendingAction?.type === 'delete' || pendingAction?.type === 'execute' && requiresExecutionAcknowledgement(pendingAction.template.riskLevel, pendingAction.template.requiresReboot)} busy={executeMutation.isPending || deleteMutation.isPending} onCancel={() => setPendingAction(null)} onConfirm={() => { if (!pendingAction) return; if (pendingAction.type === 'delete') deleteMutation.mutate(pendingAction.template.id); else if (pendingAction.type === 'execute') executeMutation.mutate({ kind: 'execute', template: pendingAction.template }); else executeMutation.mutate({ kind: pendingAction.type, execution: pendingAction.execution }) }} />
    </section>
  )
}
