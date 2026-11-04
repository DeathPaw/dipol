/**
 * Общее поведение модальных окон: защита от случайного закрытия и Enter как
 * подтверждение. Правила висят на document, а не прописаны в каждом окне —
 * так они действуют одинаково везде, включая окна, которые появятся позже.
 */
export function installModalBehaviour(): void {
  guardAccidentalClose()
  submitOnEnter()
}

/**
 * Модалки закрываются кликом по затемнению. Браузер считает кликом пару
 * mousedown+mouseup и целью — их общего предка: если начать выделять текст
 * внутри окна и отпустить кнопку за его краем, окно закроется вместе с
 * несохранёнными правками. Гасим именно такой «протащенный» клик.
 */
function guardAccidentalClose(): void {
  let startedInsideModal = false

  document.addEventListener('mousedown', e => {
    const t = e.target as Element | null
    startedInsideModal = !!t?.closest?.('.modal')
  }, true)

  document.addEventListener('click', e => {
    const t = e.target as Element | null
    if (!t || !t.classList?.contains('modal-overlay')) return
    if (!startedInsideModal) return
    e.stopPropagation()
    e.preventDefault()
    startedInsideModal = false
  }, true)
}

/**
 * Enter в поле формы = нажать основную кнопку окна. Работает только когда
 * фокус в поле: в окнах-подтверждениях без полей случайный Enter ничего не
 * запускает. Shift+Enter в textarea по-прежнему переносит строку.
 */
function submitOnEnter(): void {
  document.addEventListener('keydown', e => {
    if (e.key !== 'Enter' || e.shiftKey || e.ctrlKey || e.metaKey || e.altKey) return
    if (e.defaultPrevented || e.isComposing) return

    const target = e.target as HTMLElement | null
    const modal = target?.closest?.('.modal')
    if (!modal || !target) return

    const tag = target.tagName
    const isField =
      (tag === 'INPUT' && (target as HTMLInputElement).type !== 'checkbox'
        && (target as HTMLInputElement).type !== 'radio')
      || tag === 'TEXTAREA'
    if (!isField) return

    // Порядок важен: в окне с «Сохранить» и «Удалить» Enter обязан означать «Сохранить».
    const actions = modal.querySelector('.modal-actions') ?? modal
    const button =
      actions.querySelector<HTMLButtonElement>('.btn-primary:not([disabled])')
      ?? actions.querySelector<HTMLButtonElement>('.btn-success:not([disabled])')
      ?? actions.querySelector<HTMLButtonElement>('.btn-danger:not([disabled])')
    if (!button) return

    e.preventDefault()
    button.click()
  })
}
