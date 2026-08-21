import { useEffect, useMemo, useRef, useState } from 'react'
import { ArrowUp, FileArchive, FileCode2, FolderOpen, HardDrive, Plus, RefreshCw, Send, X } from 'lucide-react'
import { useAuth } from '../../auth/AuthProvider'
import { api } from '../../api/client'
import { useToast } from '../../components/ui/ToastProvider'
import { useDialogFocus } from '../../components/ui/useDialogFocus'
import type { PcStatus } from '../../types/dashboard'
import { uploadAndAssembleFile } from '../files/api'
import { clearUploadSession, formatBytes, resolveUploadSession } from '../files/model'
import type { FileUploadProgress } from '../files/types'
import { collectDownloadFilename, collectDownloadPath, isFinalCommandStatus } from '../commands/model'
import {
  createTransferArtifact,
  getOperationStatuses,
  joinRemotePath,
  listRemoteFiles,
  sendPcOperation,
  type RemoteFileListing,
  type OperationStatus,
} from './operations'
import './pcs.css'

type OperationTab = 'script' | 'transfer' | 'collect'

interface Props {
  targets: PcStatus[]
  onClose: () => void
}

function progressLabel(progress: FileUploadProgress | null) {
  if (!progress) return ''
  if (progress.phase === 'hashing') return `청크 해시 계산 ${progress.chunkIndex + 1}/${progress.totalChunks}`
  if (progress.phase === 'assembling') return '서버에서 파일 조립 중'
  if (progress.phase === 'complete') return '업로드 완료'
  return `업로드 ${Math.round((progress.uploadedBytes / Math.max(1, progress.totalBytes)) * 100)}%`
}

function FolderInput({ onFiles, disabled }: { onFiles: (files: File[]) => void; disabled: boolean }) {
  const ref = useRef<HTMLInputElement>(null)
  useEffect(() => {
    const input = ref.current
    if (input) input.setAttribute('webkitdirectory', '')
  }, [])
  return <label className="button secondary file-picker"><FolderOpen size={15} />폴더 선택<input ref={ref} type="file" multiple disabled={disabled} onChange={(event) => onFiles(Array.from(event.target.files || []))} /></label>
}

export function PcOperationsDialog({ targets, onClose }: Props) {
  const { token } = useAuth()
  const { showToast } = useToast()
  const onlineTargets = useMemo(() => targets.filter((pc) => pc.online && !pc.brokenNote), [targets])
  const [tab, setTab] = useState<OperationTab>('script')
  const [busy, setBusy] = useState(false)
  const [progress, setProgress] = useState<FileUploadProgress | null>(null)
  const [scriptFile, setScriptFile] = useState<File | null>(null)
  const [args, setArgs] = useState('')
  const [runMode, setRunMode] = useState<'FOREGROUND' | 'BACKGROUND'>('FOREGROUND')
  const [windowMode, setWindowMode] = useState<'HIDDEN' | 'VISIBLE'>('HIDDEN')
  const [transferFiles, setTransferFiles] = useState<File[]>([])
  const [destinationMode, setDestinationMode] = useState<'DOWNLOADS' | 'DESKTOP' | 'CUSTOM'>('DOWNLOADS')
  const [destinationPath, setDestinationPath] = useState('')
  const [runAfterTransfer, setRunAfterTransfer] = useState(false)
  const [browseMac, setBrowseMac] = useState(onlineTargets[0]?.macAddress || '')
  const [pathInput, setPathInput] = useState('')
  const [listing, setListing] = useState<RemoteFileListing | null>(null)
  const [selectedPaths, setSelectedPaths] = useState<string[]>([])
  const [outputName, setOutputName] = useState('collected-bundle.zip')
  const [usbOnly, setUsbOnly] = useState(false)
  const [collectionTaskIds, setCollectionTaskIds] = useState<number[]>([])
  const [collectionTasks, setCollectionTasks] = useState<OperationStatus[]>([])
  const [collectionTracking, setCollectionTracking] = useState(false)
  const [collectionError, setCollectionError] = useState('')
  const dialogRef = useDialogFocus<HTMLElement>(onClose, busy)

  useEffect(() => {
    if (!collectionTracking || !collectionTaskIds.length) return
    let disposed = false
    const refreshStatuses = async () => {
      try {
        const statuses = await getOperationStatuses(collectionTaskIds)
        if (disposed) return
        setCollectionTasks(statuses)
        setCollectionError('')
        if (statuses.length === collectionTaskIds.length && statuses.every((task) => isFinalCommandStatus(task.status))) {
          setCollectionTracking(false)
        }
      } catch (error) {
        if (!disposed) setCollectionError(error instanceof Error ? error.message : '수집 상태를 불러오지 못했습니다.')
      }
    }
    void refreshStatuses()
    const timer = window.setInterval(() => { void refreshStatuses() }, 2_000)
    return () => { disposed = true; window.clearInterval(timer) }
  }, [collectionTaskIds, collectionTracking])

  useEffect(() => {
    if (tab !== 'collect' || busy || !browseMac || listing?.currentPath !== '') return
    const timer = window.setInterval(() => {
      const pc = onlineTargets.find((candidate) => candidate.macAddress === browseMac)
      if (!pc) return
      void listRemoteFiles(pc, '').then(setListing).catch(() => undefined)
    }, 4_000)
    return () => window.clearInterval(timer)
  }, [browseMac, busy, listing?.currentPath, onlineTargets, tab])

  const upload = async (file: File) => {
    if (!token) throw new Error('로그인이 만료되었습니다.')
    const resolved = resolveUploadSession(file, localStorage)
    const artifact = await uploadAndAssembleFile({ file, token, uploadId: resolved.session.uploadId, onProgress: setProgress })
    clearUploadSession(resolved.session.fingerprint, localStorage)
    return artifact
  }

  const finishSummary = (label: string, summary: Awaited<ReturnType<typeof sendPcOperation>>) => {
    if (summary.failed) showToast(`${label}: ${summary.queued}대 전송, ${summary.failed}대 실패했습니다.`, 'error')
    else if (!summary.queued && summary.duplicateSkipped) showToast(`${label} 작업이 이미 진행 중입니다.`, 'error')
    else showToast(`${label} 작업을 ${summary.queued}대에 전송했습니다.`)
  }

  const runScript = async () => {
    if (!scriptFile || !onlineTargets.length) return
    setBusy(true)
    setProgress(null)
    try {
      const artifact = await upload(scriptFile)
      const summary = await sendPcOperation(onlineTargets, {
        type: 'RUN_PS1',
        scriptUrl: artifact.downloadUrl,
        payload: { runMode, windowMode, ...(args.trim() ? { args: args.trim() } : {}) },
      })
      finishSummary('PowerShell', summary)
    } catch (error) {
      showToast(error instanceof Error ? error.message : 'PowerShell 작업을 전송하지 못했습니다.', 'error')
    } finally {
      setBusy(false)
    }
  }

  const transfer = async () => {
    if (!transferFiles.length || !onlineTargets.length) return
    if (destinationMode === 'CUSTOM' && !destinationPath.trim()) {
      showToast('사용자 지정 대상 경로를 입력하세요.', 'error')
      return
    }
    setBusy(true)
    setProgress(null)
    try {
      const packaged = await createTransferArtifact(transferFiles)
      const artifact = await upload(packaged.file)
      const summary = await sendPcOperation(onlineTargets, {
        type: 'RUN_PS1',
        scriptUrl: artifact.downloadUrl,
        payload: {
          runMode, windowMode, transferMode: true, sourceType: packaged.sourceType,
          displayName: packaged.file.name, destinationMode,
          ...(destinationMode === 'CUSTOM' ? { destinationPath: destinationPath.trim() } : {}),
          runAfterTransfer, zipped: packaged.zipped,
        },
      })
      finishSummary('파일 전송', summary)
    } catch (error) {
      showToast(error instanceof Error ? error.message : '파일 전송 작업을 등록하지 못했습니다.', 'error')
    } finally {
      setBusy(false)
    }
  }

  const browse = async (path = pathInput, append = false) => {
    const pc = onlineTargets.find((candidate) => candidate.macAddress === browseMac)
    if (!pc) return
    setBusy(true)
    try {
      const offset = append && listing?.currentPath === path.trim() ? listing.nextOffset : 0
      const next = await listRemoteFiles(pc, path.trim(), offset)
      setListing((current) => append && current?.currentPath === next.currentPath
        ? { ...next, entries: [...current.entries, ...next.entries] }
        : next)
      setPathInput(next.currentPath)
      if (next.partial) showToast('일부 항목을 읽지 못했습니다. 표시된 목록으로 계속할 수 있습니다.', 'error')
    } catch (error) {
      showToast(error instanceof Error ? error.message : '원격 파일 목록을 불러오지 못했습니다.', 'error')
    } finally {
      setBusy(false)
    }
  }

  const addPath = (path: string) => setSelectedPaths((current) => current.includes(path) ? current : [...current, path])
  const selectedPathText = selectedPaths.join('\n')
  const setSelectedPathText = (value: string) => setSelectedPaths(value.split(/\r?\n/).map((item) => item.trim()).filter(Boolean))

  const collect = async () => {
    if (!selectedPaths.length || !onlineTargets.length) return
    setBusy(true)
    try {
      const summary = await sendPcOperation(onlineTargets, {
        type: 'COLLECT_FILES',
        payload: { paths: selectedPaths, outputName: outputName.trim() || 'collected-bundle.zip' },
      })
      finishSummary('파일 수집', summary)
      setCollectionTaskIds(summary.taskIds)
      setCollectionTasks([])
      setCollectionError('')
      setCollectionTracking(summary.taskIds.length > 0)
    } catch (error) {
      showToast(error instanceof Error ? error.message : '파일 수집 작업을 등록하지 못했습니다.', 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget && !busy) onClose() }}>
      <section ref={dialogRef} className="pc-operations-dialog" role="dialog" aria-modal="true" aria-labelledby="pc-operations-title" tabIndex={-1}>
        <header>
          <div><h2 id="pc-operations-title">PC 작업</h2><p>온라인 {onlineTargets.length}대 / 선택 {targets.length}대</p></div>
          <button className="icon-button" onClick={onClose} disabled={busy} aria-label="닫기"><X size={18} /></button>
        </header>
        <div className="segmented-control pc-operation-tabs" aria-label="PC 작업 종류">
          <button className={tab === 'script' ? 'active' : ''} onClick={() => setTab('script')}><FileCode2 size={15} />PowerShell</button>
          <button className={tab === 'transfer' ? 'active' : ''} onClick={() => setTab('transfer')}><Send size={15} />파일 전송</button>
          <button className={tab === 'collect' ? 'active' : ''} onClick={() => setTab('collect')}><FileArchive size={15} />파일 수집</button>
        </div>

        {!onlineTargets.length && <div className="form-error" role="alert">작업을 받을 온라인 PC가 없습니다.</div>}
        {tab === 'script' && <div className="pc-operation-body">
          <label className="command-form-field full"><span>PowerShell 스크립트</span><input type="file" accept=".ps1,text/plain" disabled={busy} onChange={(event) => setScriptFile(event.target.files?.[0] || null)} /></label>
          <div className="command-form-grid">
            <label className="command-form-field"><span>실행 방식</span><select value={runMode} onChange={(event) => setRunMode(event.target.value as typeof runMode)}><option value="FOREGROUND">완료까지 대기</option><option value="BACKGROUND">즉시 실행</option></select></label>
            <label className="command-form-field"><span>창 표시</span><select value={windowMode} onChange={(event) => setWindowMode(event.target.value as typeof windowMode)}><option value="HIDDEN">숨김</option><option value="VISIBLE">표시</option></select></label>
            <label className="command-form-field full"><span>인수</span><input value={args} onChange={(event) => setArgs(event.target.value)} placeholder="선택 입력" /></label>
          </div>
          <button className="button primary operation-submit" disabled={!scriptFile || !onlineTargets.length || busy} onClick={() => void runScript()}><Send size={15} />{busy ? '처리 중' : '스크립트 전송'}</button>
        </div>}

        {tab === 'transfer' && <div className="pc-operation-body">
          <div className="file-selection-actions">
            <label className="button secondary file-picker"><Plus size={15} />파일 선택<input type="file" multiple disabled={busy} onChange={(event) => setTransferFiles(Array.from(event.target.files || []))} /></label>
            <FolderInput disabled={busy} onFiles={setTransferFiles} />
            <span>{transferFiles.length ? `${transferFiles.length}개 · ${formatBytes(transferFiles.reduce((sum, file) => sum + file.size, 0))}` : '선택 없음'}</span>
          </div>
          <div className="command-form-grid">
            <label className="command-form-field"><span>대상 위치</span><select value={destinationMode} onChange={(event) => setDestinationMode(event.target.value as typeof destinationMode)}><option value="DOWNLOADS">다운로드</option><option value="DESKTOP">바탕화면</option><option value="CUSTOM">사용자 지정</option></select></label>
            <label className="command-form-field"><span>실행 창</span><select value={windowMode} onChange={(event) => setWindowMode(event.target.value as typeof windowMode)}><option value="HIDDEN">숨김</option><option value="VISIBLE">표시</option></select></label>
            {destinationMode === 'CUSTOM' && <label className="command-form-field full"><span>Windows 대상 경로</span><input value={destinationPath} onChange={(event) => setDestinationPath(event.target.value)} placeholder="예: C:\\LabFiles" /></label>}
          </div>
          <label className="check-row"><input type="checkbox" checked={runAfterTransfer} onChange={(event) => setRunAfterTransfer(event.target.checked)} />전송 후 파일 실행</label>
          <button className="button primary operation-submit" disabled={!transferFiles.length || !onlineTargets.length || busy} onClick={() => void transfer()}><Send size={15} />{busy ? '처리 중' : '파일 전송'}</button>
        </div>}

        {tab === 'collect' && <div className="pc-operation-body collect-operation">
          <div className="remote-browser-toolbar">
            <select value={browseMac} onChange={(event) => { setBrowseMac(event.target.value); setListing(null); setPathInput('') }} aria-label="탐색할 PC">
              {onlineTargets.map((pc) => <option key={pc.macAddress} value={pc.macAddress}>{pc.groupName || '-'} · {pc.pcName || pc.ipAddress}</option>)}
            </select>
            <input value={pathInput} onChange={(event) => setPathInput(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter') void browse() }} placeholder="비우면 드라이브 목록" />
            <button className="icon-button bordered" onClick={() => void browse()} disabled={!browseMac || busy} aria-label="경로 조회"><RefreshCw size={16} className={busy ? 'spin-icon' : ''} /></button>
            <button className="icon-button bordered" onClick={() => void browse(listing?.parentPath || '')} disabled={!listing?.currentPath || busy} aria-label="상위 폴더"><ArrowUp size={16} /></button>
          </div>
          <label className="check-row"><input type="checkbox" checked={usbOnly} disabled={Boolean(listing?.currentPath)} onChange={(event) => setUsbOnly(event.target.checked)} />루트 목록에서 USB 드라이브만 표시</label>
          <div className="remote-file-list" aria-label="원격 파일 목록">
            {!listing ? <p>PC와 경로를 선택해 목록을 조회하세요.</p> : listing.entries.filter((entry) => !usbOnly || Boolean(listing.currentPath) || entry.usb).length ? listing.entries.filter((entry) => !usbOnly || Boolean(listing.currentPath) || entry.usb).map((entry) => {
              const absolute = joinRemotePath(listing.currentPath, entry.name)
              return <div key={`${entry.type}-${entry.name}`} className="remote-file-row">
                <button className="remote-file-name" onDoubleClick={() => { if (entry.type !== 'file') void browse(absolute) }} onClick={() => { if (entry.type !== 'file') setPathInput(absolute) }} title={entry.type !== 'file' ? '더블 클릭하여 열기' : entry.name}>
                  {entry.type === 'drive' ? <HardDrive size={15} /> : entry.type === 'dir' ? <FolderOpen size={15} /> : <FileCode2 size={15} />}
                  <span>{entry.displayName || entry.name}</span><small>{entry.type === 'file' ? formatBytes(entry.size) : entry.usb ? 'USB' : ''}</small>
                </button>
                <button className="icon-button" onClick={() => addPath(absolute)} aria-label={`${entry.name} 수집 목록에 추가`}><Plus size={15} /></button>
              </div>
            }) : <p>표시할 항목이 없습니다.</p>}
            {listing?.hasMore && <button className="remote-load-more" onClick={() => void browse(listing.currentPath, true)} disabled={busy}>다음 항목 불러오기</button>}
          </div>
          <label className="command-form-field full"><span>수집 경로 (줄마다 하나)</span><textarea rows={4} value={selectedPathText} onChange={(event) => setSelectedPathText(event.target.value)} placeholder={'예: C:\\Users\\student\\Documents\nD:\\result'} /></label>
          <label className="command-form-field full"><span>결과 ZIP 이름</span><input value={outputName} onChange={(event) => setOutputName(event.target.value)} /></label>
          <button className="button primary operation-submit" disabled={!selectedPaths.length || !onlineTargets.length || busy} onClick={() => void collect()}><Send size={15} />{busy ? '처리 중' : '수집 명령 전송'}</button>
          {collectionTaskIds.length > 0 && <section className="collection-status" aria-live="polite">
            <header><div><strong>수집 진행 {collectionTasks.filter((task) => isFinalCommandStatus(task.status)).length}/{collectionTaskIds.length}</strong><small>{collectionTracking ? '2초마다 상태 확인 중' : '상태 추적이 중지되었습니다.'}</small></div>{collectionTracking && <button className="button secondary" onClick={() => setCollectionTracking(false)}>상태 추적 중지</button>}</header>
            <progress value={collectionTasks.filter((task) => isFinalCommandStatus(task.status)).length} max={collectionTaskIds.length} />
            {collectionError && <div className="form-error" role="alert">{collectionError} <button className="button secondary" onClick={() => setCollectionTracking(true)}>다시 추적</button></div>}
            <div className="collection-status-list">
              {collectionTasks.map((task) => {
                const downloadPath = collectDownloadPath(task)
                return <div key={task.taskId}><span>{task.groupName || '-'} · {task.pcName || task.macAddress}</span><strong>{task.status}</strong>{downloadPath && <button className="button secondary" onClick={() => void api.download(downloadPath, collectDownloadFilename(task))}>다운로드</button>}</div>
              })}
            </div>
          </section>}
        </div>}

        {progress && <footer className="operation-progress"><span>{progressLabel(progress)}</span><progress value={progress.uploadedBytes} max={Math.max(1, progress.totalBytes)} /></footer>}
      </section>
    </div>
  )
}
