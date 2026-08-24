import { api, apiRequest } from '../../api/client'
import type { ScriptExecution, ScriptExecutionRequest, ScriptTemplate, ScriptTemplateDraft } from './types'

export function getScriptTemplates() {
  return api.get<ScriptTemplate[]>('/script-library/templates')
}

export function getRecentScriptExecutions(limit = 10) {
  return api.get<ScriptExecution[]>(`/script-library/executions/recent?limit=${limit}`)
}

function templateForm(draft: ScriptTemplateDraft) {
  const form = new FormData()
  form.set('name', draft.name.trim())
  form.set('description', draft.description.trim())
  form.set('category', draft.category.trim())
  form.set('riskLevel', draft.riskLevel)
  form.set('requiresReboot', String(draft.requiresReboot))
  form.set('requiresOperatorMode', String(draft.requiresOperatorMode))
  form.set('powershellRequirement', draft.powershellRequirement)
  form.set('successMarker', draft.successMarker.trim())
  if (draft.inverseTemplateId) form.set('inverseTemplateId', draft.inverseTemplateId)
  if (draft.file) form.set('file', draft.file)
  return form
}

export function createScriptTemplate(draft: ScriptTemplateDraft) {
  return api.upload<ScriptTemplate>('/script-library/templates', templateForm(draft))
}

export function updateScriptTemplate(id: number, draft: ScriptTemplateDraft) {
  return apiRequest<ScriptTemplate>(`/script-library/templates/${id}`, {
    method: 'PUT',
    body: templateForm(draft),
  })
}

export function deleteScriptTemplate(id: number) {
  return api.delete<{ id: number; deleted: boolean }>(`/script-library/templates/${id}`)
}

export function executeScriptTemplate(id: number, request: ScriptExecutionRequest) {
  return api.post<ScriptExecution>(`/script-library/templates/${id}/execute`, request)
}

export function rerunScriptExecution(id: number, acknowledged: boolean) {
  return api.post<ScriptExecution>(`/script-library/executions/${id}/rerun`, { acknowledged })
}

export function retryFailedScriptExecution(id: number, acknowledged: boolean) {
  return api.post<ScriptExecution>(`/script-library/executions/${id}/retry-failed`, { acknowledged })
}
