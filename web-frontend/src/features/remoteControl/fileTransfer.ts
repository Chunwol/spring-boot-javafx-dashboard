import type { PcStatus } from '../../types/dashboard'
import { uploadAndAssembleFile } from '../files/api'
import { clearUploadSession, resolveUploadSession } from '../files/model'
import type { FileUploadProgress } from '../files/types'
import { getOperationStatuses, sendPcOperation, type OperationStatus } from '../pcs/operations'

export interface RemoteFileQueueProgress {
  fileIndex: number
  totalFiles: number
  fileName: string
  upload: FileUploadProgress
}

export async function queueRemoteDesktopFiles({
  files,
  pc,
  token,
  storage = window.localStorage,
  onProgress,
}: {
  files: readonly File[]
  pc: PcStatus
  token: string
  storage?: Storage
  onProgress?: (progress: RemoteFileQueueProgress) => void
}) {
  if (!files.length) throw new Error('전송할 파일을 선택하세요.')
  const taskIds: number[] = []

  for (let fileIndex = 0; fileIndex < files.length; fileIndex += 1) {
    const file = files[fileIndex]
    if (!file.size) throw new Error(`${file.name}: 빈 파일은 전송할 수 없습니다.`)
    const resolved = resolveUploadSession(file, storage)
    const artifact = await uploadAndAssembleFile({
      file,
      token,
      uploadId: resolved.session.uploadId,
      onProgress: (upload) => onProgress?.({ fileIndex, totalFiles: files.length, fileName: file.name, upload }),
    })
    clearUploadSession(resolved.session.fingerprint, storage)

    const summary = await sendPcOperation([pc], {
      type: 'RUN_PS1',
      scriptUrl: artifact.downloadUrl,
      payload: {
        runMode: 'FOREGROUND',
        windowMode: 'HIDDEN',
        transferMode: true,
        sourceType: 'file',
        displayName: file.name,
        destinationMode: 'DESKTOP',
        conflictPolicy: 'RENAME',
        runAfterTransfer: false,
        zipped: false,
      },
    })
    if (summary.queued !== 1 || summary.taskIds.length !== 1) {
      throw new Error(`${file.name}: 대상 PC에 파일 전송 명령을 등록하지 못했습니다.`)
    }
    taskIds.push(summary.taskIds[0])
  }

  return taskIds
}

export async function waitForRemoteFileTransfers(
  taskIds: readonly number[],
  { timeoutMs = 30 * 60_000, pollMs = 1_500 }: { timeoutMs?: number; pollMs?: number } = {},
): Promise<OperationStatus[]> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const statuses = await getOperationStatuses(taskIds)
    if (statuses.length === taskIds.length
      && statuses.every((task) => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(task.status.toUpperCase()))) {
      return statuses
    }
    await new Promise((resolve) => window.setTimeout(resolve, pollMs))
  }
  throw new Error('원격 PC의 파일 저장 완료 확인 시간이 초과되었습니다.')
}
