import { useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { api } from './api'
import { readChatEvents } from './chat-events'
import './styles.css'

const sessions = []

const DEFAULT_AVATARS = {
  male: '/avatars/default/user-male.png',
  female: '/avatars/default/user-female.png',
  assistant: '/avatars/default/assistant.png'
}

function defaultAvatarForGender(gender) {
  return gender === 'female' ? DEFAULT_AVATARS.female : DEFAULT_AVATARS.male
}

function userAvatar(user) {
  return user?.avatarUrl || defaultAvatarForGender(user?.gender)
}

function AvatarImage({ src, fallback, fallbackText = 'AI', alt = '', className = 'message-avatar' }) {
  const [current, setCurrent] = useState(src || fallback)
  const [failed, setFailed] = useState(false)
  useEffect(() => { setCurrent(src || fallback); setFailed(false) }, [src, fallback])
  if (!current || failed) return <span className={`${className} fallback-avatar`} role="img" aria-label={alt}>{fallbackText}</span>
  return <img className={className} src={current} alt={alt} onError={() => { if (current !== fallback) setCurrent(fallback); else setFailed(true) }} />
}

function normalizeProfile(profile) {
  if (!profile) return null
  const customAvatar = Boolean(profile.hasCustomAvatar) || (profile.avatarUrl && !profile.avatarUrl.includes('/avatars/default/'))
  return {
    ...profile,
    avatarUrl: customAvatar ? profile.avatarUrl : defaultAvatarForGender(profile.gender),
    assistantAvatarUrl: DEFAULT_AVATARS.assistant,
    hasCustomAvatar: customAvatar
  }
}

function Dot({ tone = 'mint' }) { return <i className={`dot ${tone}`} /> }
function Badge({ children, tone = 'cyan' }) { return <span className={`badge ${tone}`}>{children}</span> }
function MenuIcon({ name }) {
  const paths = {
    overview: <><rect x="4" y="4" width="6" height="6" rx="1" /><rect x="14" y="4" width="6" height="6" rx="1" /><rect x="4" y="14" width="6" height="6" rx="1" /><rect x="14" y="14" width="6" height="6" rx="1" /></>,
    agent: <><path d="m12 3 1.7 5.3L19 10l-5.3 1.7L12 17l-1.7-5.3L5 10l5.3-1.7L12 3Z" /><path d="m19 16 .6 1.9L21.5 18l-1.9.6L19 20.5l-.6-1.9-1.9-.6 1.9-.6L19 16Z" /></>,
    projects: <><path d="M3.5 7.5h6l2 2h9v8.8a1.7 1.7 0 0 1-1.7 1.7H5.2a1.7 1.7 0 0 1-1.7-1.7V7.5Z" /><path d="M3.5 7.5V5.7A1.7 1.7 0 0 1 5.2 4h4l2 2h3" /></>,
    conflicts: <><path d="M7 7h5a5 5 0 0 1 5 5v1" /><path d="m14 10 3 3 3-3" /><path d="M17 17h-5a5 5 0 0 1-5-5v-1" /><path d="m10 14-3-3-3 3" /></>,
    audit: <><rect x="5" y="3.5" width="14" height="17" rx="2" /><path d="M9 3.5v2h6v-2M8.5 10h7M8.5 14h7M8.5 18h4" /></>,
    settings: <><path d="M12 8.3a3.7 3.7 0 1 0 0 7.4 3.7 3.7 0 0 0 0-7.4Z" /><path d="m19.4 13.5 1.1.8-1.8 3.1-1.3-.5a7.8 7.8 0 0 1-1.5.9l-.2 1.4h-3.6l-.2-1.4a7.8 7.8 0 0 1-1.5-.9l-1.3.5-1.8-3.1 1.1-.8a7.4 7.4 0 0 1 0-1.8l-1.1-.8 1.8-3.1 1.3.5a7.8 7.8 0 0 1 1.5-.9l.2-1.4h3.6l.2 1.4a7.8 7.8 0 0 1 1.5.9l1.3-.5 1.8 3.1-1.1.8a7.4 7.4 0 0 1 0 1.8Z" /></>,
    logout: <><path d="M10 5H6.5A1.5 1.5 0 0 0 5 6.5v11A1.5 1.5 0 0 0 6.5 19H10" /><path d="M13 8l4 4-4 4M17 12H9" /></>
  }
  return <svg className="menu-icon" viewBox="0 0 24 24" aria-hidden="true">{paths[name]}</svg>
}
function ConfirmDialog({ dialog, onCancel, onConfirm }) {
  if (!dialog) return null
  return <div className="confirm-overlay" onMouseDown={event => event.target === event.currentTarget && onCancel()}>
    <section className="confirm-dialog" role="alertdialog" aria-modal="true" aria-labelledby="confirm-dialog-title" aria-describedby="confirm-dialog-message">
      <div className="confirm-dialog-icon">!</div>
      <div className="confirm-dialog-copy"><h3 id="confirm-dialog-title">{dialog.title}</h3><p id="confirm-dialog-message">{dialog.message}</p></div>
      <div className="confirm-dialog-actions"><button className="quiet" onClick={onCancel}>取消</button><button className="danger-button" onClick={onConfirm}>{dialog.confirmLabel || '确认删除'}</button></div>
    </section>
  </div>
}

function Login({ onLogin }) {
  const [email, setEmail] = useState('lin@company.com')
  const [password, setPassword] = useState('demo-password')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const submit = async (event) => {
    event?.preventDefault()
    setBusy(true); setError('')
    try { await onLogin(email, password) } catch (e) { setError(e.message || '登录失败，请检查账号信息') } finally { setBusy(false) }
  }
  return <main className="login">
    <section className="login-story">
      <div className="brand"><b>⌁</b> codeweave</div>
      <div className="story-copy"><Badge>ENTERPRISE AI WORKBENCH</Badge><h1>让团队和 Agent<br /><em>一起把事情做完。</em></h1><p>从第一条指令到最后一次合并，CodeWeave 把会话、代码、执行环境和协作过程放进同一个清晰的工作区。</p></div>
      <div className="story-status"><span><Dot /> Workspace online</span><span>CodeWeave runtime</span><span>v0.1 preview</span></div>
    </section>
    <section className="login-form-wrap"><form className="login-card" onSubmit={submit}><div className="form-head"><div><small>WELCOME BACK</small><h2>登录工作台</h2></div><Badge tone="mint">SSO READY</Badge></div><p className="muted">使用企业账号进入你的项目和协作会话。</p><button type="button" className="sso" onClick={() => onLogin('sso@company.com', 'sso-demo')}>◎ 使用企业 SSO 登录 <span>→</span></button><div className="or"><span>或使用账号登录</span></div><label>企业邮箱<input value={email} onChange={e => setEmail(e.target.value)} placeholder="name@company.com" /></label><label>密码<input value={password} onChange={e => setPassword(e.target.value)} type="password" placeholder="输入密码" /></label><div className="form-row"><label className="remember"><input type="checkbox" /> 保持登录</label><a href="#forgot">忘记密码？</a></div>{error && <p className="login-error">{error}</p>}<button type="submit" className="primary full" disabled={busy}>{busy ? '正在进入…' : '进入工作台'} <span>↗</span></button><p className="login-help">还没有组织账号？<a href="#request">联系管理员开通</a></p></form><div className="login-footer">Privacy protected　·　Enterprise access　·　© 2026 CodeWeave</div></section>
  </main>
}
function SidebarToggle({ collapsed, onToggle }) { return <button className="topbar-sidebar-toggle" onClick={onToggle} aria-expanded={!collapsed} aria-label={collapsed ? '展开菜单' : '收起菜单'} title={collapsed ? '展开菜单' : '收起菜单'}><svg className="sidebar-toggle-icon" viewBox="0 0 24 24" aria-hidden="true"><path d={collapsed ? 'M4 6.5h10M4 12h10M4 17.5h10' : 'M10 6.5h10M10 12h10M10 17.5h10'} /><path d={collapsed ? 'm16 8.5 3.5 3.5-3.5 3.5M19.5 12H13' : 'm8 8.5-3.5 3.5L8 15.5M4.5 12H11'} /></svg></button> }
function Header({ share, collapsed, onToggle, modelName, project, profile, onAvatarUpload }) { return <header className="topbar"><div className="top-left"><div className="brand compact"><b>⌁</b><span className="brand-copy"><strong>codeweave</strong><small>AI 协作工作台</small></span></div><SidebarToggle collapsed={collapsed} onToggle={onToggle} /><span>/</span><strong>{project?.name || '未选择项目'}</strong><Badge tone={project ? 'mint' : 'cyan'}>{project ? '工作区已连接' : '工作区为空'}</Badge></div><div className="top-center"><span><Dot /> Rust Local <b>127.0.0.1:9042</b></span><span><Dot tone="cyan" /> K8s Runner <b>cluster-ap-east</b></span><span className="model">✦ {modelName || '未配置模型'} <small>{modelName ? '默认' : '设置里添加'}</small></span></div><div className="top-right"><div className="avatars"><span>A</span><span>B</span><span>+1</span></div><button className="quiet" onClick={share}>↗ 分享</button><label className="profile profile-upload" title="更换头像"><AvatarImage src={profile?.avatarUrl} fallback={defaultAvatarForGender(profile?.gender)} fallbackText="你" alt="当前用户头像" className="profile-avatar" /><input type="file" accept="image/png,image/jpeg" onChange={event => event.target.files?.[0] && onAvatarUpload?.(event.target.files[0])} /></label></div></header> }

function Sidebar({ active, setActive, logout, openSettings, access, collapsed }) { const items = [['overview','overview','总览'],['agent','agent','Agent 工作台'],['projects','projects','项目与工作区'],['conflicts','conflicts','冲突中心'],['audit','audit','审计与活动']]; const menus = access?.menus || items.map(item => item[0]); return <aside className={`sidebar${collapsed ? ' collapsed' : ''}`}><nav>{items.filter(([id]) => menus.includes(id)).map(([id,icon,label]) => <button className={active === id ? 'active' : ''} onClick={() => setActive(id)} key={id} aria-label={label} title={label}><i><MenuIcon name={icon} /></i><span>{label}</span></button>)}</nav><div className="side-bottom"><button onClick={openSettings} aria-label="设置" title="设置"><i>⚙</i><span>设置</span></button><button onClick={logout} aria-label="退出" title="退出"><i>↪</i><span>退出</span></button><small><Dot /> Agent connected</small></div></aside> }

function SettingsModal({ onClose, onToast, onDefaultModelChange, access }) {
  const [section, setSection] = useState('models')
  const [showForm, setShowForm] = useState(false)
  const [confirmDialog, setConfirmDialog] = useState(null)
  const [testing, setTesting] = useState(false)
  const [modelTestResult, setModelTestResult] = useState(null)
  const [discoveredModels, setDiscoveredModels] = useState([])
  const [discoveringModels, setDiscoveringModels] = useState(false)
  const [models, setModels] = useState([])
  const [roles, setRoles] = useState([])
  const [users, setUsers] = useState([])
  const [departments, setDepartments] = useState([])
  const [ragflowUrl, setRagflowUrl] = useState('')
  const [ragflowToken, setRagflowToken] = useState('')
  const [ragflowTokenConfigured, setRagflowTokenConfigured] = useState(false)
  const [ragflowSaving, setRagflowSaving] = useState(false)
  const [ragflowTesting, setRagflowTesting] = useState(false)
  const [ragflowTest, setRagflowTest] = useState(null)
  const [ragflowConfigOpen, setRagflowConfigOpen] = useState(true)
  const [ragflowDatasetList, setRagflowDatasetList] = useState([])
  const [editingId, setEditingId] = useState(null)
  const [modelName, setModelName] = useState('DeepSeek-V3')
  const [endpoint, setEndpoint] = useState('https://api.deepseek.com/v1')
  const [apiKey, setApiKey] = useState('')
  const [userName, setUserName] = useState('')
  const [inviteEmail, setInviteEmail] = useState('')
  const [inviteRole, setInviteRole] = useState('developer')
  const [inviteGender, setInviteGender] = useState('male')
  const [roleFormOpen, setRoleFormOpen] = useState(false)
  const [editingRoleId, setEditingRoleId] = useState(null)
  const [roleName, setRoleName] = useState('')
  const [roleDescription, setRoleDescription] = useState('')
  const [rolePermission, setRolePermission] = useState('读写项目')
  const [roleMenus, setRoleMenus] = useState(['overview', 'projects'])
  const [userFormOpen, setUserFormOpen] = useState(false)
  const [editingUserId, setEditingUserId] = useState(null)
  const [editUserName, setEditUserName] = useState('')
  const [editUserEmail, setEditUserEmail] = useState('')
  const [editUserRole, setEditUserRole] = useState('developer')
  const [editUserDepartment, setEditUserDepartment] = useState('engineering')
  const [editUserGender, setEditUserGender] = useState('male')
  const [editUserAvatarFile, setEditUserAvatarFile] = useState(null)
  const [editUserAvatarPreview, setEditUserAvatarPreview] = useState('')
  const [passwordFormOpen, setPasswordFormOpen] = useState(false)
  const [passwordUser, setPasswordUser] = useState(null)
  const [newPassword, setNewPassword] = useState('')
  const [departmentFormOpen, setDepartmentFormOpen] = useState(false)
  const [editingDepartmentId, setEditingDepartmentId] = useState(null)
  const [departmentName, setDepartmentName] = useState('')
  const [departmentParent, setDepartmentParent] = useState('root')
  const canManageOrg = access?.permissions?.includes('*') || access?.permissions?.includes('org:manage') || access?.permissions?.includes('全部权限')
  useEffect(() => {
    if (!editUserAvatarFile) return undefined
    const url = URL.createObjectURL(editUserAvatarFile)
    setEditUserAvatarPreview(url)
    return () => URL.revokeObjectURL(url)
  }, [editUserAvatarFile])
  useEffect(() => { Promise.allSettled([api.models(), api.roles(), api.users(), api.departments(), api.ragflowConfig()]).then(async ([m, r, u, d, k]) => { if (m.status === 'fulfilled') { const items = m.value?.items || []; setModels(items); onDefaultModelChange?.((items.find(item => item.defaultModel) || items[0])?.name || '') } if (r.status === 'fulfilled') setRoles(r.value?.items || []); if (u.status === 'fulfilled') setUsers(u.value?.items || []); if (d.status === 'fulfilled') setDepartments(d.value?.items || []); if (k.status === 'fulfilled') { const configured = Boolean(k.value?.url && k.value?.tokenConfigured); setRagflowUrl(k.value?.url || ''); setRagflowTokenConfigured(Boolean(k.value?.tokenConfigured)); setRagflowConfigOpen(!configured); if (configured) { try { const datasets = await api.ragflowDatasets(); setRagflowDatasetList(datasets?.items || []) } catch { setRagflowDatasetList([]) } } } }) }, [onDefaultModelChange])
  const saveRagflow = async () => {
    if (!ragflowUrl.trim()) return onToast('请填写 RAGFlow URL')
    setRagflowSaving(true)
    try { const result = await api.saveRagflowConfig({ url: ragflowUrl.trim(), ...(ragflowToken.trim() ? { token: ragflowToken.trim() } : {}) }); setRagflowUrl(result.url || ragflowUrl.trim()); setRagflowToken(''); setRagflowTokenConfigured(Boolean(result.tokenConfigured)); const datasets = await api.ragflowDatasets(); setRagflowDatasetList(datasets?.items || []); setRagflowConfigOpen(false); onToast('RAGFlow 连接配置已保存') }
    catch (error) { onToast(error.message || 'RAGFlow 连接配置保存失败') }
    finally { setRagflowSaving(false) }
  }
  const testRagflow = async () => {
    if (!ragflowUrl.trim()) return setRagflowTest({ tone: 'error', text: '请填写 RAGFlow URL' })
    setRagflowTesting(true); setRagflowTest(null)
    try { const result = await api.testRagflowConfig({ url: ragflowUrl.trim(), ...(ragflowToken.trim() ? { token: ragflowToken.trim() } : {}) }); setRagflowTest({ tone: 'success', text: result.message || '连接成功' }) }
    catch (error) { setRagflowTest({ tone: 'error', text: error.message || 'RAGFlow 连接失败' }) }
    finally { setRagflowTesting(false) }
  }
  const openModel = model => { setEditingId(model?.id || null); setModelName(model?.name || 'deepseek-chat'); setEndpoint(model?.endpoint || 'https://api.deepseek.com/v1'); setApiKey(''); setModelTestResult(null); setDiscoveredModels([]); setShowForm(true) }
  const discoverModels = async () => {
    if (!endpoint.trim()) return setModelTestResult({ status: 'invalid_endpoint', message: '请先填写接口地址' })
    setDiscoveringModels(true); setModelTestResult(null)
    try {
      const result = await api.discoverModelList({ ...(editingId ? { id: editingId } : {}), endpoint: endpoint.trim(), ...(apiKey.trim() ? { apiKey: apiKey.trim() } : {}) })
      setDiscoveredModels(result.items || [])
      if (!result.items?.length) setModelTestResult({ status: 'warning', message: '接口返回成功，但没有可用模型。' })
    } catch (error) {
      setDiscoveredModels([])
      setModelTestResult({ status: 'error', message: error.message || '模型列表获取失败' })
    } finally { setDiscoveringModels(false) }
  }
  const testConnection = async () => {
    if (!endpoint.trim()) return setModelTestResult({ status: 'invalid_endpoint', message: '请先填写接口地址' })
    setTesting(true); setModelTestResult(null)
    try {
      const result = await api.testModelConnection({ ...(editingId ? { id: editingId } : {}), name: modelName.trim(), endpoint: endpoint.trim(), ...(apiKey.trim() ? { apiKey: apiKey.trim() } : {}) })
      setModelTestResult(result)
      if (result.status === 'ready') {
        await saveModel()
      } else {
        onToast(result.message || '连接测试完成')
      }
    } catch (error) {
      setModelTestResult({ status: 'error', message: error.message || 'Model connection failed.' })
      onToast(error.message || 'Model connection failed.')
    } finally { setTesting(false) }
  }
  const saveModel = async () => {
    const currentModel = editingId ? models.find(item => item.id === editingId) : null
    const defaultModel = editingId ? Boolean(currentModel?.defaultModel) : models.length === 0 || !models.some(item => item.defaultModel)
    const payload = { name: modelName.trim(), endpoint: endpoint.trim(), ...(apiKey.trim() ? { apiKey: apiKey.trim() } : {}), defaultModel }
    try {
      const result = editingId ? await api.updateModel(editingId, payload) : await api.createModel(payload)
      setModels(current => editingId ? current.map(item => item.id === editingId ? result : item) : [result, ...current]); if (result.defaultModel) onDefaultModelChange?.(result.name); onToast('模型配置已保存'); setApiKey(''); setShowForm(false); setModelTestResult(null)
    } catch (error) {
      setModelTestResult({ status: 'error', message: '模型配置未入库，请检查后端、PostgreSQL 和当前登录状态' })
      onToast(error?.message || '模型配置未入库，请检查后端和数据库')
    }
  }
  const selectDefaultModel = async model => {
    if (model.defaultModel) return
    try {
      const result = await api.updateModel(model.id, { name: model.name, endpoint: model.endpoint, defaultModel: true })
      setModels(current => current.map(item => item.id === model.id ? result : { ...item, defaultModel: false }))
      onDefaultModelChange?.(result.name)
      onToast('默认模型已切换')
    } catch { onToast('默认模型切换失败，配置未发生变更') }
  }
  const removeModel = model => setConfirmDialog({
    title: '删除模型配置',
    message: `确定删除模型配置“${model.name}”吗？删除后将无法恢复。`,
    confirmLabel: '删除配置',
    onConfirm: async () => {
      try {
        await api.deleteModel(model.id)
        setModels(current => {
          const remaining = current.filter(item => item.id !== model.id)
          if (model.defaultModel) onDefaultModelChange?.(remaining.find(item => item.defaultModel)?.name || remaining[0]?.name || '')
          return model.defaultModel && remaining.length && !remaining.some(item => item.defaultModel)
            ? remaining.map((item, index) => ({ ...item, defaultModel: index === 0 }))
            : remaining
        })
        onToast('模型配置已删除')
      } catch { onToast('模型删除失败，数据库未发生变更') }
    }
  })
  const inviteUser = async () => { if (!inviteEmail.trim()) return; const payload = { displayName: userName.trim() || inviteEmail.split('@')[0], email: inviteEmail.trim(), role: inviteRole, gender: inviteGender }; try { const result = await api.inviteUser(payload); setUsers(current => [...current, result]); onToast('用户已添加') } catch { setUsers(current => [...current, { id: `local-${Date.now()}`, displayName: payload.displayName, email: payload.email, role: inviteRole, gender: inviteGender, status: 'active' }]); onToast('后端未连接，用户暂存在当前页面') } setUserName(''); setInviteEmail('') }
  const changeRole = async (id, role) => { try { const result = await api.updateUserRole(id, role); setUsers(current => current.map(item => item.id === id ? result : item)) } catch { setUsers(current => current.map(item => item.id === id ? { ...item, role } : item)) } }
  const openUser = user => { setEditingUserId(user.id); setEditUserName(user.displayName || ''); setEditUserEmail(user.email || ''); setEditUserRole(user.role || 'developer'); setEditUserDepartment(user.departmentId || 'engineering'); setEditUserGender(user.gender || 'male'); setEditUserAvatarFile(null); setEditUserAvatarPreview(userAvatar(user) || defaultAvatarForGender(user.gender)); setUserFormOpen(true) }
  const saveUser = async () => {
    const payload = { displayName: editUserName.trim(), email: editUserEmail.trim(), role: editUserRole, departmentId: editUserDepartment, gender: editUserGender }
    try {
      let result = await api.updateUser(editingUserId, payload)
      if (editUserAvatarFile) result = await api.uploadUserAvatar(editingUserId, editUserAvatarFile)
      setUsers(current => current.map(item => item.id === editingUserId ? result : item)); onToast('用户信息已保存'); setUserFormOpen(false); setEditUserAvatarFile(null); setEditUserAvatarPreview('')
    } catch { setUsers(current => current.map(item => item.id === editingUserId ? { ...item, ...payload } : item)); onToast('后端未连接，用户信息暂存在当前页面') }
  }
  const removeUser = user => {
    if (user.id === 'demo-user') return onToast('当前管理员账号不可删除')
    setConfirmDialog({
      title: '删除用户',
      message: `确定删除用户“${user.displayName || user.email}”吗？删除后将无法恢复。`,
      confirmLabel: '删除用户',
      onConfirm: async () => {
        try { await api.deleteUser(user.id); setUsers(current => current.filter(item => item.id !== user.id)); onToast('用户已删除') } catch { onToast('用户删除失败') }
      }
    })
  }
  const openPasswordForm = user => { setPasswordUser(user); setNewPassword(''); setPasswordFormOpen(true) }
  const resetPassword = async () => { if (newPassword.length < 8) return onToast('密码至少需要 8 位'); try { await api.resetUserPassword(passwordUser.id, newPassword); setUsers(current => current.map(item => item.id === passwordUser.id ? { ...item, status: 'active' } : item)); onToast('密码已重置'); setPasswordFormOpen(false) } catch { onToast('密码重置失败') } }
  const openDepartment = department => { setEditingDepartmentId(department?.id || null); setDepartmentName(department?.name || ''); setDepartmentParent(department?.parentId || 'root'); setDepartmentFormOpen(true) }
  const saveDepartment = async () => { if (!departmentName.trim()) return; const payload = { name: departmentName.trim(), parentId: departmentParent }; try { const result = editingDepartmentId ? await api.updateDepartment(editingDepartmentId, payload) : await api.createDepartment(payload); setDepartments(current => editingDepartmentId ? current.map(item => item.id === editingDepartmentId ? result : item) : [...current, result]); onToast('部门已保存') } catch { const local = { id: editingDepartmentId || `local-${Date.now()}`, ...payload }; setDepartments(current => editingDepartmentId ? current.map(item => item.id === editingDepartmentId ? local : item) : [...current, local]); onToast('后端未连接，部门暂存在当前页面') } setDepartmentFormOpen(false) }
  const removeDepartment = async department => { if (department.id === 'root') return onToast('根组织不可删除'); try { await api.deleteDepartment(department.id); setDepartments(current => current.filter(item => item.id !== department.id)); onToast('部门已删除') } catch { onToast('部门删除失败，可能仍有下级部门或成员') } }
  const departmentRows = (parentId = '', depth = 0) => departments.filter(item => String(item.parentId || '') === parentId).flatMap(item => [<div className="department-row" key={item.id} style={{ paddingLeft: `${14 + depth * 22}px` }}><span className="tree-branch">{depth ? '└' : '⌂'}</span><div><b>{item.name}</b><small>{item.description || '组织部门'}</small></div><span className="department-count">{users.filter(user => user.departmentId === item.id).length} 人</span><button onClick={() => openDepartment(item)}>编辑</button>{item.id !== 'root' && <button className="danger-link" onClick={() => removeDepartment(item)}>删除</button>}</div>, ...departmentRows(item.id, depth + 1)])
  const openRole = role => { setEditingRoleId(role?.id || null); setRoleName(role?.name || ''); setRoleDescription(role?.description || ''); setRolePermission(role?.permissions?.[0] || '读写项目'); setRoleMenus(role?.menus || ['overview', 'projects']); setRoleFormOpen(true) }
  const saveRole = async () => { if (!roleName.trim()) return; const payload = { name: roleName.trim(), description: roleDescription.trim() || '自定义协作角色', permissions: [rolePermission], menus: roleMenus }; try { const result = editingRoleId ? await api.updateRole(editingRoleId, payload) : await api.createRole(payload); setRoles(current => editingRoleId ? current.map(item => item.id === editingRoleId ? result : item) : [...current, result]); onToast('角色已保存') } catch { const local = { id: editingRoleId || `local-${Date.now()}`, ...payload, builtIn: false }; setRoles(current => editingRoleId ? current.map(item => item.id === editingRoleId ? local : item) : [...current, local]); onToast('后端未连接，角色暂存在当前页面') } setRoleFormOpen(false) }
  const removeRole = async role => { if (role.builtIn) return onToast('系统角色不可删除'); try { await api.deleteRole(role.id); setRoles(current => current.filter(item => item.id !== role.id)); onToast('角色已删除') } catch { onToast('角色删除失败，可能仍有用户使用该角色') } }
  return <div className="settings-overlay" onMouseDown={e => e.target === e.currentTarget && onClose()}>
    <section className="settings-modal" role="dialog" aria-modal="true" aria-label="设置">
      <header className="settings-head"><div><small>WORKSPACE SETTINGS</small><h2>设置</h2></div><button className="settings-close" onClick={onClose}>×</button></header>
      <div className="settings-body">
        <nav className="settings-nav"><button className={section === 'models' ? 'selected' : ''} onClick={() => setSection('models')}><span>✦</span><div><b>模型配置</b><small>个人模型与默认服务</small></div></button><button className={section === 'ragflow' ? 'selected' : ''} onClick={() => setSection('ragflow')}><span>⌁</span><div><b>RAGFlow 知识库</b><small>连接和访问凭据</small></div></button>{canManageOrg && <><button className={section === 'roles' ? 'selected' : ''} onClick={() => setSection('roles')}><span>♧</span><div><b>权限角色</b><small>成员与项目访问范围</small></div></button><button className={section === 'users' ? 'selected' : ''} onClick={() => setSection('users')}><span>♙</span><div><b>用户管理</b><small>添加成员与调整角色</small></div></button><button className={section === 'departments' ? 'selected' : ''} onClick={() => setSection('departments')}><span>⌘</span><div><b>组织架构</b><small>部门树与归属关系</small></div></button></>}<button className={section === 'notifications' ? 'selected' : ''} onClick={() => setSection('notifications')}><span>◌</span><div><b>通知偏好</b><small>协作提醒与消息</small></div></button></nav>
        <div className="settings-content">
          {section === 'models' && <><div className="settings-title"><div><h3>模型配置</h3><p>配置后只对你本人可用，分享会话不会暴露 API Key。</p></div><button className="primary small" onClick={() => openModel()}>＋ 添加模型</button></div>{models.length ? models.map(model => <div className="model-card" key={model.id}><div className="model-card-main"><div className="model-logo">CW</div><div><strong>{model.name}</strong><span>{model.hasApiKey ? '已配置密钥 · 私人配置' : '未配置密钥'}</span></div></div><label className={`model-default-choice${model.defaultModel ? ' selected' : ''}`}><input type="radio" name="default-model" checked={Boolean(model.defaultModel)} onChange={() => selectDefaultModel(model)} />{model.defaultModel ? '默认模型' : '设为默认'}</label><button className="quiet" onClick={() => openModel(model)}>配置</button><button className="icon-danger" onClick={() => removeModel(model)}>×</button></div>) : <div className="empty-state settings-empty"><strong>还没有模型配置</strong><span>添加 CodeWeave 或其他 OpenAI 兼容模型后，Agent 会话才能调用它。</span></div>}{showForm && <div className="model-form"><div className="form-section-head"><div><b>{editingId ? '编辑模型' : '添加模型'}</b><small>OpenAI 兼容接口</small></div><button onClick={() => setShowForm(false)}>收起</button></div><label>默认模型 ID（兜底）<input value={modelName} onChange={e => setModelName(e.target.value)} /><small className="field-help">填写服务商支持的模型 ID；未提供模型列表接口时，将使用这个模型。</small></label><label>接口地址<input value={endpoint} onChange={e => { setEndpoint(e.target.value); setDiscoveredModels([]) }} /><button className="quiet discover-models-button" onClick={discoverModels} disabled={discoveringModels}>{discoveringModels ? '读取中…' : '查看可用模型'}</button></label>{discoveredModels.length > 0 && <div className="discovered-models"><small>选择一个模型作为默认模型</small>{discoveredModels.map(item => <button type="button" key={item.id} onClick={() => setModelName(item.id)}><strong>{item.id}</strong>{item.name && item.name !== item.id && <span>{item.name}</span>}</button>)}</div>}<label>API Key<input value={apiKey} onChange={e => setApiKey(e.target.value)} type="password" placeholder={editingId ? "Saved key is retained when blank" : "sk-••••••••••••"} /></label>{modelTestResult && <div className={`model-test-result ${modelTestResult.status === 'ready' ? 'success' : modelTestResult.status === 'error' ? 'error' : 'warning'}`}>{modelTestResult.message}</div>}<div className="model-form-actions"><button className="quiet" onClick={testConnection} disabled={testing}>{testing ? '测试中…' : '测试并保存'}</button><button className="primary" onClick={saveModel}>保存配置</button></div></div>}<div className="settings-note"><span>▣</span><p>测试成功后会自动保存模型配置；单独保存适合只修改名称或接口地址的场景。</p></div></>}
          {section === 'ragflow' && <><div className="settings-title"><div><h3>RAGFlow 知识库</h3><p>连接配置保存在当前账号中，项目只需要选择要使用的知识库。</p></div>{!ragflowConfigOpen && <button className="quiet small" onClick={() => setRagflowConfigOpen(true)}>编辑连接</button>}</div>{ragflowConfigOpen ? <div className="model-form"><div className="form-section-head"><div><b>连接配置</b><small>用于读取当前账号可见的知识库</small></div><div className="ragflow-form-actions"><Badge tone={ragflowTokenConfigured ? 'mint' : 'amber'}>{ragflowTokenConfigured ? '已配置 Token' : '未配置 Token'}</Badge>{ragflowTokenConfigured && <button className="quiet small" onClick={() => { setRagflowConfigOpen(false); setRagflowTest(null) }}>返回列表</button>}</div></div><label>RAGFlow URL<input value={ragflowUrl} onChange={e => setRagflowUrl(e.target.value)} placeholder="http://localhost:9380" /></label><label>Token<input value={ragflowToken} onChange={e => setRagflowToken(e.target.value)} type="password" placeholder={ragflowTokenConfigured ? '已保存，留空则继续使用' : '请输入 RAGFlow API Token'} /></label><div className="model-form-actions"><button className="quiet" onClick={testRagflow} disabled={ragflowTesting}>{ragflowTesting ? '测试中…' : '测试连接'}</button><button className="primary" onClick={saveRagflow} disabled={ragflowSaving}>{ragflowSaving ? '保存中…' : '保存连接配置'}</button></div>{ragflowTest && <div className={`model-test-result ${ragflowTest.tone}`}>{ragflowTest.text}</div>}</div> : <div className="model-card"><div className="model-card-main"><div className="model-logo">⌁</div><div><strong>{ragflowUrl}</strong><span>连接已保存，项目可以选择知识库</span></div></div><Badge tone="mint">已连接</Badge></div>}{!ragflowConfigOpen && <div className="dataset-picker settings-dataset-list"><small>当前账号可见的知识库</small>{ragflowDatasetList.length ? ragflowDatasetList.map(dataset => <div className="dataset-list-row" key={dataset.id}><b>{dataset.name || dataset.id}</b><em>{dataset.id}</em></div>) : <div className="settings-empty-copy">暂无可用知识库</div>}</div>}<div className="settings-note"><span>⌁</span><p>项目创建和编辑窗口会从这里读取知识库，并支持多选绑定。Token 只在服务端加密保存。</p></div></>}

          {section === 'roles' && <><div className="settings-title"><div><h3>权限角色</h3><p>用角色控制成员能看到什么、能执行什么。</p></div><button className="primary small" onClick={() => openRole()}>＋ 新建角色</button></div>{roleFormOpen && <div className="model-form role-form"><div className="form-section-head"><div><b>{editingRoleId ? '编辑角色' : '新建角色'}</b><small>自定义成员访问范围</small></div><button onClick={() => setRoleFormOpen(false)}>收起</button></div><label>角色名称<input value={roleName} onChange={e => setRoleName(e.target.value)} placeholder="例如：项目负责人" /></label><label>角色说明<input value={roleDescription} onChange={e => setRoleDescription(e.target.value)} placeholder="描述这个角色可以做什么" /></label><label>基础权限<select value={rolePermission} onChange={e => setRolePermission(e.target.value)}><option>读写项目</option><option>仅查看</option><option>执行 Agent</option><option>管理项目</option></select></label><div className="menu-checks"><small>可见菜单</small><div>{[['overview','总览'],['agent','Agent 工作台'],['projects','项目与工作区'],['conflicts','冲突中心'],['audit','审计与活动'],['settings','设置']].map(([id,label]) => <label key={id}><input type="checkbox" checked={roleMenus.includes(id)} onChange={e => setRoleMenus(current => e.target.checked ? [...new Set([...current, id])] : current.filter(item => item !== id))} />{label}</label>)}</div></div><div className="model-form-actions"><button className="quiet" onClick={() => setRoleFormOpen(false)}>取消</button><button className="primary" onClick={saveRole}>保存角色</button></div></div>}<div className="role-list"><div className="role-row role-head"><span>角色</span><span>成员</span><span>权限范围</span><span>操作</span></div>{roles.map(role => <div className="role-row" key={role.id}><div><b>{role.name}</b><small>{role.description}</small></div><span>{users.filter(user => user.role === role.id).length} 人</span><Badge tone={role.id === 'admin' ? 'violet' : role.id === 'viewer' ? 'mint' : 'cyan'}>{role.permissions?.[0] || '已授权'}</Badge><div className="role-actions"><button onClick={() => openRole(role)}>编辑</button>{!role.builtIn && <button className="danger-link" onClick={() => removeRole(role)}>删除</button>}</div></div>)}</div><div className="settings-note"><span>♧</span><p>系统角色不可删除；自定义角色只有在没有用户使用时才能删除。</p></div></>}
          {section === 'users' && <><div className="settings-title"><div><h3>用户管理</h3><p>添加工作台用户，维护角色、性别和部门归属。用户可以在编辑用户时上传自己的头像。</p></div></div><div className="invite-row"><input value={userName} onChange={e => setUserName(e.target.value)} placeholder="姓名" /><input value={inviteEmail} onChange={e => setInviteEmail(e.target.value)} placeholder="企业邮箱，例如 name@company.com" /><select value={inviteRole} onChange={e => setInviteRole(e.target.value)}>{roles.filter(role => role.id !== 'admin').map(role => <option value={role.id} key={role.id}>{role.name}</option>)}</select><select value={inviteGender} onChange={e => setInviteGender(e.target.value)} aria-label="性别"><option value="male">男</option><option value="female">女</option></select><button className="primary" onClick={inviteUser}>添加用户</button></div>{userFormOpen && <div className="model-form user-form"><div className="form-section-head"><div><b>编辑用户</b><small>修改人员资料和组织归属</small></div><button onClick={() => setUserFormOpen(false)}>收起</button></div><div className="user-avatar-editor"><label>头像</label><div className="user-avatar-preview"><img src={editUserAvatarPreview || userAvatar(users.find(item => item.id === editingUserId)) || defaultAvatarForGender(editUserGender)} alt="当前头像" onError={event => { event.currentTarget.src = defaultAvatarForGender(editUserGender) }} /></div><FileDropzone file={editUserAvatarFile} onFileChange={file => { setEditUserAvatarFile(file); if (!file) setEditUserAvatarPreview(userAvatar(users.find(item => item.id === editingUserId)) || defaultAvatarForGender(editUserGender)) }} accept="image/png,image/jpeg" hint="支持 PNG、JPG，单个文件不超过 5 MB" /></div><label>姓名<input value={editUserName} onChange={e => setEditUserName(e.target.value)} /></label><label>企业邮箱<input value={editUserEmail} onChange={e => setEditUserEmail(e.target.value)} /></label><label>角色<select value={editUserRole} onChange={e => setEditUserRole(e.target.value)}>{roles.map(role => <option value={role.id} key={role.id}>{role.name}</option>)}</select></label><label>所属部门<select value={editUserDepartment} onChange={e => setEditUserDepartment(e.target.value)}>{departments.map(department => <option value={department.id} key={department.id}>{department.name}</option>)}</select></label><label>性别<select value={editUserGender} onChange={e => setEditUserGender(e.target.value)}><option value="male">男</option><option value="female">女</option></select></label><div className="model-form-actions"><button className="quiet" onClick={() => setUserFormOpen(false)}>取消</button><button className="primary" onClick={saveUser}>保存用户</button></div></div>}{passwordFormOpen && <div className="model-form password-form"><div className="form-section-head"><div><b>重置密码</b><small>{passwordUser?.displayName || passwordUser?.email}</small></div><button onClick={() => setPasswordFormOpen(false)}>收起</button></div><label>新密码<input value={newPassword} onChange={e => setNewPassword(e.target.value)} type="password" placeholder="至少 8 位" /></label><div className="model-form-actions"><button className="quiet" onClick={() => setPasswordFormOpen(false)}>取消</button><button className="primary" onClick={resetPassword}>确认重置</button></div></div>}<div className="user-list">{users.map(user => <div className="user-row" key={user.id}><div className="user-avatar">{userAvatar(user) ? <img src={userAvatar(user)} alt="" onError={event => { event.currentTarget.src = defaultAvatarForGender(user.gender) }} /> : String(user.displayName || user.email).slice(0, 1).toUpperCase()}</div><div className="user-info"><b>{user.displayName}</b><small>{user.email} · {user.gender === 'female' ? '女' : '男'} · {departments.find(item => item.id === user.departmentId)?.name || '未分配部门'}</small></div><select value={user.role} onChange={e => changeRole(user.id, e.target.value)} disabled={user.id === 'demo-user'}>{roles.map(role => <option value={role.id} key={role.id}>{role.name}</option>)}</select><Badge tone={user.status === 'active' ? 'mint' : 'amber'}>{user.status === 'active' ? '已启用' : '待处理'}</Badge><button className="user-action" onClick={() => openUser(user)}>编辑</button><button className="user-action" onClick={() => openPasswordForm(user)}>重置密码</button>{user.id !== 'demo-user' && <button className="danger-link user-action" onClick={() => removeUser(user)}>删除</button>}</div>)}</div></>}
          {section === 'departments' && <><div className="settings-title"><div><h3>组织架构</h3><p>用部门树管理人员归属，角色和部门可以独立调整。</p></div><button className="primary small" onClick={() => openDepartment()}>＋ 新建部门</button></div>{departmentFormOpen && <div className="model-form department-form"><div className="form-section-head"><div><b>{editingDepartmentId ? '编辑部门' : '新建部门'}</b><small>组织树中的部门节点</small></div><button onClick={() => setDepartmentFormOpen(false)}>收起</button></div><label>部门名称<input value={departmentName} onChange={e => setDepartmentName(e.target.value)} placeholder="例如：技术平台组" /></label><label>上级部门<select value={departmentParent} onChange={e => setDepartmentParent(e.target.value)}>{departments.filter(department => department.id !== editingDepartmentId).map(department => <option value={department.id} key={department.id}>{department.name}</option>)}</select></label><div className="model-form-actions"><button className="quiet" onClick={() => setDepartmentFormOpen(false)}>取消</button><button className="primary" onClick={saveDepartment}>保存部门</button></div></div>}<div className="department-list">{departmentRows()}</div><div className="settings-note"><span>⌘</span><p>删除部门前需要先移走成员和下级部门，根组织始终保留。</p></div></>}
          {section === 'notifications' && <><div className="settings-title"><div><h3>通知偏好</h3><p>选择哪些协作事件需要提醒你。</p></div></div><div className="preference-list"><label><span><b>任务完成</b><small>Agent 完成任务或生成变更包时提醒</small></span><input type="checkbox" defaultChecked /></label><label><span><b>冲突与审批</b><small>出现代码冲突、审批请求时提醒</small></span><input type="checkbox" defaultChecked /></label><label><span><b>任务交接</b><small>有人交接任务或邀请你协作时提醒</small></span><input type="checkbox" defaultChecked /></label></div></>}
        </div>
      </div>
      <footer className="settings-foot"><span>设置只影响当前账号</span><button className="primary small" onClick={onClose}>完成</button></footer>
    </section>
    <ConfirmDialog dialog={confirmDialog} onCancel={() => setConfirmDialog(null)} onConfirm={() => { const action = confirmDialog?.onConfirm; setConfirmDialog(null); action?.() }} />
  </div>
}

function Metric({ label, value, detail, tone, icon }) { return <div className="metric"><div><small>{label}</small><i className={tone}>{icon}</i></div><strong>{value}</strong><p><Dot tone={tone} /> {detail}</p></div> }
function SessionCard({ item, open }) { const actor = item.latestActor || (item.latestRole === 'user' ? '你' : 'CodeWeave Agent'); const tone = item.latestRole === 'assistant' ? 'mint' : item.tone || 'cyan'; return <button className="session" onClick={open}><div className={`avatar ${tone}`}>{item.who}</div><div className="session-body"><strong>{item.project || '未选择项目'}</strong><small>{item.title || '协作任务'} · {formatActivityTime(item.latestAt || item.updatedAt)}</small><p><Badge tone={tone}>{actor}</Badge> <span className="session-latest">{item.latestMessage || item.detail || '等待最新动态'}</span></p></div><span>→</span></button> }

function formatActivityTime(value) { if (!value) return '刚刚'; const date = new Date(value); if (Number.isNaN(date.getTime())) return '刚刚'; const seconds = Math.max(0, Math.floor((Date.now() - date.getTime()) / 1000)); if (seconds < 60) return '刚刚'; if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`; if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`; return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' }) }
function latestSessionsByProject(items = []) { const latest = new Map(); [...items].sort((a, b) => String(b.updatedAt || '').localeCompare(String(a.updatedAt || ''))).forEach(item => { const key = item.projectId || item.project || item.id; if (!latest.has(key)) latest.set(key, item) }); return [...latest.values()] }
function Overview({ nav, open, items = [], metrics = { runningAgents: 0, pendingApprovals: 0, modelUsage: '0', membersOnline: 1 }, attention = [], activity = [] }) {
  const today = new Date().toLocaleDateString('zh-CN', { weekday: 'long', month: 'short', day: 'numeric' }).toUpperCase()
  const metricDetails = { runningAgents: metrics.runningAgents ? 'Agent 正在执行任务' : '当前没有运行中的 Agent', pendingApprovals: metrics.pendingApprovals ? '有事项等待你的决定' : '当前没有审批', modelUsage: metrics.modelUsage && metrics.modelUsage !== '0' ? '今日累计模型用量' : '今日尚未产生用量', membersOnline: `${metrics.membersOnline || 0} 位成员在线` }
  return <div className="page overview-page"><div className="page-head"><div><small>{today}</small><h1>早上好，Lin。</h1><p>这里是你和团队今天的协作状态。</p></div><button className="primary" onClick={() => nav('agent')}>＋ 新建任务</button></div><div className="metrics"><Metric label="运行中的 Agent" value={metrics.runningAgents} detail={metricDetails.runningAgents} tone="mint" icon="✦" /><Metric label="待处理审批" value={metrics.pendingApprovals} detail={metricDetails.pendingApprovals} tone="amber" icon="!" /><Metric label="今日模型用量" value={metrics.modelUsage} detail={metricDetails.modelUsage} tone="cyan" icon="◌" /><Metric label="协作成员" value={metrics.membersOnline} detail={metricDetails.membersOnline} tone="violet" icon="◉" /></div><div className="two-col"><section className="panel"><div className="panel-head"><div><small>PROJECT ACTIVITY</small><h2>项目最近协作</h2></div><button onClick={() => nav('agent')}>查看全部 →</button></div><div className="sessions">{items.length ? items.map(s => <SessionCard item={{ ...s, who: s.who || 'A', tone: s.tone || 'cyan', state: s.state || '运行中', detail: s.detail || '等待最新动态' }} open={() => open(s)} key={s.projectId || s.project || s.id} />) : <div className="empty-state"><strong>还没有项目协作</strong><span>创建任务并发送第一条指令后，最新进展会显示在这里。</span><button className="primary small" onClick={() => nav('agent')}>创建任务</button></div>}</div></section><section className="panel"><div className="panel-head"><div><small>NEEDS ATTENTION</small><h2>需要你关注</h2></div><button onClick={() => nav('conflicts')}>···</button></div>{attention.length ? attention.map(item => <div className={`notice ${item.tone || 'warning'}`} key={item.id || item.title}><b>{item.title}</b><p>{item.detail}</p><button onClick={() => nav(item.route || 'conflicts')}>去处理 →</button></div>) : <div className="empty-state"><strong>当前没有待处理事项</strong><span>冲突、审批和构建活动会在产生后显示在这里。</span></div>}</section></div><section className="panel activity"><div className="panel-head"><div><small>TEAM ACTIVITY</small><h2>团队最近动态</h2></div><button onClick={() => nav('audit')}>进入审计 →</button></div>{activity.length ? <div className="activity-grid">{activity.map(item => <p key={item.id || `${item.createdAt}-${item.target}`}><Dot tone={item.tone || 'cyan'} /><span><b>{item.actor || '团队成员'}</b> {item.action || '更新了工作区'}<small>{item.target || ''} · {formatActivityTime(item.createdAt)}</small></span></p>)}</div> : <div className="activity-grid"><div className="empty-state"><strong>暂无团队动态</strong><span>团队协作事件会在任务创建后记录。</span></div></div>}</section></div>
}

function Agent({ session, project, nav, onShare, onToast, profile, models = [], onSessionChange }) {
  const [text, setText] = useState('')
  const [dshSessionId, setDshSessionId] = useState(session?.dshSessionId || session?.id || '')
  const [messages, setMessages] = useState([])
  const [busy, setBusy] = useState(false)
  const [agentStatus, setAgentStatus] = useState('待输入')
  const [executionMode, setExecutionMode] = useState(() => project?.id ? localStorage.getItem(`codeweave-execution-${project.id}`) || 'dsh' : 'dsh')
  const [waiting, setWaiting] = useState(false)
  const [promptHistory, setPromptHistory] = useState([])
  const [historyIndex, setHistoryIndex] = useState(-1)
  const [pendingFiles, setPendingFiles] = useState([])
  const [switchingModel, setSwitchingModel] = useState(false)
  const [selectedModelId, setSelectedModelId] = useState(() => localStorage.getItem('codeweave-selected-model') || '')
  const scrollRef = useRef(null)
  const inputRef = useRef(null)
  const fileInputRef = useRef(null)
  const followBottom = useRef(true)
  const seenEvents = useRef(new Set())
  useEffect(() => {
    if (dshSessionId || !models.length || models.some(item => item.id === selectedModelId)) return
    const next = models.find(item => item.defaultModel)?.id || models[0].id
    setSelectedModelId(next)
    localStorage.setItem('codeweave-selected-model', next)
  }, [models, selectedModelId, dshSessionId])
  useEffect(() => {
    if (followBottom.current && scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight
  }, [messages, waiting])
  const title = session?.title || '新建协作任务'
  const sessionKey = session?.id || 'new-session'
  useEffect(() => {
    setDshSessionId(session?.dshSessionId || session?.id || '')
    if (session?.modelId) setSelectedModelId(session.modelName ? `${session.modelId}::${session.modelName}` : (models.find(item => item.configId === session.modelId)?.id || session.modelId))
    setMessages([])
    setWaiting(false)
    setAgentStatus('待输入')
    seenEvents.current.clear()
  }, [sessionKey])
  useEffect(() => {
    if (!dshSessionId) return undefined
    let disposed = false
    let loading = false
    const loadEvents = async () => {
      if (loading) return
      loading = true
      try {
        const result = await api.agentEvents(dshSessionId)
        if (disposed) return
        const additions = []
        for (const item of result?.items || []) {
          const eventId = item.eventId || `${item.createdAt || ''}:${item.payload || ''}`
          if (seenEvents.current.has(eventId)) continue
          seenEvents.current.add(eventId)
          for (const update of readChatEvents(item.payload)) {
            if (update.status) {
              setAgentStatus({ running: '正在回复', complete: '已完成', error: '回复失败', stopped: '已中断' }[update.status])
              setWaiting(update.status === 'running')
            }
            if (update.text) {
              if (update.role === 'assistant') setWaiting(false)
              additions.push({ ...update, id: update.id || eventId })
            }
          }
        }
        if (additions.length) setMessages(current => {
          const next = [...current]
          for (const message of additions) {
            const index = next.findIndex(item => item.id === message.id)
            if (index < 0) next.push(message)
            else next[index] = message
          }
          return next
        })
      } catch {
        if (!disposed) { setWaiting(false); setAgentStatus('连接中断') }
      } finally { loading = false }
    }
    loadEvents()
    const timer = setInterval(loadEvents, 1000)
    return () => { disposed = true; clearInterval(timer) }
  }, [dshSessionId])
  const chooseFiles = files => {
    const next = Array.from(files || []).filter(file => file.size <= 50 * 1024 * 1024)
    setPendingFiles(current => [...current, ...next].slice(0, 10))
    if (next.length < Array.from(files || []).length) onToast?.('单个文件不能超过 50 MB')
  }
  const removeFile = file => setPendingFiles(current => current.filter(item => item !== file))
  const send = async () => {
    const value = text.trim()
    if ((!value && pendingFiles.length === 0) || busy || switchingModel) return
    setText('')
    setPromptHistory(current => [...current.filter(item => item !== value), value].slice(-30))
    setHistoryIndex(-1)
    followBottom.current = true
    setMessages(current => [...current, { role: 'user', text: value }])
    setBusy(true)
    setWaiting(true)
    setAgentStatus('正在回复')
    try {
      let id = dshSessionId
      if (!id) {
        const selectedModel = models.find(item => item.id === selectedModelId)
        const created = await api.createAgentSession({ executionMode, title, projectId: project?.id || '', projectName: project?.name || '', ...(selectedModelId ? { modelId: selectedModelId, modelName: selectedModel?.modelId || selectedModel?.name || '' } : {}) })
        if (!created?.sessionId) throw new Error('会话创建失败，请稍后重试。')
        id = created.sessionId
        setDshSessionId(id)
        onSessionChange?.(created.session || { id, dshSessionId: id, title, projectId: project?.id || '', project: project?.name || '未选择项目' })
      }
      const uploads = []
      for (const file of pendingFiles) uploads.push(await api.uploadAgentFile(id, file))
      await api.sendAgentMessage(id, value, uploads.filter(item => !item.image).map(item => item.receiptId), uploads.flatMap(item => item.image ? [item.image] : []))
      setPendingFiles([])
    } catch (error) {
      setWaiting(false)
      setAgentStatus('连接失败')
      setMessages(current => [...current, { role: 'error', text: error.message || '消息发送失败，请稍后重试。' }])
    } finally { setBusy(false) }
  }
  const interrupt = async () => {
    if (!dshSessionId || !waiting) return
    try { await api.cancelAgentSession(dshSessionId); setWaiting(false); setAgentStatus('已中断') }
    catch (error) { onToast?.(error.message || '中断请求失败') }
  }
  const changeModel = async event => {
    const modelId = event.target.value
    const selected = models.find(item => item.id === modelId)
    setSwitchingModel(true)
    try {
      if (dshSessionId) await api.selectAgentModel(dshSessionId, modelId, selected?.modelId || selected?.name || '')
      setSelectedModelId(modelId)
      localStorage.setItem('codeweave-selected-model', modelId)
      onToast?.('下一条消息将使用所选模型')
    } catch (error) { onToast?.(error.message || '模型切换失败') }
    finally { setSwitchingModel(false) }
  }
  return <div className="agent-simple">
    <section className="chat chat-main">
      <div className="chat-head simple-head">
        <div><small>COLLABORATION SESSION</small><h2>{title}</h2></div>
        <div className="agent-head-actions"><Badge tone={agentStatus === '连接失败' ? 'amber' : agentStatus === '已完成' ? 'mint' : 'cyan'}>{agentStatus}</Badge><button className="quiet" onClick={onShare}>↗ 分享</button></div>
      </div>
      <div className="agent-context-bar">
        <button className="project-select" onClick={() => nav('projects')}><span>⌁</span><b>{project?.name || '选择项目'}</b><em>{project ? (project.source || '工作区已连接') : '尚未选择项目'}</em><strong>→</strong></button>
        <span>{project ? (project.repoUrl || '服务器共享工作区') : 'Git 仓库和快照都会准备到服务器共享工作区'}</span>
      </div>
      <div className="chat-scroll" ref={scrollRef} onScroll={event => { const el = event.currentTarget; followBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80 }}>{messages.length ? <div className="agent-messages">{messages.map((message, index) => { const isUser = message.role === 'user'; const fallback = isUser ? defaultAvatarForGender(profile?.gender) : DEFAULT_AVATARS.assistant; const avatarUrl = isUser ? userAvatar(profile) : DEFAULT_AVATARS.assistant; return <div className={`agent-message ${message.role}`} key={message.id || `${message.role}-${index}`}><AvatarImage src={avatarUrl} fallback={fallback} fallbackText={isUser ? '你' : 'AI'} alt={isUser ? '你的头像' : 'AI 头像'} /><div className="message-body"><span className="message-author">{isUser ? '你' : 'CodeWeave Agent'}</span><p>{message.text}</p></div></div>})}{waiting && <div className="reply-pending" role="status"><span className="reply-dots" aria-hidden="true"><i /><i /><i /></span>正在回复</div>}</div> : <div className="empty-state agent-empty"><strong>{project ? '准备开始协作' : '从一句话开始'}</strong><span>{project ? '描述你想完成的事情，Agent 会在当前项目中规划、执行并同步进度。' : '描述你想完成的事情，Agent 会在当前会话中规划、执行并同步进度。'}</span>{!project && <button className="primary small" onClick={() => nav('projects')}>先选择项目</button>}</div>}</div>
      <form className="composer chat-composer" onSubmit={event => { event.preventDefault(); send() }} onDragOver={event => event.preventDefault()} onDrop={event => { event.preventDefault(); chooseFiles(event.dataTransfer.files) }} onPaste={event => { const files = Array.from(event.clipboardData?.files || []); if (files.length) { event.preventDefault(); chooseFiles(files) } }}>
        {pendingFiles.length > 0 && <div className="composer-files" aria-label="待发送附件">{pendingFiles.map(file => <div className="composer-file" key={`${file.name}-${file.lastModified}`}><span>⌑</span><b title={file.name}>{file.name}</b><small>{Math.max(1, Math.round(file.size / 1024))} KB</small><button type="button" onClick={() => removeFile(file)} aria-label={`移除 ${file.name}`}>×</button></div>)}</div>}
        <textarea ref={inputRef} aria-label="消息" rows={2} value={text} onChange={e => { setText(e.target.value); setHistoryIndex(-1) }} onKeyDown={e => {
          if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); send(); return }
          if (e.key === 'Enter' && (e.metaKey || e.ctrlKey) && !e.nativeEvent.isComposing) { e.preventDefault(); send(); return }
          if (e.key === 'Escape') { e.preventDefault(); setText(''); setHistoryIndex(-1); return }
          if (e.key === 'ArrowUp' && !e.shiftKey && !text.includes('\n')) {
            e.preventDefault(); setHistoryIndex(current => { const next = Math.max(0, current < 0 ? promptHistory.length - 1 : current - 1); setText(promptHistory[next] || ''); return next })
          }
          if (e.key === 'ArrowDown' && !e.shiftKey && !text.includes('\n')) {
            e.preventDefault(); setHistoryIndex(current => { const next = Math.min(promptHistory.length, current + 1); setText(next === promptHistory.length ? '' : promptHistory[next] || ''); return next })
          }
          if (e.key === 'Tab') {
            e.preventDefault()
            const start = e.currentTarget.selectionStart
            const end = e.currentTarget.selectionEnd
            const next = `${text.slice(0, start)}  ${text.slice(end)}`
            setText(next)
            requestAnimationFrame(() => { e.currentTarget.selectionStart = e.currentTarget.selectionEnd = start + 2 })
          }
        }} placeholder="输入消息… Enter 发送，Shift + Enter 换行" disabled={busy} />
        <div className="composer-footer"><div className="composer-tools"><button type="button" className="composer-attach" onClick={() => fileInputRef.current?.click()} aria-label="上传文件" title="上传文件"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m21.44 11.05-8.49 8.49a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.82-2.82l8.48-8.49" /></svg></button><input ref={fileInputRef} type="file" multiple hidden onChange={event => { chooseFiles(event.target.files); event.target.value = '' }} /><small>Enter 发送 · Shift + Enter 换行 · 拖拽或粘贴文件</small></div>{waiting ? <button type="button" className="composer-stop" aria-label="中断回复" title="中断回复" onClick={interrupt}><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="7" y="7" width="10" height="10" rx="1" /></svg></button> : <button type="submit" aria-label="发送消息" disabled={busy || (!text.trim() && pendingFiles.length === 0)}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 19V5M6 11l6-6 6 6" /></svg></button>}</div>
      </form>
    </section>
    <aside className="agent-rail">
      <div className="rail-section"><small>当前状态</small><div className="rail-status"><Dot tone={agentStatus === '连接失败' ? 'amber' : agentStatus === '已完成' ? 'mint' : 'cyan'} /> {agentStatus}</div></div>
      <div className="rail-section"><small>执行方式</small><select className="execution-select" value={executionMode} onChange={event => { setExecutionMode(event.target.value); if (project?.id) localStorage.setItem(`codeweave-execution-${project.id}`, event.target.value) }}><option value="dsh">CodeWeave Agent 工作台</option><option value="runner" disabled>企业 Runner（未配置）</option></select><div className="rail-muted execution-help">当前会话使用 {executionMode === 'dsh' ? 'CodeWeave Agent 工作台' : '企业 Runner'}。新消息会在下一次提交时生效。</div></div>
      <div className="rail-section"><small>当前模型</small><select className="execution-select" value={selectedModelId || models.find(item => item.defaultModel)?.id || models[0]?.id || ''} onChange={changeModel} disabled={!models.length || switchingModel || busy || waiting}><option value="" disabled>{models.length ? '选择模型' : '未配置模型'}</option>{selectedModelId && !models.some(item => item.id === selectedModelId) && <option value={selectedModelId}>{session?.modelName || selectedModelId.split('::').pop()}（当前会话）</option>}{models.map(model => <option value={model.id} key={model.id}>{model.name}{model.providerName && model.providerName !== model.name ? ` · ${model.providerName}` : ''}{model.defaultModel ? '（默认）' : ''}</option>)}</select><div className="rail-muted execution-help">优先显示服务商可用模型；没有模型列表接口时使用配置中的模型 ID。</div></div>
      <div className="rail-section"><small>协作成员</small><div className="rail-muted">创建任务后可邀请团队成员</div></div>
      <button className="rail-share" onClick={onShare}>↗ 一键分享只读会话</button>
    </aside>
  </div>
}

function DshConsole({ url, project, nav }) {
  const frameUrl = (() => {
    try {
      const parsed = new URL(url)
      const parentHost = window.location.hostname
      const localHosts = host => host === 'localhost' || host === '::1' || /^127(?:\.\d{1,3}){3}$/.test(host)
      if (localHosts(parsed.hostname) && localHosts(parentHost)) parsed.hostname = parentHost
      return parsed.href
    } catch {
      return url
    }
  })()
  const projectLabel = project
    ? project.workspaceReady ? `CodeWeave 项目：${project.name} · 已绑定服务器工作区` : `CodeWeave 项目：${project.name} · 服务器工作区未就绪`
    : '项目、会话、附件、引导和快捷键由 DSH 原生控制台提供'
  return <div className="dsh-console-shell"><header className="dsh-console-head"><div><small>CODEWEAVE</small><b>CodeWeave Agent 工作台</b><span>{projectLabel}</span></div><div><button className="quiet small" onClick={() => nav('projects')}>切换 CodeWeave 项目</button><a className="quiet small" href={url} target="_blank" rel="noreferrer">在新窗口打开 ↗</a></div></header><iframe className="dsh-console-frame" src={frameUrl} title="CodeWeave Agent 工作台" allow="clipboard-read; clipboard-write" /></div>
}

function DshUnavailable({ nav }) {
  return <div className="dsh-unavailable"><div><small>CODEWEAVE</small><h2>CodeWeave 控制台暂时不可用</h2><p>请确认 CodeWeave Agent 工作台已启动，并检查后端里的控制台地址和认证 Token。</p><button className="primary small" onClick={() => window.location.reload()}>重新连接</button><button className="quiet small" onClick={() => nav('projects')}>返回项目</button></div></div>
}

function DshProjectNeedsDirectory({ project, nav }) {
  const status = project?.workspaceStatus || 'preparing'
  return <div className="dsh-unavailable"><div><small>CODEWEAVE PROJECT</small><h2>{status === 'waiting-upload' ? '等待服务器接收快照' : status === 'error' ? '服务器工作区准备失败' : '服务器工作区准备中'}</h2><p>{project?.workspaceError || '项目目录由服务器统一准备，准备完成后团队成员可以进入同一个工作区。'}</p><button className="primary small" onClick={() => nav('projects')}>返回项目</button></div></div>
}

function Conflicts({ nav }) { return <div className="page"><div className="page-head"><div><small>COLLABORATION CONTROL</small><h1>冲突中心</h1><p>把协作中的不确定性集中到这里处理。</p></div><button className="quiet" onClick={() => nav('agent')}>返回工作台</button></div><section className="panel"><div className="empty-state"><strong>暂无协作冲突</strong><span>当多人同时修改同一项目或文件时，冲突会显示在这里。</span></div></section></div> }

function FileDropzone({ file, onFileChange, accept, hint }) {
  const inputRef = useRef(null)
  const [dragging, setDragging] = useState(false)
  const choose = files => {
    const next = files?.[0]
    if (next) onFileChange(next)
  }
  return <div className={`file-dropzone${dragging ? ' dragging' : ''}${file ? ' has-file' : ''}`}
    onDragOver={event => { event.preventDefault(); setDragging(true) }}
    onDragLeave={() => setDragging(false)}
    onDrop={event => { event.preventDefault(); setDragging(false); choose(event.dataTransfer.files) }}
    onClick={() => inputRef.current?.click()}
    onKeyDown={event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); inputRef.current?.click() } }}
    role="button" tabIndex="0" aria-label="选择文件">
    <input ref={inputRef} type="file" accept={accept} onChange={event => choose(event.target.files)} />
    {file ? <>
      <span className="file-dropzone-icon selected">✓</span>
      <span className="file-dropzone-copy"><strong>{file.name}</strong><small>{formatFileSize(file.size)} · 已准备上传</small></span>
      <button type="button" className="file-dropzone-remove" onClick={event => { event.stopPropagation(); onFileChange(null); if (inputRef.current) inputRef.current.value = '' }} aria-label="移除文件">×</button>
    </> : <>
      <span className="file-dropzone-icon">↑</span>
      <span className="file-dropzone-copy"><strong>拖拽文件到这里，或点击选择</strong><small>{hint}</small></span>
      <span className="file-dropzone-action">选择文件</span>
    </>}
  </div>
}

function formatFileSize(size = 0) {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${Math.ceil(size / 1024)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function Projects({ nav, projects = [], onProjectsChange, onOpenProject, onToast }) {
  const [formOpen, setFormOpen] = useState(false)
  const [editingId, setEditingId] = useState(null)
  const [name, setName] = useState('')
  const [type, setType] = useState('git')
  const [repoUrl, setRepoUrl] = useState('')
  const [path, setPath] = useState('')
  const [snapshotFile, setSnapshotFile] = useState(null)
  const [branch, setBranch] = useState('main')
  const [description, setDescription] = useState('')
  const [ragflowEnabled, setRagflowEnabled] = useState(false)
  const [ragflowDatasets, setRagflowDatasets] = useState([])
  const [ragflowDatasetIds, setRagflowDatasetIds] = useState([])
  const [ragflowLoading, setRagflowLoading] = useState(false)
  const [ragflowMessage, setRagflowMessage] = useState(null)
  const [syncingId, setSyncingId] = useState(null)
  const openForm = project => {
    setEditingId(project?.id || null); setName(project?.name || ''); setType(project?.type || 'git')
    setRepoUrl(project?.repoUrl || ''); setPath(''); setSnapshotFile(null); setBranch(project?.branch || 'main'); setDescription(project?.description || '')
    setRagflowEnabled(Boolean(project?.ragflowConfigured || project?.ragflowDatasetId || project?.ragflowDatasetIds?.length)); setRagflowDatasets([]); setRagflowDatasetIds(project?.ragflowDatasetIds || (project?.ragflowDatasetId ? [project.ragflowDatasetId] : [])); setRagflowMessage(null)
    setFormOpen(true)
  }
  const loadRagflowDatasets = async () => {
    setRagflowLoading(true); setRagflowMessage(null)
    try {
      const result = await api.ragflowDatasets()
      const items = result?.items || []
      setRagflowDatasets(items)
      if (items.length) {
        const selectedIds = ragflowDatasetIds.filter(id => items.some(item => item.id === id))
        setRagflowDatasetIds(selectedIds.length ? selectedIds : [items[0].id]); setRagflowMessage({ tone: 'success', text: `已获取 ${items.length} 个可用知识库，请选择要绑定的库` })
      } else setRagflowMessage({ tone: 'warning', text: '当前 Token 没有可用的知识库' })
    } catch (error) { setRagflowMessage({ tone: 'error', text: error.message || '获取知识库失败，请检查 URL 和 Token' }) }
    finally { setRagflowLoading(false) }
  }
  const save = async () => {
    if (!name.trim()) return onToast?.('请填写项目名称')
    const selectedDatasets = ragflowDatasets.filter(item => ragflowDatasetIds.includes(item.id))
    const payload = { name: name.trim(), type, repoUrl: repoUrl.trim(), branch: branch.trim() || 'main', description: description.trim(), ragflowEnabled, ragflowDatasetIds, ragflowDatasetNames: selectedDatasets.map(item => item.name || item.id) }
    try {
      let result = editingId ? await api.updateProject(editingId, payload) : await api.createProject(payload)
      if (type === 'snapshot' && snapshotFile) {
        try { const uploaded = await api.uploadSnapshot(result.id, snapshotFile); result = { ...result, ...uploaded, workspaceReady: uploaded.status === 'ready', workspaceStatus: uploaded.status, path: uploaded.objectName, snapshotName: uploaded.originalName } } catch (error) { onToast?.(error.message || '项目已保存，但快照上传失败'); return }
      }
      onProjectsChange?.(current => editingId ? current.map(item => item.id === editingId ? result : item) : [result, ...current])
      onToast?.(editingId ? '项目已更新' : '项目已创建')
      setFormOpen(false)
    } catch {
      const local = { id: editingId || `local-project-${Date.now()}`, ...payload, source: type === 'git' ? 'Git 仓库' : '快照项目', workspaceReady: false, workspaceStatus: 'preparing', status: 'preparing', activeTasks: 0, ragflowConfigured: ragflowEnabled && Boolean(ragflowDatasetIds.length) }
      onProjectsChange?.(current => editingId ? current.map(item => item.id === editingId ? local : item) : [local, ...current])
      onToast?.('后端未连接，项目暂存在当前页面')
      setFormOpen(false)
    }
  }
  const remove = async project => {
    if (!window.confirm(`确定删除项目“${project.name}”吗？`)) return
    try { await api.deleteProject(project.id); onProjectsChange?.(current => current.filter(item => item.id !== project.id)); onToast?.('项目已删除') }
    catch { onToast?.('项目删除失败') }
  }
  const sync = async project => {
    setSyncingId(project.id)
    try { const result = await api.syncProject(project.id); onProjectsChange?.(current => current.map(item => item.id === project.id ? result : item)); onToast?.('服务器工作区已重新准备') }
    catch (error) { onProjectsChange?.(current => current.map(item => item.id === project.id ? { ...item, workspaceStatus: 'error', workspaceError: error.message || '服务器工作区准备失败' } : item)); onToast?.(error.message || '服务器工作区准备失败') }
    finally { setSyncingId(null) }
  }
  return <div className="page">
    <div className="page-head"><div><small>YOUR WORKSPACES</small><h1>项目与工作区</h1><p>项目目录由服务器统一管理，所有有权限的成员进入同一个工作区。</p></div><button className="primary small" onClick={() => openForm()}>＋ 新建项目</button></div>
    <div className="workspace-guide"><div><b>一个项目，多个协作会话</b><span>Git 仓库在服务器 clone，快照在服务器解压，Agent 使用共享目录。</span></div><div className="workspace-types"><span><i className="type-dot git" />Git 仓库</span><span><i className="type-dot snapshot" />快照</span><span><i className="type-dot local" />服务器目录</span></div></div>
    <div className="project-grid">{projects.length ? projects.map(project => <article className="project" key={project.id}><div className="project-top"><Badge tone={project.workspaceReady ? 'mint' : project.workspaceStatus === 'error' ? 'danger' : 'amber'}>{project.workspaceReady ? '服务器可用' : project.workspaceStatus === 'error' ? '准备失败' : project.workspaceStatus === 'waiting-upload' ? '等待快照' : '准备中'}</Badge><button className="project-more" onClick={() => openForm(project)}>编辑</button></div><h2>{project.name}</h2><p className="project-source">{project.source || (project.type === 'git' ? 'Git 仓库' : project.type === 'snapshot' ? '快照项目' : '服务器目录')}</p><p className="project-location">{project.repoUrl || '服务器共享工作区'}</p>{project.ragflowConfigured && <span className="knowledge-bound">⌁ 已绑定 RAGFlow · {(project.ragflowDatasetNames || []).join('、') || `${(project.ragflowDatasetIds || []).length} 个知识库`}</span>}{project.workspaceError && <span className="field-help">{project.workspaceError}</span>}<footer><span>{project.activeTasks || 0} 个活跃任务</span><div>{(project.type === 'git' || !project.workspaceReady) && <button disabled={syncingId === project.id} onClick={() => sync(project)}>{syncingId === project.id ? '准备中…' : project.type === 'git' && project.workspaceReady ? '同步服务器' : '重新准备'}</button>}<button onClick={() => onOpenProject?.(project)}>进入工作区 →</button><button className="danger-link" onClick={() => remove(project)}>删除</button></div></footer></article>) : <div className="empty-state project-empty"><strong>还没有项目</strong><span>先添加一个 Git 仓库或快照，Agent 会在服务器共享目录里执行任务。</span><button className="primary small" onClick={() => openForm()}>＋ 创建第一个项目</button></div>}<button className="project new" onClick={() => openForm()}><strong>＋</strong><b>新建项目</b><small>Git、快照或服务器目录</small></button></div>
    {formOpen && <div className="project-form-overlay" onMouseDown={event => event.target === event.currentTarget && setFormOpen(false)}><section className="project-form" role="dialog" aria-modal="true"><header><div><small>{editingId ? 'EDIT WORKSPACE' : 'NEW WORKSPACE'}</small><h2>{editingId ? '编辑项目' : '新建项目'}</h2></div><button className="settings-close" onClick={() => setFormOpen(false)}>×</button></header><div className="project-form-body"><label>项目名称<input value={name} onChange={event => setName(event.target.value)} placeholder="例如：支付服务" /></label><label>项目类型<select value={type} onChange={event => { setType(event.target.value); setSnapshotFile(null) }}><option value="git">Git 仓库</option><option value="snapshot">快照项目</option></select></label>{type === 'git' && <><label>仓库地址<input value={repoUrl} onChange={event => setRepoUrl(event.target.value)} placeholder="https://github.com/company/repository.git" /></label><label>默认分支<input value={branch} onChange={event => setBranch(event.target.value)} placeholder="main" /></label><p className="field-help">保存后由服务器 clone 到共享工作区，团队成员使用同一份目录。</p></>}{type === 'snapshot' && <div className="project-file-field"><label>上传快照文件</label><FileDropzone file={snapshotFile} onFileChange={setSnapshotFile} accept=".zip" hint="支持 ZIP 快照，文件会上传并解压到服务器共享工作区" /></div>}<div className="knowledge-binding"><div className="binding-head"><div><b>使用 RAGFlow 知识库</b><small>连接配置在“设置 → RAGFlow”，这里可同时选择多个知识库</small></div><label className="switch"><input type="checkbox" checked={ragflowEnabled} onChange={event => { const enabled = event.target.checked; setRagflowEnabled(enabled); setRagflowMessage(null); if (enabled) loadRagflowDatasets() }} /><span /></label></div>{ragflowEnabled && <div className="binding-fields"><button className="quiet small" onClick={loadRagflowDatasets} disabled={ragflowLoading}>{ragflowLoading ? '获取中…' : '刷新可用知识库'}</button>{ragflowDatasets.length > 0 && <div className="dataset-picker"><small>选择要绑定的知识库（可多选）</small>{ragflowDatasets.map(dataset => <label key={dataset.id}><input type="checkbox" checked={ragflowDatasetIds.includes(dataset.id)} onChange={event => setRagflowDatasetIds(current => event.target.checked ? [...new Set([...current, dataset.id])] : current.filter(id => id !== dataset.id))} /><span><b>{dataset.name || dataset.id}</b><em>{dataset.id}</em></span></label>)}</div>}{ragflowMessage && <div className={`binding-message ${ragflowMessage.tone}`}>{ragflowMessage.text}</div>}</div>}</div><label>项目说明<textarea value={description} onChange={event => setDescription(event.target.value)} placeholder="说明这个项目用于什么协作" /></label></div><footer><button className="quiet small" onClick={() => setFormOpen(false)}>取消</button><button className="primary small" onClick={save}>保存项目</button></footer></section></div>}
  </div>
}

function Audit() { return <div className="page"><div className="page-head"><div><small>SECURITY & OBSERVABILITY</small><h1>审计与活动</h1><p>查看团队、Agent 和执行节点发生过什么。</p></div><button className="quiet">导出审计报表 ↓</button></div><div className="audit-filter"><input placeholder="⌕ 搜索用户、操作或会话" /><button>执行者：全部⌄</button><button>事件类型：全部⌄</button><button>状态：全部⌄</button></div><section className="panel audit"><div className="empty-state"><strong>暂无审计记录</strong><span>登录、任务、Agent 执行和分享操作会记录在这里。</span></div></section></div> }

export default function App() {
  const [logged, setLogged] = useState(Boolean(localStorage.getItem('codeweave-token')))
  const [active, setActive] = useState(() => localStorage.getItem('codeweave-active-page') || 'overview')
  const [session, setSession] = useState(null)
  const [selectedProject, setSelectedProject] = useState(null)
  const [items, setItems] = useState([])
  const [summary, setSummary] = useState({ metrics: { runningAgents: 0, pendingApprovals: 0, modelUsage: '0', membersOnline: 1 }, projects: [], attention: [], activity: [] })
  const [defaultModelName, setDefaultModelName] = useState('')
  const [models, setModels] = useState([])
  const [dshConsoleUrl, setDshConsoleUrl] = useState('')
  const [toast, setToast] = useState('')
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [sidebarCollapsed, setSidebarCollapsed] = useState(() => localStorage.getItem('codeweave-sidebar-collapsed') === 'true')
  const [access, setAccess] = useState({ menus: ['overview', 'agent', 'projects', 'conflicts', 'audit', 'settings'], permissions: ['*'] })
  const [profile, setProfile] = useState(() => normalizeProfile({ gender: 'male' }))
  const nav = id => { setActive(id); localStorage.setItem('codeweave-active-page', id) }
  const login = async (email, password) => {
    try {
      const result = await api.login(email, password)
      localStorage.setItem('codeweave-token', result.token)
    } catch (error) {
      // The prototype remains explorable when the local API is not running.
      if (!error.message.startsWith('请求失败')) throw error
    }
    setLogged(true)
  }
  const logout = () => { localStorage.removeItem('codeweave-token'); localStorage.removeItem('codeweave-selected-project'); localStorage.removeItem('codeweave-session-id'); localStorage.removeItem('codeweave-active-page'); setLogged(false); setActive('overview'); setSelectedProject(null); setSession(null) }
  useEffect(() => {
    if (!logged) return
    const refreshWorkspace = () => Promise.allSettled([api.sessions(), api.summary(), api.me(), api.projects(), api.models(), api.modelCatalog(), api.profile()]).then(([sessionResult, summaryResult, accessResult, projectResult, modelResult, catalogResult, profileResult]) => {
      if (sessionResult.status === 'fulfilled') { const available = sessionResult.value?.items || []; setItems(latestSessionsByProject(available)); const savedId = localStorage.getItem('codeweave-session-id'); const saved = available.find(item => item.id === savedId); if (saved) setSession(saved) }
      if (summaryResult.status === 'fulfilled') setSummary(summaryResult.value)
      if (projectResult.status === 'fulfilled') { const projects = projectResult.value?.items || []; setSummary(current => ({ ...current, projects })); const storedId = localStorage.getItem('codeweave-selected-project'); const selected = projects.find(item => item.id === storedId); if (selected) setSelectedProject(selected); else if (storedId) localStorage.removeItem('codeweave-selected-project') }
      if (accessResult.status === 'fulfilled') setAccess(accessResult.value)
      if (modelResult.status === 'fulfilled') { const available = modelResult.value?.items || []; const configured = available.filter(item => item.hasApiKey); setDefaultModelName((available.find(item => item.defaultModel) || available[0])?.name || ''); if (catalogResult.status !== 'fulfilled' || !(catalogResult.value?.items || []).length) setModels(configured.map(item => ({ ...item, id: `${item.id}::${item.name}`, modelId: item.name, providerName: item.name }))) }
      if (catalogResult.status === 'fulfilled' && (catalogResult.value?.items || []).length) setModels(catalogResult.value.items)
      if (profileResult.status === 'fulfilled') setProfile(normalizeProfile(profileResult.value))
    })
    refreshWorkspace()
    const timer = setInterval(refreshWorkspace, 5000)
    return () => clearInterval(timer)
  }, [logged])
  useEffect(() => {
    if (!logged) return undefined
    let disposed = false
    if (!selectedProject?.workspaceReady) { setDshConsoleUrl(''); return undefined }
    api.dshConsole({ projectId: selectedProject.id, name: selectedProject.name }).then(result => { if (!disposed) setDshConsoleUrl(result?.url || '') }).catch(() => { if (!disposed) setDshConsoleUrl('') })
    return () => { disposed = true }
  }, [logged, selectedProject?.id, selectedProject?.name, selectedProject?.workspaceReady])
  const share = async () => {
    try {
      const result = await api.createShare({ sessionId: session?.id || 'cw-9842', mode: 'readonly' })
      const link = `${window.location.origin}${result.path}`
      await navigator.clipboard?.writeText(link)
      setToast(`只读分享链接已复制：${link}`)
    } catch { setToast('分享入口已打开（本地 API 未连接）') }
    setTimeout(() => setToast(''), 3500)
  }
  const uploadAvatar = async file => {
    try { setProfile(normalizeProfile(await api.uploadAvatar(file))); setToast('头像已更新') }
    catch (error) { setToast(error.message || '头像上传失败') }
    setTimeout(() => setToast(''), 3500)
  }
  const updateProjects = updater => setSummary(current => ({ ...current, projects: typeof updater === 'function' ? updater(current.projects || []) : updater }))
  const toggleSidebar = () => setSidebarCollapsed(current => {
    const next = !current
    localStorage.setItem('codeweave-sidebar-collapsed', String(next))
    return next
  })
  if (!logged) return <Login onLogin={login} />
  let page = <Overview metrics={summary.metrics} items={items} attention={summary.attention} activity={summary.activity} nav={nav} open={s => { setSession(s); localStorage.setItem('codeweave-session-id', s.id); nav('agent') }} />
  if (active === 'agent') page = !selectedProject ? <DshUnavailable nav={nav} /> : !selectedProject.workspaceReady ? <DshProjectNeedsDirectory project={selectedProject} nav={nav} /> : dshConsoleUrl ? <DshConsole url={dshConsoleUrl} project={selectedProject} nav={nav} /> : <DshUnavailable nav={nav} />
  if (active === 'conflicts') page = <Conflicts nav={nav} />
  if (active === 'projects') page = <Projects projects={summary.projects} nav={nav} onProjectsChange={updateProjects} onOpenProject={project => { setSelectedProject(project); localStorage.setItem('codeweave-selected-project', project.id); nav('agent') }} onToast={message => { setToast(message); setTimeout(() => setToast(''), 3500) }} />
  if (active === 'audit') page = <Audit />
  return <div className="shell"><Header share={share} collapsed={sidebarCollapsed} onToggle={toggleSidebar} modelName={defaultModelName} project={selectedProject} profile={profile} onAvatarUpload={uploadAvatar} /><Sidebar active={active} setActive={setActive} logout={logout} openSettings={() => setSettingsOpen(true)} access={access} collapsed={sidebarCollapsed} /><main className={`main${sidebarCollapsed ? ' sidebar-collapsed' : ''}`}>{page}</main>{toast && <div className="toast">{toast}</div>}{settingsOpen && <SettingsModal access={access} onClose={() => setSettingsOpen(false)} onDefaultModelChange={setDefaultModelName} onToast={message => { setToast(message); setTimeout(() => setToast(''), 3500) }} />}<footer className="statusbar"><span>⌁ {selectedProject?.name || '未选择项目'}</span><span>Rust heartbeat 4ms</span><span>Tokens 38.4k / 500k</span><span className="online">◉ WS Connected · 3 active</span></footer></div>
}

createRoot(document.getElementById('root')).render(<App />)
