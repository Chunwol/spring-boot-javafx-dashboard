import { AlertTriangle, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

interface ConfirmDialogProps {
  open: boolean
  title: string
  description: string
  confirmLabel: string
  danger?: boolean
  busy?: boolean
  requiredText?: string
  requiredTextLabel?: string
  onConfirm: () => void
  onCancel: () => void
}

export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  danger = false,
  busy = false,
  requiredText,
  requiredTextLabel,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  const [typedText, setTypedText] = useState('')
  const dialogRef = useRef<HTMLElement>(null)
  const cancelButtonRef = useRef<HTMLButtonElement>(null)
  const busyRef = useRef(busy)
  const onCancelRef = useRef(onCancel)

  busyRef.current = busy
  onCancelRef.current = onCancel

  useEffect(() => {
    if (!open) return

    setTypedText('')
    const previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const dialog = dialogRef.current
    const cancelButton = cancelButtonRef.current
    cancelButton?.focus()
    if (document.activeElement !== cancelButton) dialog?.focus()

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        if (!busyRef.current) {
          event.preventDefault()
          onCancelRef.current()
        }
        return
      }
      if (event.key !== 'Tab' || !dialogRef.current) return

      const currentDialog = dialogRef.current
      const focusable = Array.from(currentDialog.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
      )).filter((element) => !element.hidden && !element.closest('[hidden]'))

      if (focusable.length === 0) {
        event.preventDefault()
        currentDialog.focus()
        return
      }

      const first = focusable[0]
      const last = focusable[focusable.length - 1]
      const activeElement = document.activeElement
      if (!currentDialog.contains(activeElement)) {
        event.preventDefault()
        ;(event.shiftKey ? last : first).focus()
      } else if (event.shiftKey && activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      if (previouslyFocused?.isConnected) previouslyFocused.focus()
    }
  }, [open])

  if (!open) return null
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !busy) onCancel()
    }}>
      <section ref={dialogRef} className="confirm-dialog" role="alertdialog" aria-modal="true" aria-labelledby="confirm-title" aria-describedby="confirm-description" tabIndex={-1}>
        <button className="icon-button modal-close" onClick={onCancel} disabled={busy} aria-label="닫기"><X size={18} /></button>
        <span className={`confirm-icon${danger ? ' danger' : ''}`}><AlertTriangle size={22} /></span>
        <h2 id="confirm-title">{title}</h2>
        <p id="confirm-description">{description}</p>
        {requiredText && (
          <label className="confirm-input">
            <span>{requiredTextLabel || `확인을 위해 '${requiredText}'을(를) 입력하세요.`}</span>
            <input value={typedText} onChange={(event) => setTypedText(event.target.value)} autoComplete="off" />
          </label>
        )}
        <div className="modal-actions">
          <button ref={cancelButtonRef} className="button secondary" onClick={onCancel} disabled={busy}>취소</button>
          <button className={`button ${danger ? 'danger-solid' : 'primary'}`} onClick={onConfirm} disabled={busy || Boolean(requiredText && typedText !== requiredText)}>{busy ? '처리 중' : confirmLabel}</button>
        </div>
      </section>
    </div>
  )
}
