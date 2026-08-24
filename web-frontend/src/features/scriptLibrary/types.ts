export type ScriptRisk = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
export type PowerShellRequirement = 'WINDOWS_POWERSHELL_5_1' | 'POWERSHELL_7'
export type ScriptExecutionStatus = 'QUEUED' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED' | 'PARTIAL_FAILED'

export interface ScriptTemplate {
  id: number
  name: string
  description?: string | null
  category: string
  riskLevel: ScriptRisk
  requiresReboot: boolean
  requiresOperatorMode: boolean
  powershellRequirement: PowerShellRequirement
  successMarker?: string | null
  builtIn: boolean
  builtInKey?: string | null
  inverseTemplateId?: number | null
  currentVersion: number
  sha256: string
  downloadUrl: string
  createdAt: string
  updatedAt: string
}

export interface ScriptExecutionTarget {
  macAddress: string
  pcName?: string | null
  groupId?: number | null
  taskId: number
  status: string
  exitCode?: number | null
  stdout?: string | null
  stderr?: string | null
}

export interface ScriptExecution {
  id: number
  templateId: number
  templateName: string
  version: number
  sha256: string
  status: ScriptExecutionStatus
  runMode: 'FOREGROUND' | 'BACKGROUND'
  windowMode: 'HIDDEN' | 'VISIBLE'
  arguments?: string | null
  targetCount: number
  completedCount: number
  failedCount: number
  activeCount: number
  sourceExecutionId?: number | null
  createdAt: string
  updatedAt: string
  targets: ScriptExecutionTarget[]
}

export interface ScriptExecutionRequest {
  targetMacs: string[]
  runMode: 'FOREGROUND' | 'BACKGROUND'
  windowMode: 'HIDDEN' | 'VISIBLE'
  arguments?: string
  acknowledged: boolean
}

export interface ScriptTemplateDraft {
  name: string
  description: string
  category: string
  riskLevel: ScriptRisk
  requiresReboot: boolean
  requiresOperatorMode: boolean
  powershellRequirement: PowerShellRequirement
  successMarker: string
  inverseTemplateId: string
  file: File | null
}
