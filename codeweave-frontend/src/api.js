const API_BASE = '/api'

async function request(path, options = {}) {
  const token = localStorage.getItem('codeweave-token')
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) }
  if (token) headers['codeweave-token'] = token
  const response = await fetch(`${API_BASE}${path}`, { ...options, headers })
  if (response.status === 204) return null
  const raw = await response.text()
  let data = null
  if (raw.trim()) { try { data = JSON.parse(raw) } catch { data = { message: raw.trim() } } }
  if (!response.ok) throw new Error(data?.message || `请求失败（${response.status}）`)
  return data
}

export const api = {
  login: (email, password) => request('/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) }),
  me: () => request('/auth/me'),
  profile: () => request('/profile'),
  uploadAvatar: async file => {
    const token = localStorage.getItem('codeweave-token')
    const body = new FormData(); body.append('file', file)
    const response = await fetch(`${API_BASE}/profile/avatar`, { method: 'POST', headers: token ? { 'codeweave-token': token } : {}, body })
    const raw = await response.text(); let data = null
    if (raw.trim()) { try { data = JSON.parse(raw) } catch { data = { message: raw.trim() } } }
    if (!response.ok) throw new Error(data?.message || `头像上传失败（${response.status}）`)
    return data
  },
  sessions: () => request('/sessions'),
  summary: () => request('/workspace/summary'),
  projects: () => request('/workspace/projects'),
  createProject: (payload) => request('/workspace/projects', { method: 'POST', body: JSON.stringify(payload) }),
  updateProject: (id, payload) => request(`/workspace/projects/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),
  syncProject: id => request(`/workspace/projects/${id}/sync`, { method: 'POST' }),
  ragflowConfig: () => request('/settings/ragflow'),
  saveRagflowConfig: (payload) => request('/settings/ragflow', { method: 'PUT', body: JSON.stringify(payload) }),
  testRagflowConfig: (payload) => request('/settings/ragflow/test', { method: 'POST', body: JSON.stringify(payload) }),
  ragflowDatasets: () => request('/settings/ragflow/datasets'),
  deleteProject: (id) => request(`/workspace/projects/${id}`, { method: 'DELETE' }),
  uploadSnapshot: async (projectId, file) => {
    const token = localStorage.getItem('codeweave-token')
    const body = new FormData(); body.append('file', file)
    const response = await fetch(`${API_BASE}/workspace/projects/${projectId}/snapshots`, { method: 'POST', headers: token ? { 'codeweave-token': token } : {}, body })
    if (!response.ok) throw new Error(`快照上传失败（${response.status}）`)
    return response.json()
  },
  createShare: (payload) => request('/workspace/shares', { method: 'POST', body: JSON.stringify(payload) }),
  createTask: (payload) => request('/workspace/tasks', { method: 'POST', body: JSON.stringify(payload) }),
  createAgentSession: (payload = {}) => request('/agent/sessions', { method: 'POST', body: JSON.stringify(payload) }),
  selectAgentModel: (sessionId, modelId, modelName = '') => request(`/agent/sessions/${sessionId}/model`, { method: 'PUT', body: JSON.stringify({ modelId, ...(modelName ? { modelName } : {}) }) }),
  cancelAgentSession: sessionId => request(`/agent/sessions/${sessionId}/cancel`, { method: 'POST' }),
  sendAgentMessage: (sessionId, text, receiptIds = [], images = []) => request(`/agent/sessions/${sessionId}/messages`, { method: 'POST', body: JSON.stringify({ text, receiptIds, images }) }),
  uploadAgentFile: async (sessionId, file) => {
    const token = localStorage.getItem('codeweave-token')
    const body = new FormData(); body.append('file', file, file.name)
    const response = await fetch(`${API_BASE}/agent/sessions/${sessionId}/attachments`, { method: 'POST', headers: token ? { 'codeweave-token': token } : {}, body })
    const raw = await response.text(); let data = null
    if (raw.trim()) { try { data = JSON.parse(raw) } catch { data = { message: raw.trim() } } }
    if (!response.ok) throw new Error(data?.message || `文件上传失败（${response.status}）`)
    return data
  },
  agentEvents: (sessionId) => request(`/agent/sessions/${sessionId}/events`),
  dshConsole: (project = {}) => request('/agent/dsh-console', { method: 'POST', body: JSON.stringify({ projectId: project.projectId || project.id || '', projectName: project.name || '' }) }),
  models: () => request('/settings/models'),
  modelCatalog: () => request('/settings/models/catalog'),
  createModel: (payload) => request('/settings/models', { method: 'POST', body: JSON.stringify(payload) }),
  updateModel: (id, payload) => request(`/settings/models/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),
  deleteModel: (id) => request(`/settings/models/${id}`, { method: 'DELETE' }),
  testModel: (id) => request(`/settings/models/${id}/test`, { method: 'POST' }),
  testModelConnection: (payload) => request('/settings/models/test-connection', { method: 'POST', body: JSON.stringify(payload) }),
  discoverModelList: (payload) => request('/settings/models/discover', { method: 'POST', body: JSON.stringify(payload) }),
  roles: () => request('/settings/roles'),
  createRole: (payload) => request('/settings/roles', { method: 'POST', body: JSON.stringify(payload) }),
  updateRole: (id, payload) => request(`/settings/roles/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),
  deleteRole: (id) => request(`/settings/roles/${id}`, { method: 'DELETE' }),
  users: () => request('/settings/users'),
  inviteUser: (payload) => request('/settings/users', { method: 'POST', body: JSON.stringify(payload) }),
  updateUserRole: (id, role) => request(`/settings/users/${id}/role`, { method: 'PUT', body: JSON.stringify({ role }) }),
  updateUser: (id, payload) => request(`/settings/users/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),
  uploadUserAvatar: async (id, file) => {
    const token = localStorage.getItem('codeweave-token')
    const body = new FormData(); body.append('file', file)
    const response = await fetch(`${API_BASE}/settings/users/${id}/avatar`, { method: 'POST', headers: token ? { 'codeweave-token': token } : {}, body })
    const raw = await response.text(); let data = null
    if (raw.trim()) { try { data = JSON.parse(raw) } catch { data = { message: raw.trim() } } }
    if (!response.ok) throw new Error(data?.message || `头像上传失败（${response.status}）`)
    return data
  },
  deleteUser: (id) => request(`/settings/users/${id}`, { method: 'DELETE' }),
  resetUserPassword: (id, password) => request(`/settings/users/${id}/password`, { method: 'PUT', body: JSON.stringify({ password }) }),
  departments: () => request('/settings/departments'),
  createDepartment: (payload) => request('/settings/departments', { method: 'POST', body: JSON.stringify(payload) }),
  updateDepartment: (id, payload) => request(`/settings/departments/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),
  deleteDepartment: (id) => request(`/settings/departments/${id}`, { method: 'DELETE' }),
}
