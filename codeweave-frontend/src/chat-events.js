// Only committed assistant text is conversational content; tool output and reasoning stay private to the runtime.
export function readChatEvents(payload) {
  if (typeof payload === 'string') {
    try { payload = JSON.parse(payload) } catch { return [] }
  }
  if (!payload || typeof payload !== 'object') return []
  if (Array.isArray(payload)) return payload.flatMap(readChatEvents)
  if (payload.type === 'snapshot') return (payload.records || []).flatMap(readChatEvents)
  if (payload.method === 'session.event') return readChatEvents(payload.params?.event)
  if (payload.type === 'event') return readChatEvents(payload.event)
  if (payload.value && typeof payload.value === 'object') return readChatEvents(payload.value)
  const event = payload.event && typeof payload.event === 'object' ? payload.event : payload
  if (!event) return []
  const id = event.seq == null ? undefined : `event-${event.seq}`
  if (event.type === 'assistant/message') {
    const content = event.data?.message?.content
    const text = typeof content === 'string'
      ? content.trim()
      : Array.isArray(content)
        ? content.filter(block => block?.type === 'text' && typeof block.text === 'string').map(block => block.text).join('\n').trim()
        : ''
    return text ? [{ id, role: 'assistant', text }] : []
  }
  if (event.type === 'turn/start') return [{ status: 'running' }]
  if (event.type === 'turn/end') {
    const kind = event.data?.reason?.kind
    if (kind === 'error') {
      const reason = event.data?.reason
      const detail = typeof reason?.message === 'string' ? reason.message.trim()
        : typeof reason?.error === 'string' ? reason.error.trim()
          : typeof reason?.error?.message === 'string' ? reason.error.message.trim() : ''
      return [{ id, role: 'error', text: detail ? `回复失败：${detail}` : '暂时无法生成回复，请检查模型配置后重试。', status: 'error' }]
    }
    if (['aborted', 'interrupted', 'blocked'].includes(kind)) return [{ id, role: 'error', text: '回复已中断，你可以重新发送消息。', status: 'stopped' }]
    return [{ status: 'complete' }]
  }
  return []
}
