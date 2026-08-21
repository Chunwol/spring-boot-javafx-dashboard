export interface ChunkUploadResult {
  uploadId: string
  chunkIndex: number
  totalChunks: number
  uploadedChunks: number
  missingChunks: number
  missingChunkIndexesPreview: number[]
  resumed: boolean
  complete: boolean
}

export interface AssembleResult {
  id: number
  sha256: string
  sizeBytes: number
  downloadUrl: string
  deduplicated: boolean
  manifestKey: string
}

export interface UploadedArtifact extends AssembleResult {
  uploadId: string
  originalFilename: string
  totalChunks: number
  resumedChunks: number
}

export interface FileUploadProgress {
  phase: 'hashing' | 'uploading' | 'assembling' | 'complete'
  chunkIndex: number
  totalChunks: number
  uploadedBytes: number
  totalBytes: number
  resumedChunks: number
}

export interface CreateDistributionJobInput {
  uploadId: string
  originalFilename: string
  totalChunks: number
  artifactId: number
  groupId?: number
  targetMacs?: string[]
  waveSize: number
  canaryOnly: boolean
  targetVersion?: string
  sourceMode: 'SERVER_ONLY' | 'HYBRID_PEER'
  rampUpFactor: number
  updatePackageType: 'JAR' | 'FULL_BUNDLE'
}

export interface CreatedDistributionJob {
  jobId: number
  status: string
  uploadId: string
  artifactId?: number | null
  targetVersion?: string | null
  sourceMode?: string | null
  rampUpFactor?: number | null
  updatePackageType?: 'JAR' | 'FULL_BUNDLE' | string | null
}

export interface DistributionJobSummary {
  jobId: number
  uploadId: string
  originalFilename: string
  status: string
  artifactId?: number | null
  targetVersion?: string | null
  sourceMode?: string | null
  rampUpFactor?: number | null
  updatePackageType?: 'JAR' | 'FULL_BUNDLE' | string | null
  createdAt?: string | null
  completedAt?: string | null
  cancelledAt?: string | null
  errorMessage?: string | null
}

export interface DistributionWaveStatus {
  wave: number
  total: number
  queued: number
  inProgress: number
  completed: number
  failed: number
  cancelled: number
}

export interface DistributionJobStatus {
  jobId: number
  uploadId: string
  status: string
  artifactId?: number | null
  targetVersion?: string | null
  sourceMode?: string | null
  rampUpFactor?: number | null
  updatePackageType?: 'JAR' | 'FULL_BUNDLE' | string | null
  totalAgents: number
  completed: number
  failed: number
  inProgress: number
  cancelled: number
  progress: number
  createdAt?: string | null
  completedAt?: string | null
  cancelledAt?: string | null
  waves?: { items?: Record<string, Record<string, unknown>> }
}

export interface DistributionJobEvent {
  id: number
  eventType: string
  detailJson?: string | null
  createdAt?: string | null
}

export interface UploadSession {
  fingerprint: string
  uploadId: string
  originalFilename: string
  fileSize: number
  lastModified: number
  totalChunks: number
  updatedAt: number
}
