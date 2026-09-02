import { useEffect, useMemo, useState } from 'react'
import type { CSSProperties, FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useLocation } from 'react-router-dom'
import {
  ArrowRightLeft,
  CheckCircle2,
  FileArchive,
  RefreshCw,
  RotateCw,
  ShieldCheck,
  UploadCloud,
  XCircle,
} from 'lucide-react'
import { useAuth } from '../auth/AuthProvider'
import { ConfirmDialog } from '../components/ui/ConfirmDialog'
import { EmptyState, PageError, PageLoading } from '../components/ui/PageState'
import { useToast } from '../components/ui/ToastProvider'
import { getClassrooms } from '../features/classrooms/api'
import { getPcs } from '../features/pcs/api'
import {
  cancelDistributionJob,
  cancelPeerTransfer,
  createPeerTransfer,
  createDistributionJob,
  getDistributionJob,
  getDistributionJobEvents,
  getRecentDistributionJobs,
  retryFailedDistribution,
  retryFailedPeerTransfer,
  uploadAndAssembleFile,
} from '../features/files/api'
import {
  canCancelJob,
  canRetryJob,
  clearUploadSession,
  createUploadId,
  FILE_CHUNK_SIZE,
  fileFingerprint,
  formatBytes,
  formatDateTime,
  formatEventDetail,
  jobStatusLabel,
  jobStatusTone,
  normalizeEvents,
  normalizeJobStatus,
  normalizeRecentJobs,
  normalizeWaves,
  resolveUploadSession,
  totalChunksFor,
} from '../features/files/model'
import type { CreateDistributionJobInput, CreatePeerTransferInput, FileUploadProgress, UploadedArtifact } from '../features/files/types'

type PendingAction =
  | { type: 'deploy'; input: CreateDistributionJobInput }
  | { type: 'peerTransfer'; input: CreatePeerTransferInput }
  | { type: 'cancel'; jobId: number; peer: boolean }
  | { type: 'retry'; jobId: number; peer: boolean }

const styles = {
  twoColumn: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 430px), 1fr))',
    gap: 16,
    alignItems: 'start',
    marginBottom: 18,
  },
  panel: {
    minWidth: 0,
    padding: 16,
    background: 'var(--surface-subtle)',
    border: '1px solid var(--border)',
    borderRadius: 7,
  },
  panelHeader: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 10,
    marginBottom: 14,
  },
  panelTitle: { display: 'flex', alignItems: 'center', gap: 8, margin: 0, fontSize: 14 },
  formGrid: { display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 10 },
  field: { display: 'grid', gap: 5, minWidth: 0 },
  fieldWide: { display: 'grid', gridColumn: '1 / -1', gap: 5, minWidth: 0 },
  fieldLabel: { color: 'var(--muted)', fontSize: 10, fontWeight: 800 },
  input: {
    width: '100%',
    height: 38,
    minWidth: 0,
    padding: '0 10px',
    color: '#415169',
    background: 'white',
    border: '1px solid var(--border-strong)',
    borderRadius: 6,
    fontSize: 12,
  },
  uploadBox: {
    display: 'grid',
    justifyItems: 'center',
    gap: 9,
    minHeight: 142,
    padding: 20,
    background: 'white',
    border: '1px dashed var(--border-strong)',
    borderRadius: 7,
    textAlign: 'center' as const,
  },
  uploadName: { maxWidth: '100%', overflow: 'hidden', fontSize: 12, textOverflow: 'ellipsis', whiteSpace: 'nowrap' as const },
  subtle: { margin: 0, color: 'var(--muted)', fontSize: 10 },
  progressTrack: { width: '100%', height: 8, overflow: 'hidden', background: '#e2e8f0', borderRadius: 4 },
  progressBar: { height: '100%', background: 'var(--blue)', borderRadius: 4, transition: 'width 160ms ease' },
  artifact: {
    display: 'grid',
    gridTemplateColumns: 'auto minmax(0, 1fr)',
    alignItems: 'center',
    gap: 11,
    marginTop: 12,
    padding: 11,
    color: '#17663f',
    background: 'var(--green-soft)',
    border: '1px solid #b9e4cc',
    borderRadius: 6,
  },
  artifactText: { minWidth: 0 },
  artifactName: { display: 'block', overflow: 'hidden', fontSize: 12, textOverflow: 'ellipsis', whiteSpace: 'nowrap' as const },
  artifactMeta: { display: 'block', marginTop: 3, fontSize: 10 },
  checkRow: { display: 'flex', alignItems: 'center', gap: 8, minHeight: 38, color: '#415169', fontSize: 12 },
  actions: { display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 12 },
  detailGrid: { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(110px, 1fr))', gap: 8, marginBottom: 14 },
  metric: { minWidth: 0, padding: 10, background: 'white', border: '1px solid var(--border)', borderRadius: 6 },
  metricLabel: { display: 'block', color: 'var(--muted)', fontSize: 9 },
  metricValue: { display: 'block', marginTop: 4, overflow: 'hidden', fontSize: 15, textOverflow: 'ellipsis' },
  splitDetail: { display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 420px), 1fr))', gap: 16, marginTop: 16 },
  eventList: { maxHeight: 310, overflowY: 'auto' as const, border: '1px solid var(--border)', borderRadius: 6 },
  event: { padding: '10px 12px', background: 'white', borderBottom: '1px solid var(--border)' },
  eventTop: { display: 'flex', justifyContent: 'space-between', gap: 10, marginBottom: 4, fontSize: 11 },
  eventDetail: { margin: 0, color: 'var(--muted)', overflowWrap: 'anywhere' as const, fontSize: 10, lineHeight: 1.45 },
  targetList: { maxHeight: 190, overflowY: 'auto' as const, background: 'white', border: '1px solid var(--border)', borderRadius: 6 },
  targetRow: { display: 'grid', gridTemplateColumns: 'auto minmax(0, 1fr)', alignItems: 'center', gap: 8, minHeight: 34, padding: '5px 9px', borderBottom: '1px solid var(--border)', fontSize: 11 },
} satisfies Record<string, CSSProperties>

function mutationMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback
}

function sourceModeLabel(mode?: string | null) {
  return mode === 'HYBRID_PEER' ? '하이브리드 피어' : mode === 'SERVER_ONLY' ? '서버 직접' : mode === 'PC_DIRECT' ? 'PC 직접' : mode || '-'
}

function uploadPercent(progress: FileUploadProgress | null) {
  if (!progress) return 0
  if (progress.phase === 'complete') return 100
  if (progress.phase === 'assembling') return 95
  if (!progress.totalBytes) return 0
  return Math.min(90, Math.round((progress.uploadedBytes / progress.totalBytes) * 90))
}

function uploadPhaseLabel(progress: FileUploadProgress | null) {
  if (!progress) return '업로드 대기'
  if (progress.phase === 'hashing') return `${progress.chunkIndex + 1}/${progress.totalChunks} 청크 해시 계산`
  if (progress.phase === 'uploading') return `${progress.chunkIndex + 1}/${progress.totalChunks} 청크 전송 완료`
  if (progress.phase === 'assembling') return '서버에서 파일 조립 중'
  return '업로드 완료'
}

export function FilesPage() {
  const location = useLocation()
  const { hasPermission, token } = useAuth()
  const allowed = hasPermission('FILE_DISTRIBUTION_VIEW')
  const canUpload = hasPermission('FILE_UPLOAD_DISTRIBUTE')
  const canPeerTransfer = hasPermission('PEER_FILE_TRANSFER')
  const canControlJobs = hasPermission('FILE_JOB_CONTROL')
  const { showToast } = useToast()
  const queryClient = useQueryClient()
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [uploadId, setUploadId] = useState('')
  const [uploadFingerprint, setUploadFingerprint] = useState('')
  const [resumeAvailable, setResumeAvailable] = useState(false)
  const [uploadProgress, setUploadProgress] = useState<FileUploadProgress | null>(null)
  const [uploadedArtifact, setUploadedArtifact] = useState<UploadedArtifact | null>(null)
  const routeTargetMacs = Array.isArray((location.state as { targetMacs?: unknown } | null)?.targetMacs)
    ? ((location.state as { targetMacs: unknown[] }).targetMacs.filter((value): value is string => typeof value === 'string'))
    : []
  const [targetMode, setTargetMode] = useState<'ALL' | 'GROUP' | 'PCS' | ''>(() => routeTargetMacs.length ? 'PCS' : '')
  const [groupId, setGroupId] = useState<number | ''>('')
  const [selectedTargetMacs, setSelectedTargetMacs] = useState<Set<string>>(() => new Set(routeTargetMacs))
  const [targetSearch, setTargetSearch] = useState('')
  const [waveSize, setWaveSize] = useState(5)
  const [canaryOnly, setCanaryOnly] = useState(false)
  const [deploymentAcknowledged, setDeploymentAcknowledged] = useState(false)
  const [targetVersion, setTargetVersion] = useState('')
  const [sourceMode, setSourceMode] = useState<'SERVER_ONLY' | 'HYBRID_PEER'>('HYBRID_PEER')
  const [updatePackageType, setUpdatePackageType] = useState<'JAR' | 'FULL_BUNDLE'>('JAR')
  const [rampUpFactor, setRampUpFactor] = useState(1.8)
  const [peerSourceMac, setPeerSourceMac] = useState('')
  const [peerTargetMacs, setPeerTargetMacs] = useState<Set<string>>(new Set())
  const [peerSourcePath, setPeerSourcePath] = useState('')
  const [peerTargetPath, setPeerTargetPath] = useState('')
  const [peerOverwriteExisting, setPeerOverwriteExisting] = useState(false)
  const [peerAcknowledged, setPeerAcknowledged] = useState(false)
  const [peerTargetSearch, setPeerTargetSearch] = useState('')
  const [selectedJobId, setSelectedJobId] = useState<number | null>(null)
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null)

  const classroomsQuery = useQuery({
    queryKey: ['classrooms'],
    queryFn: getClassrooms,
    staleTime: 60_000,
    enabled: canUpload,
  })
  const pcsQuery = useQuery({
    queryKey: ['pcs'],
    queryFn: getPcs,
    staleTime: 30_000,
    enabled: canUpload || canPeerTransfer,
  })
  const eligibleTargetPcs = useMemo(() => (pcsQuery.data || []).filter((pc) => pc.online && !pc.brokenNote), [pcsQuery.data])
  const filteredTargetPcs = useMemo(() => {
    const query = targetSearch.trim().toLocaleLowerCase('ko-KR')
    if (!query) return eligibleTargetPcs
    return eligibleTargetPcs.filter((pc) => [pc.groupName, pc.pcName, pc.ipAddress, pc.macAddress].some((value) => value?.toLocaleLowerCase('ko-KR').includes(query)))
  }, [eligibleTargetPcs, targetSearch])
  const peerSourcePc = eligibleTargetPcs.find((pc) => pc.macAddress === peerSourceMac)
  const filteredPeerTargets = useMemo(() => {
    const query = peerTargetSearch.trim().toLocaleLowerCase('ko-KR')
    return eligibleTargetPcs.filter((pc) => pc.macAddress !== peerSourceMac && (
      !query || [pc.groupName, pc.pcName, pc.ipAddress, pc.macAddress].some((value) => value?.toLocaleLowerCase('ko-KR').includes(query))
    ))
  }, [eligibleTargetPcs, peerSourceMac, peerTargetSearch])
  const jobsQuery = useQuery({
    queryKey: ['file-distribution', 'jobs'],
    queryFn: getRecentDistributionJobs,
    refetchInterval: 10_000,
    enabled: allowed,
  })
  const jobs = useMemo(() => normalizeRecentJobs(jobsQuery.data), [jobsQuery.data])

  useEffect(() => {
    if (!jobs.length) {
      setSelectedJobId(null)
      return
    }
    if (selectedJobId == null || !jobs.some((job) => job.jobId === selectedJobId)) {
      setSelectedJobId(jobs[0].jobId)
    }
  }, [jobs, selectedJobId])

  const statusQuery = useQuery({
    queryKey: ['file-distribution', 'job', selectedJobId],
    queryFn: () => getDistributionJob(selectedJobId as number),
    enabled: allowed && selectedJobId != null,
    refetchInterval: 5_000,
  })
  const eventsQuery = useQuery({
    queryKey: ['file-distribution', 'events', selectedJobId],
    queryFn: () => getDistributionJobEvents(selectedJobId as number),
    enabled: allowed && selectedJobId != null,
    refetchInterval: 5_000,
  })
  const jobStatus = useMemo(() => normalizeJobStatus(statusQuery.data), [statusQuery.data])
  const waves = useMemo(() => normalizeWaves(jobStatus), [jobStatus])
  const events = useMemo(() => normalizeEvents(eventsQuery.data), [eventsQuery.data])

  const invalidateJobs = async (jobId?: number) => {
    await queryClient.invalidateQueries({ queryKey: ['file-distribution', 'jobs'] })
    if (jobId != null) {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['file-distribution', 'job', jobId] }),
        queryClient.invalidateQueries({ queryKey: ['file-distribution', 'events', jobId] }),
      ])
    }
  }

  const uploadMutation = useMutation({
    mutationFn: async ({ file, id }: { file: File; id: string }) => {
      if (!token) throw new Error('로그인 정보가 만료되었습니다.')
      return uploadAndAssembleFile({ file, token, uploadId: id, onProgress: setUploadProgress })
    },
    onSuccess: (artifact) => {
      setUploadedArtifact(artifact)
      if (uploadFingerprint) {
        try { clearUploadSession(uploadFingerprint, localStorage) } catch { /* Storage can be unavailable in private mode. */ }
      }
      const resumed = artifact.resumedChunks ? ` 이어받은 청크 ${artifact.resumedChunks}개를 확인했습니다.` : ''
      const deduplicated = artifact.deduplicated ? ' 서버의 동일 파일을 재사용했습니다.' : ''
      showToast(`파일 업로드와 조립이 완료되었습니다.${resumed}${deduplicated}`)
    },
    onError: (error) => {
      setResumeAvailable(true)
      showToast(mutationMessage(error, '파일을 업로드하지 못했습니다.'), 'error')
    },
  })

  const createMutation = useMutation({
    mutationFn: createDistributionJob,
    onSuccess: async (created) => {
      setSelectedJobId(created.jobId)
      setPendingAction(null)
      setUploadedArtifact(null)
      setDeploymentAcknowledged(false)
      await invalidateJobs(created.jobId)
      showToast(`배포 작업 #${created.jobId}을 생성했습니다.`)
    },
    onError: (error) => showToast(mutationMessage(error, '배포 작업을 생성하지 못했습니다.'), 'error'),
  })
  const peerCreateMutation = useMutation({
    mutationFn: createPeerTransfer,
    onSuccess: async (created) => {
      setSelectedJobId(created.jobId)
      setPendingAction(null)
      setPeerAcknowledged(false)
      await invalidateJobs(created.jobId)
      showToast(`PC 직접 전송 작업 #${created.jobId}을 생성했습니다.`)
    },
    onError: (error) => showToast(mutationMessage(error, 'PC 직접 전송 작업을 생성하지 못했습니다.'), 'error'),
  })
  const cancelMutation = useMutation({
    mutationFn: ({ jobId, peer }: { jobId: number; peer: boolean }) => peer ? cancelPeerTransfer(jobId) : cancelDistributionJob(jobId),
    onSuccess: async (result) => {
      setPendingAction(null)
      await invalidateJobs(result.jobId)
      showToast(`배포 작업 #${result.jobId}을 취소했습니다.`)
    },
    onError: (error) => showToast(mutationMessage(error, '배포 작업을 취소하지 못했습니다.'), 'error'),
  })
  const retryMutation = useMutation({
    mutationFn: ({ jobId, peer }: { jobId: number; peer: boolean }) => peer ? retryFailedPeerTransfer(jobId) : retryFailedDistribution(jobId),
    onSuccess: async (result) => {
      setPendingAction(null)
      await invalidateJobs(result.jobId)
      showToast(`배포 작업 #${result.jobId}의 실패 대상을 재시도했습니다.`)
    },
    onError: (error) => showToast(mutationMessage(error, '실패 대상을 재시도하지 못했습니다.'), 'error'),
  })

  const submitPeerTransfer = (event: FormEvent) => {
    event.preventDefault()
    if (!peerSourceMac || !peerSourcePc) return showToast('온라인 원본 PC를 선택하세요.', 'error')
    if (!peerTargetMacs.size) return showToast('대상 PC를 한 대 이상 선택하세요.', 'error')
    if (!peerSourcePath.trim() || !peerTargetPath.trim()) return showToast('원본 경로와 대상 경로를 모두 입력하세요.', 'error')
    if (!/^[A-Za-z]:\\/.test(peerSourcePath.trim()) || !/^[A-Za-z]:\\/.test(peerTargetPath.trim())) {
      return showToast('경로는 C:\\폴더처럼 드라이브를 포함한 절대 경로로 입력하세요.', 'error')
    }
    if (!peerAcknowledged) return showToast('전송 방식과 덮어쓰기 정책을 확인하세요.', 'error')
    setPendingAction({
      type: 'peerTransfer',
      input: {
        sourceMac: peerSourceMac,
        targetMacs: [...peerTargetMacs],
        sourcePath: peerSourcePath.trim(),
        targetPath: peerTargetPath.trim(),
        overwriteExisting: peerOverwriteExisting,
      },
    })
  }

  const chooseFile = (file: File | null) => {
    setUploadedArtifact(null)
    setUploadProgress(null)
    setSelectedFile(file)
    setDeploymentAcknowledged(false)
    if (!file) {
      setUploadId('')
      setUploadFingerprint('')
      setResumeAvailable(false)
      return
    }
    const lowerName = file.name.toLocaleLowerCase('en-US')
    if (!lowerName.endsWith('.jar') && !lowerName.endsWith('.zip')) {
      showToast('에이전트 JAR 또는 PCAgent 전체 업데이트 ZIP만 선택할 수 있습니다.', 'error')
      setSelectedFile(null)
      return
    }
    const inferredPackageType = lowerName.endsWith('.zip') ? 'FULL_BUNDLE' : 'JAR'
    setUpdatePackageType(inferredPackageType)
    if (inferredPackageType === 'FULL_BUNDLE') setSourceMode('SERVER_ONLY')
    const totalChunks = totalChunksFor(file.size)
    if (!totalChunks || totalChunks > 200_000) {
      showToast(totalChunks ? '파일 청크 수가 서버 한도를 초과합니다.' : '빈 파일은 업로드할 수 없습니다.', 'error')
      setSelectedFile(null)
      return
    }
    const fingerprint = fileFingerprint(file)
    setUploadFingerprint(fingerprint)
    try {
      const resolved = resolveUploadSession(file, localStorage)
      setUploadId(resolved.session.uploadId)
      setResumeAvailable(resolved.resumed)
    } catch {
      setUploadId(createUploadId())
      setResumeAvailable(false)
    }
    const match = file.name.match(/(?:^|[-_])v?(\d+(?:\.\d+){1,3})(?:[-_.]|$)/i)
    if (match) setTargetVersion(match[1])
  }

  const submitJob = (event: FormEvent) => {
    event.preventDefault()
    if (!uploadedArtifact) return showToast('먼저 파일 업로드를 완료하세요.', 'error')
    if (targetMode === '') return showToast('배포 대상을 선택하세요.', 'error')
    if (targetMode === 'GROUP' && classroomsQuery.isError) return showToast('강의실 목록을 다시 불러온 뒤 배포하세요.', 'error')
    if (targetMode === 'GROUP' && groupId === '') return showToast('대상 강의실을 선택하세요.', 'error')
    if (targetMode === 'PCS' && pcsQuery.isError) return showToast('PC 목록을 다시 불러온 뒤 배포하세요.', 'error')
    if (targetMode === 'PCS' && !selectedTargetMacs.size) return showToast('배포할 온라인 PC를 선택하세요.', 'error')
    if (!deploymentAcknowledged) return showToast('무중단 업데이트 동작을 확인하세요.', 'error')
    if (updatePackageType === 'FULL_BUNDLE' && !uploadedArtifact.originalFilename.toLocaleLowerCase('en-US').endsWith('.zip')) {
      return showToast('전체 번들 업데이트에는 ZIP 파일이 필요합니다.', 'error')
    }
    if (!Number.isInteger(waveSize) || waveSize < 1) return showToast('초기 웨이브 크기를 확인하세요.', 'error')
    if (!Number.isFinite(rampUpFactor) || rampUpFactor < 1 || rampUpFactor > 3) {
      return showToast('웨이브 확장률은 1.0에서 3.0 사이여야 합니다.', 'error')
    }
    setPendingAction({
      type: 'deploy',
      input: {
        uploadId: uploadedArtifact.uploadId,
        originalFilename: uploadedArtifact.originalFilename,
        totalChunks: uploadedArtifact.totalChunks,
        artifactId: uploadedArtifact.id,
        groupId: targetMode === 'GROUP' ? groupId as number : undefined,
        targetMacs: targetMode === 'PCS' ? [...selectedTargetMacs] : undefined,
        waveSize,
        canaryOnly,
        targetVersion: targetVersion.trim() || undefined,
        sourceMode: updatePackageType === 'FULL_BUNDLE' ? 'SERVER_ONLY' : sourceMode,
        rampUpFactor,
        updatePackageType,
      },
    })
  }

  const refresh = () => {
    void jobsQuery.refetch()
    void classroomsQuery.refetch()
    void pcsQuery.refetch()
    if (selectedJobId != null) {
      void statusQuery.refetch()
      void eventsQuery.refetch()
    }
  }

  const actionBusy = createMutation.isPending || peerCreateMutation.isPending || cancelMutation.isPending || retryMutation.isPending
  const selectedSummary = jobs.find((job) => job.jobId === selectedJobId)
  const selectedIsPeer = (jobStatus?.updatePackageType || selectedSummary?.updatePackageType) === 'PC_COPY'
  const progressValue = uploadPercent(uploadProgress)

  if (!allowed) {
    return <section className="content-section"><PageError message="파일 배포 기능 권한이 없습니다." /></section>
  }
  if (jobsQuery.isLoading && !jobsQuery.data && classroomsQuery.isLoading) {
    return <section className="content-section"><PageLoading label="파일 배포 정보를 불러오는 중입니다." /></section>
  }

  return (
    <section className="content-section">
      <header className="page-header">
        <div><h1>파일 배포</h1><p>기존 에이전트로 PC 간 자료 복사 또는 에이전트 패키지 배포</p></div>
        <button className="icon-button bordered" onClick={refresh} disabled={jobsQuery.isFetching || statusQuery.isFetching} aria-label="새로고침" title="새로고침">
          <RefreshCw size={17} className={jobsQuery.isFetching || statusQuery.isFetching ? 'spin-icon' : ''} />
        </button>
      </header>

      {jobsQuery.isError && jobsQuery.data && <div className="form-error" role="alert">최근 작업 갱신에 실패해 마지막 데이터를 표시하고 있습니다.</div>}

      {canPeerTransfer && <form style={{ ...styles.panel, marginBottom: 18 }} onSubmit={submitPeerTransfer}>
        <header style={styles.panelHeader}>
          <h2 style={styles.panelTitle}><ArrowRightLeft size={17} />PC 간 직접 자료 전송</h2>
          <small style={styles.subtle}>에이전트 업데이트 없음 · SHA-256 검증</small>
        </header>
        {pcsQuery.isError && <div className="form-error" role="alert">PC 목록을 불러오지 못했습니다. 새로고침 후 다시 시도하세요.</div>}
        <div style={styles.formGrid}>
          <label style={styles.fieldWide}>
            <span style={styles.fieldLabel}>원본 PC</span>
            <select style={styles.input} value={peerSourceMac} disabled={pcsQuery.isLoading || pcsQuery.isError} onChange={(event) => {
              const next = event.target.value
              setPeerSourceMac(next)
              setPeerTargetMacs((current) => { const updated = new Set(current); updated.delete(next); return updated })
              setPeerAcknowledged(false)
            }}>
              <option value="">온라인 원본 PC를 선택하세요</option>
              {eligibleTargetPcs.map((pc) => <option key={pc.macAddress} value={pc.macAddress}>{pc.groupName || '-'} · {pc.pcName || pc.ipAddress || pc.macAddress} · {pc.ipAddress || '-'}</option>)}
            </select>
          </label>
          <label style={styles.field}>
            <span style={styles.fieldLabel}>원본 경로</span>
            <input style={styles.input} value={peerSourcePath} maxLength={1000} onChange={(event) => { setPeerSourcePath(event.target.value); setPeerAcknowledged(false) }} placeholder="예: C:\\Rocky10" />
          </label>
          <label style={styles.field}>
            <span style={styles.fieldLabel}>대상 경로</span>
            <input style={styles.input} value={peerTargetPath} maxLength={1000} onChange={(event) => { setPeerTargetPath(event.target.value); setPeerAcknowledged(false) }} placeholder="예: C:\\Rocky10" />
          </label>
          <div style={styles.fieldWide}>
            <span style={styles.fieldLabel}>대상 PC ({peerTargetMacs.size}대)</span>
            <input style={styles.input} value={peerTargetSearch} onChange={(event) => setPeerTargetSearch(event.target.value)} placeholder="강의실, PC 이름, IP, MAC 검색" />
            <div style={{ display: 'flex', gap: 8 }}>
              <button type="button" className="button secondary" disabled={!peerSourceMac} onClick={() => setPeerTargetMacs((current) => new Set([...current, ...filteredPeerTargets.map((pc) => pc.macAddress)]))}>검색 결과 선택</button>
              <button type="button" className="button secondary" onClick={() => setPeerTargetMacs(new Set())}>선택 해제</button>
            </div>
            <div style={styles.targetList}>{!peerSourceMac ? <p style={{ ...styles.subtle, padding: 10 }}>원본 PC를 먼저 선택하세요.</p> : filteredPeerTargets.length ? filteredPeerTargets.map((pc, index) => <label key={pc.macAddress} style={{ ...styles.targetRow, borderBottom: index === filteredPeerTargets.length - 1 ? 0 : styles.targetRow.borderBottom }}>
              <input type="checkbox" checked={peerTargetMacs.has(pc.macAddress)} onChange={() => setPeerTargetMacs((current) => { const next = new Set(current); if (next.has(pc.macAddress)) next.delete(pc.macAddress); else next.add(pc.macAddress); return next })} />
              <span><strong>{pc.groupName || '-'}</strong> · {pc.pcName || pc.ipAddress || pc.macAddress} <small style={styles.subtle}>{pc.ipAddress || '-'} · {pc.macAddress}</small></span>
            </label>) : <p style={{ ...styles.subtle, padding: 10 }}>조건에 맞는 대상 PC가 없습니다.</p>}</div>
          </div>
          <label style={{ ...styles.checkRow, gridColumn: '1 / -1' }}><input type="checkbox" checked={peerOverwriteExisting} onChange={(event) => { setPeerOverwriteExisting(event.target.checked); setPeerAcknowledged(false) }} />대상에 내용이 다른 파일이 있으면 덮어쓰기</label>
          <label style={{ ...styles.checkRow, gridColumn: '1 / -1', alignItems: 'flex-start' }}><input type="checkbox" checked={peerAcknowledged} onChange={(event) => setPeerAcknowledged(event.target.checked)} />원본 PC는 읽기 전용으로 사용하고, 대상 PC에서 파일별 SHA-256 검증 후 반영하며, 임시 전송 서버와 방화벽 규칙은 작업 종료 시 자동 제거됨을 확인했습니다.</label>
        </div>
        <div style={styles.actions}><button className="button primary" disabled={!peerSourceMac || !peerTargetMacs.size || !peerSourcePath.trim() || !peerTargetPath.trim() || !peerAcknowledged || actionBusy}><ArrowRightLeft size={16} />직접 전송 시작</button></div>
      </form>}

      {canUpload && <div style={styles.twoColumn}>
        <section style={styles.panel}>
          <header style={styles.panelHeader}><h2 style={styles.panelTitle}><UploadCloud size={17} />파일 업로드</h2><small style={styles.subtle}>청크 {formatBytes(FILE_CHUNK_SIZE)}</small></header>
          <label style={styles.uploadBox}>
            <FileArchive size={28} color="var(--blue)" />
            <strong style={styles.uploadName}>{selectedFile?.name || '배포할 파일 선택'}</strong>
            <span style={styles.subtle}>{selectedFile ? `${formatBytes(selectedFile.size)} · ${totalChunksFor(selectedFile.size)}개 청크` : '파일을 선택하면 SHA-256 검증 후 전송됩니다.'}</span>
            <input type="file" accept=".jar,.zip" hidden disabled={uploadMutation.isPending} onChange={(event) => chooseFile(event.target.files?.[0] || null)} />
            <span className="button secondary">파일 선택</span>
          </label>
          {(uploadProgress || resumeAvailable) && <div style={{ marginTop: 12 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 10, marginBottom: 6, fontSize: 10, color: 'var(--muted)' }}>
              <span>{uploadPhaseLabel(uploadProgress)}{resumeAvailable && !uploadProgress ? ' · 이어올리기 가능' : ''}</span><strong>{progressValue}%</strong>
            </div>
            <div style={styles.progressTrack}><div style={{ ...styles.progressBar, width: `${progressValue}%` }} /></div>
            {uploadProgress?.resumedChunks ? <p style={{ ...styles.subtle, marginTop: 6 }}>서버에 있던 청크 {uploadProgress.resumedChunks}개를 재사용했습니다.</p> : null}
          </div>}
          <div style={styles.actions}>
            <button className="button primary" disabled={!selectedFile || !uploadId || uploadMutation.isPending} onClick={() => selectedFile && uploadMutation.mutate({ file: selectedFile, id: uploadId })}>
              <UploadCloud size={16} />{uploadMutation.isPending ? '처리 중' : resumeAvailable ? '이어올리기' : '업로드'}
            </button>
          </div>
          {uploadedArtifact && <div style={styles.artifact}>
            <CheckCircle2 size={22} />
            <div style={styles.artifactText}>
              <strong style={styles.artifactName}>{uploadedArtifact.originalFilename}</strong>
              <small style={styles.artifactMeta}>Artifact #{uploadedArtifact.id} · {formatBytes(uploadedArtifact.sizeBytes)} · SHA-256 {uploadedArtifact.sha256.slice(0, 12)}...</small>
            </div>
          </div>}
        </section>

        <form style={styles.panel} onSubmit={submitJob}>
          <header style={styles.panelHeader}><h2 style={styles.panelTitle}><ShieldCheck size={17} />배포 설정</h2><small style={styles.subtle}>{uploadedArtifact ? `Artifact #${uploadedArtifact.id}` : '파일 업로드 필요'}</small></header>
          {targetMode === 'GROUP' && classroomsQuery.isError && <div className="form-error" role="alert">강의실 목록을 불러오지 못했습니다. 새로고침 후 대상을 다시 선택하세요.</div>}
          {targetMode === 'PCS' && pcsQuery.isError && <div className="form-error" role="alert">PC 목록을 불러오지 못했습니다. 새로고침 후 대상을 다시 선택하세요.</div>}
          <div style={styles.formGrid}>
            <label style={styles.fieldWide}><span style={styles.fieldLabel}>대상 범위</span><select style={styles.input} value={targetMode} onChange={(event) => setTargetMode(event.target.value as typeof targetMode)}>
              <option value="">대상을 선택하세요</option><option value="ALL">전체 온라인 PC</option><option value="GROUP">강의실</option><option value="PCS">개별 PC</option>
            </select></label>
            {targetMode === 'GROUP' && <label style={styles.fieldWide}><span style={styles.fieldLabel}>대상 강의실</span><select style={styles.input} value={groupId} disabled={classroomsQuery.isLoading || classroomsQuery.isError} onChange={(event) => setGroupId(event.target.value ? Number(event.target.value) : '')}>
              <option value="">강의실을 선택하세요</option>{(classroomsQuery.data || []).map((room) => <option key={room.groupId} value={room.groupId}>{room.groupName} ({room.onlinePcs}대 온라인)</option>)}
            </select></label>}
            {targetMode === 'PCS' && <div style={styles.fieldWide}>
              <span style={styles.fieldLabel}>온라인 PC 선택 ({selectedTargetMacs.size}대)</span>
              <input style={styles.input} value={targetSearch} onChange={(event) => setTargetSearch(event.target.value)} placeholder="강의실, PC 이름, IP, MAC 검색" />
              <div style={{ display: 'flex', gap: 8 }}><button type="button" className="button secondary" onClick={() => setSelectedTargetMacs((current) => new Set([...current, ...filteredTargetPcs.map((pc) => pc.macAddress)]))}>검색 결과 선택</button><button type="button" className="button secondary" onClick={() => setSelectedTargetMacs(new Set())}>선택 해제</button></div>
              <div style={styles.targetList}>{filteredTargetPcs.length ? filteredTargetPcs.map((pc, index) => <label key={pc.macAddress} style={{ ...styles.targetRow, borderBottom: index === filteredTargetPcs.length - 1 ? 0 : styles.targetRow.borderBottom }}><input type="checkbox" checked={selectedTargetMacs.has(pc.macAddress)} onChange={() => setSelectedTargetMacs((current) => { const next = new Set(current); if (next.has(pc.macAddress)) next.delete(pc.macAddress); else next.add(pc.macAddress); return next })} /><span><strong>{pc.groupName || '-'}</strong> · {pc.pcName || pc.ipAddress || pc.macAddress} <small style={styles.subtle}>{pc.ipAddress || '-'} · {pc.macAddress}</small></span></label>) : <p style={{ ...styles.subtle, padding: 10 }}>조건에 맞는 온라인 PC가 없습니다.</p>}</div>
            </div>}
            <label style={styles.field}><span style={styles.fieldLabel}>초기 웨이브</span><input style={styles.input} type="number" min="1" max="500" value={waveSize} onChange={(event) => setWaveSize(Number(event.target.value))} /></label>
            <label style={styles.field}><span style={styles.fieldLabel}>웨이브 확장률</span><input style={styles.input} type="number" min="1" max="3" step="0.1" value={rampUpFactor} onChange={(event) => setRampUpFactor(Number(event.target.value))} /></label>
            <label style={styles.field}><span style={styles.fieldLabel}>업데이트 범위</span><select style={styles.input} value={updatePackageType} onChange={(event) => { const next = event.target.value as typeof updatePackageType; setUpdatePackageType(next); if (next === 'FULL_BUNDLE') setSourceMode('SERVER_ONLY'); setDeploymentAcknowledged(false) }}><option value="JAR">에이전트 JAR</option><option value="FULL_BUNDLE">전체 번들</option></select></label>
            <label style={styles.field}><span style={styles.fieldLabel}>배포 방식</span><select style={styles.input} value={updatePackageType === 'FULL_BUNDLE' ? 'SERVER_ONLY' : sourceMode} disabled={updatePackageType === 'FULL_BUNDLE'} onChange={(event) => setSourceMode(event.target.value as typeof sourceMode)}><option value="HYBRID_PEER">하이브리드 피어</option><option value="SERVER_ONLY">서버 직접</option></select></label>
            <label style={styles.field}><span style={styles.fieldLabel}>대상 버전</span><input style={styles.input} value={targetVersion} maxLength={100} onChange={(event) => setTargetVersion(event.target.value)} placeholder="예: 1.1.13" /></label>
            <label style={{ ...styles.checkRow, gridColumn: '1 / -1' }}><input type="checkbox" checked={canaryOnly} onChange={(event) => setCanaryOnly(event.target.checked)} />첫 웨이브만 카나리 배포</label>
            <label style={{ ...styles.checkRow, gridColumn: '1 / -1', alignItems: 'flex-start' }}><input type="checkbox" checked={deploymentAcknowledged} onChange={(event) => setDeploymentAcknowledged(event.target.checked)} />{updatePackageType === 'FULL_BUNDLE' ? 'JRE, VNC, 워치독과 JAR을 함께 교체하며 재부팅하지 않고, 정상 heartbeat가 없으면 전체 구성을 롤백함을 확인했습니다.' : 'JAR만 교체하며 재부팅하지 않고, 새 에이전트가 정상화되지 않으면 워치독이 이전 JAR로 롤백함을 확인했습니다.'}</label>
          </div>
          <div style={styles.actions}><button className="button primary" disabled={!uploadedArtifact || targetMode === '' || (targetMode === 'GROUP' && (groupId === '' || classroomsQuery.isError)) || (targetMode === 'PCS' && (!selectedTargetMacs.size || pcsQuery.isError)) || !deploymentAcknowledged || actionBusy}><UploadCloud size={16} />배포 시작</button></div>
        </form>
      </div>}

      <div className="section-toolbar">
        <div><h2>최근 배포 작업</h2><p>최대 50개 · 10초마다 갱신</p></div>
      </div>
      {!jobs.length && jobsQuery.isError ? <PageError message={mutationMessage(jobsQuery.error, '최근 배포 작업을 불러오지 못했습니다.')} onRetry={() => void jobsQuery.refetch()} /> : !jobs.length ? <EmptyState message="최근 배포 작업이 없습니다." /> : (
        <div className="table-scroll"><table className="data-table"><thead><tr><th>작업</th><th>파일</th><th>범위</th><th>버전</th><th>방식</th><th>상태</th><th>생성 시각</th><th>완료 시각</th></tr></thead><tbody>
          {jobs.map((job) => <tr key={job.jobId} tabIndex={0} onClick={() => setSelectedJobId(job.jobId)} onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); setSelectedJobId(job.jobId) } }} style={job.jobId === selectedJobId ? { background: '#eef4fb' } : undefined} aria-selected={job.jobId === selectedJobId}>
            <td><strong>#{job.jobId}</strong></td><td>{job.updatePackageType === 'PC_COPY' ? job.sourcePath || job.originalFilename : job.originalFilename}</td><td>{job.updatePackageType === 'PC_COPY' ? 'PC 자료 복사' : job.updatePackageType === 'FULL_BUNDLE' ? '전체 번들' : 'JAR'}</td><td>{job.targetVersion || '-'}</td><td>{sourceModeLabel(job.sourceMode)}</td><td><span className={`status-badge ${jobStatusTone(job.status)}`}>{jobStatusLabel(job.status)}</span></td><td>{formatDateTime(job.createdAt)}</td><td>{formatDateTime(job.completedAt || job.cancelledAt)}</td>
          </tr>)}
        </tbody></table></div>
      )}

      {selectedJobId != null && <section style={{ ...styles.panel, marginTop: 16 }}>
        <header style={styles.panelHeader}>
          <h2 style={styles.panelTitle}>작업 #{selectedJobId} 상세 <span className={`status-badge ${jobStatusTone(jobStatus?.status || selectedSummary?.status)}`}>{jobStatusLabel(jobStatus?.status || selectedSummary?.status)}</span></h2>
          <div style={{ display: 'flex', gap: 8 }}>
            {canControlJobs && <button className="button secondary" disabled={!canRetryJob(jobStatus) || actionBusy} onClick={() => setPendingAction({ type: 'retry', jobId: selectedJobId, peer: selectedIsPeer })}><RotateCw size={15} />실패 재시도</button>}
            {canControlJobs && <button className="button danger" disabled={!canCancelJob(jobStatus?.status) || actionBusy} onClick={() => setPendingAction({ type: 'cancel', jobId: selectedJobId, peer: selectedIsPeer })}><XCircle size={15} />작업 취소</button>}
          </div>
        </header>
        {statusQuery.isLoading && !statusQuery.data ? <PageLoading label="작업 상태를 불러오는 중입니다." /> : statusQuery.isError && !statusQuery.data ? <PageError message={mutationMessage(statusQuery.error, '작업 상태를 불러오지 못했습니다.')} onRetry={() => void statusQuery.refetch()} /> : jobStatus && <>
          <div style={styles.detailGrid}>
            <div style={styles.metric}><span style={styles.metricLabel}>전체 대상</span><strong style={styles.metricValue}>{jobStatus.totalAgents}대</strong></div>
            <div style={styles.metric}><span style={styles.metricLabel}>완료</span><strong style={styles.metricValue}>{jobStatus.completed}대</strong></div>
            <div style={styles.metric}><span style={styles.metricLabel}>진행 중</span><strong style={styles.metricValue}>{jobStatus.inProgress}대</strong></div>
            <div style={styles.metric}><span style={styles.metricLabel}>실패</span><strong style={styles.metricValue}>{jobStatus.failed}대</strong></div>
            <div style={styles.metric}><span style={styles.metricLabel}>진행률</span><strong style={styles.metricValue}>{Math.round(jobStatus.progress * 100)}%</strong></div>
          </div>
          <div style={styles.progressTrack}><div style={{ ...styles.progressBar, width: `${Math.round(jobStatus.progress * 100)}%`, background: jobStatus.failed ? 'var(--orange)' : 'var(--green)' }} /></div>
          {selectedIsPeer && <div style={{ marginTop: 14 }}>
            <p style={{ ...styles.subtle, marginBottom: 8 }}><strong>원본</strong> {jobStatus.sourcePath || '-'} → <strong>대상</strong> {jobStatus.targetPath || '-'} · {jobStatus.overwriteExisting ? '다른 파일 덮어쓰기' : '다른 파일 발견 시 중단'}</p>
            <div className="table-scroll"><table className="data-table"><thead><tr><th>대상 MAC</th><th>상태</th><th>검증 파일</th><th>검증 용량</th><th>오류</th></tr></thead><tbody>
              {(jobStatus.targets || []).map((target) => <tr key={target.agentId} style={{ cursor: 'default' }}><td>{target.agentId}</td><td><span className={`status-badge ${jobStatusTone(target.status)}`}>{jobStatusLabel(target.status)}</span></td><td>{target.verifiedFiles.toLocaleString('ko-KR')}</td><td>{formatBytes(target.verifiedBytes)}</td><td>{target.errorMessage || '-'}</td></tr>)}
            </tbody></table></div>
          </div>}
          <div style={styles.splitDetail}>
            <div><h3 style={{ ...styles.panelTitle, marginBottom: 10 }}>{selectedIsPeer ? '대상 집계' : '웨이브 현황'}</h3>{!waves.length ? <EmptyState message={selectedIsPeer ? '대상 정보가 없습니다.' : '웨이브 정보가 없습니다.'} /> : <div className="table-scroll"><table className="data-table"><thead><tr><th>{selectedIsPeer ? '묶음' : '웨이브'}</th><th>전체</th><th>대기</th><th>진행</th><th>완료</th><th>실패</th><th>취소</th></tr></thead><tbody>{waves.map((wave) => <tr key={wave.wave} style={{ cursor: 'default' }}><td><strong>{selectedIsPeer ? 1 : wave.wave + 1}</strong></td><td>{wave.total}</td><td>{wave.queued}</td><td>{wave.inProgress}</td><td>{wave.completed}</td><td>{wave.failed}</td><td>{wave.cancelled}</td></tr>)}</tbody></table></div>}</div>
            <div><h3 style={{ ...styles.panelTitle, marginBottom: 10 }}>최근 이벤트</h3>{eventsQuery.isError && !eventsQuery.data ? <PageError message="이벤트를 불러오지 못했습니다." onRetry={() => void eventsQuery.refetch()} /> : !events.length ? <EmptyState message="기록된 이벤트가 없습니다." /> : <div style={styles.eventList}>{events.slice(0, 30).map((event, index) => <article key={`${event.id}-${index}`} style={{ ...styles.event, borderBottom: index === Math.min(events.length, 30) - 1 ? 0 : styles.event.borderBottom }}><div style={styles.eventTop}><strong>{event.eventType}</strong><time>{formatDateTime(event.createdAt)}</time></div><p style={styles.eventDetail}>{formatEventDetail(event.detailJson)}</p></article>)}</div>}</div>
          </div>
        </>}
      </section>}

      <ConfirmDialog
        open={Boolean(pendingAction)}
        title={pendingAction?.type === 'deploy' ? '파일 배포 시작' : pendingAction?.type === 'peerTransfer' ? 'PC 직접 전송 시작' : pendingAction?.type === 'cancel' ? '배포 작업 취소' : '실패 대상 재시도'}
        description={pendingAction?.type === 'deploy'
          ? `${pendingAction.input.originalFilename} 파일을 ${pendingAction.input.targetMacs?.length ? `선택한 PC ${pendingAction.input.targetMacs.length}대` : pendingAction.input.groupId ? (classroomsQuery.data || []).find((room) => room.groupId === pendingAction.input.groupId)?.groupName || '선택한 강의실' : '전체 온라인 PC'}에 배포합니다. 초기 웨이브 ${pendingAction.input.waveSize}대로 시작하며 PC는 재부팅하지 않습니다.`
          : pendingAction?.type === 'peerTransfer'
            ? `${pendingAction.input.sourcePath} 자료를 선택한 PC ${pendingAction.input.targetMacs.length}대의 ${pendingAction.input.targetPath} 위치로 직접 전송합니다. 에이전트는 업데이트하지 않으며, ${pendingAction.input.overwriteExisting ? '내용이 다른 기존 파일은 덮어씁니다.' : '내용이 다른 기존 파일이 있으면 해당 PC 전송을 중단합니다.'}`
          : pendingAction?.type === 'cancel'
            ? `작업 #${pendingAction.jobId}과 진행 중인 ${pendingAction.peer ? 'PC 직접 전송 워커' : '웨이브'}를 취소합니다.`
            : pendingAction?.type === 'retry'
              ? `작업 #${pendingAction.jobId}의 실패한 PC를 다시 배포 대기열에 넣습니다.`
              : ''}
        confirmLabel={pendingAction?.type === 'deploy' ? '배포 시작' : pendingAction?.type === 'peerTransfer' ? '직접 전송 시작' : pendingAction?.type === 'cancel' ? '작업 취소' : '다시 시도'}
        danger={pendingAction?.type === 'cancel' || pendingAction?.type === 'deploy' || pendingAction?.type === 'peerTransfer'}
        busy={actionBusy}
        requiredText={pendingAction?.type === 'cancel' ? `JOB-${pendingAction.jobId}` : undefined}
        requiredTextLabel={pendingAction?.type === 'cancel' ? `확인을 위해 JOB-${pendingAction.jobId} 입력` : undefined}
        onCancel={() => { if (!actionBusy) setPendingAction(null) }}
        onConfirm={() => {
          if (!pendingAction || actionBusy) return
          if (pendingAction.type === 'deploy') createMutation.mutate(pendingAction.input)
          else if (pendingAction.type === 'peerTransfer') peerCreateMutation.mutate(pendingAction.input)
          else if (pendingAction.type === 'cancel') cancelMutation.mutate({ jobId: pendingAction.jobId, peer: pendingAction.peer })
          else retryMutation.mutate({ jobId: pendingAction.jobId, peer: pendingAction.peer })
        }}
      />
    </section>
  )
}
