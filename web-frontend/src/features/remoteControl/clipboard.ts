export interface UnicodeKeySender {
  sendKey: (keysym: number, code: string, down?: boolean) => void
}

const ENTER_KEYSYM = 0xff0d
const TAB_KEYSYM = 0xff09
const BACKSPACE_KEYSYM = 0xff08
const UNICODE_KEYSYM_BASE = 0x01000000

export function needsUnicodeKeyPaste(text: string) {
  return Array.from(text).some((character) => character.codePointAt(0)! > 0xff)
}

export function sendUnicodeKeyPaste(client: UnicodeKeySender, text: string) {
  const normalized = text.replace(/\r\n?/g, '\n')
  for (const character of normalized) {
    let keysym: number
    if (character === '\n') keysym = ENTER_KEYSYM
    else if (character === '\t') keysym = TAB_KEYSYM
    else if (character === '\b') keysym = BACKSPACE_KEYSYM
    else {
      const codePoint = character.codePointAt(0)!
      keysym = codePoint > 0xff ? UNICODE_KEYSYM_BASE | codePoint : codePoint
    }
    client.sendKey(keysym, '')
  }
}
