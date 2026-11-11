import { FileText, Trash2, X } from 'lucide-react'
import { useState } from 'react'
import { useDialogFocus } from '../../components/ui/useDialogFocus'
import type { PcStatus } from '../../types/dashboard'
import './pcs.css'

interface Props {
  pc: PcStatus
  busy: boolean
  onClose: () => void
  onSave: (note: string | null) => void
}

const MAX_NOTE_LENGTH = 500

export function DeviceNoteDialog({ pc, busy, onClose, onSave }: Props) {
  const [draft, setDraft] = useState(pc.deviceNote || '')
  const dialogRef = useDialogFocus<HTMLElement>(onClose, busy)
  const normalized = draft.trim()
  const current = pc.deviceNote?.trim() || ''
  const unchanged = normalized === current

  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !busy) onClose()
    }}>
      <section ref={dialogRef} className="device-note-dialog" role="dialog" aria-modal="true" aria-labelledby="device-note-title" tabIndex={-1}>
        <button type="button" className="icon-button modal-close" onClick={onClose} disabled={busy} aria-label="닫기"><X size={18} /></button>
        <header>
          <span className="device-note-dialog-icon" aria-hidden="true"><FileText size={20} /></span>
          <div>
            <h2 id="device-note-title">장치 메모</h2>
            <p>{pc.groupName || '-'} · {pc.pcName || pc.macAddress}</p>
          </div>
        </header>
        <p className="device-note-guidance">장비를 계속 사용하면서 공유해야 할 점검 내용이나 수리 일정을 기록합니다. 이 메모는 PC 상태와 명령 실행에 영향을 주지 않습니다.</p>
        <div className="device-note-field">
          <label htmlFor="device-note-input">장치 메모</label>
          <textarea
            id="device-note-input"
            autoFocus
            value={draft}
            maxLength={MAX_NOTE_LENGTH}
            rows={6}
            placeholder="예: 사용 가능·점검 필요 — 간헐적 팬 경고, 보증수리 예정"
            onChange={(event) => setDraft(event.target.value)}
          />
          <small><span>개인정보나 계정 정보는 입력하지 마세요.</span><span>{draft.length}/{MAX_NOTE_LENGTH}</span></small>
        </div>
        <div className="device-note-actions">
          <div>
            {current && <button type="button" className="button note-clear" onClick={() => onSave(null)} disabled={busy}><Trash2 size={15} />메모 삭제</button>}
          </div>
          <div>
            <button type="button" className="button secondary" onClick={onClose} disabled={busy}>취소</button>
            <button type="button" className="button primary" onClick={() => onSave(normalized || null)} disabled={busy || unchanged}>{busy ? '저장 중' : '메모 저장'}</button>
          </div>
        </div>
      </section>
    </div>
  )
}
