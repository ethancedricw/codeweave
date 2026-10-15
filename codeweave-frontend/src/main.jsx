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
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
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
    <section className="login-form-wrap"><form className="login-card" onSubmit={submit}><div className="form-head"><div><small>WELCOME BACK</small><h2>登录工作台</h2></div></div><p className="muted">使用账号进入你的项目和协作会话。</p><label>用户账号<input value={email} onChange={e => setEmail(e.target.value)} placeholder="请输入用户账号" /></label><label>密码<input value={password} onChange={e => setPassword(e.target.value)} type="password" placeholder="输入密码" /></label><div className="form-row"><label className="remember"><input type="checkbox" /> 保持登录</label><a href="#forgot">忘记密码？</a></div>{error && <p className="login-error">{error}</p>}<button type="submit" className="primary full" disabled={busy}>{busy ? '正在进入…' : '进入工作台'} <span>↗</span></button><p className="login-help">还没有组织账号？<a href="#request">联系管理员开通</a></p></form><div className="login-footer">Privacy protected　·　Enterprise access　·　© 2026 CodeWeave</div></section>
  </main>
}
function SidebarToggle({ collapsed, onToggle }) { return <button className="topbar-sidebar-toggle" onClick={onToggle} aria-expanded={!collapsed} aria-label={collapsed ? '展开菜单' : '收起菜单'} title={collapsed ? '展开菜单' : '收起菜单'}><svg className="sidebar-toggle-icon" viewBox="0 0 24 24" aria-hidden="true"><path d={collapsed ? 'M4 6.5h10M4 12h10M4 17.5h10' : 'M10 6.5h10M10 12h10M10 17.5h10'} /><path d={collapsed ? 'm16 8.5 3.5 3.5-3.5 3.5M19.5 12H13' : 'm8 8.5-3.5 3.5L8 15.5M4.5 12H11'} /></svg></button> }
function Header({ collapsed, onToggle, modelName, project, profile, onAvatarUpload, onLogout }) {
  const [menuOpen, setMenuOpen] = useState(false)
  const [passwordOpen, setPasswordOpen] = useState(false)
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const menuRef = useRef(null)
  useEffect(() => {
    const close = event => { if (!menuRef.current?.contains(event.target)) setMenuOpen(false) }
    const escape = event => { if (event.key === 'Escape') { setMenuOpen(false); if (!busy) setPasswordOpen(false) } }
    document.addEventListener('pointerdown', close); document.addEventListener('keydown', escape)
    return () => { document.removeEventListener('pointerdown', close); document.removeEventListener('keydown', escape) }
  }, [busy])
  const resetPassword = async event => {
    event.preventDefault(); setError('')
    if (password.length < 8) return setError('密码至少需要 8 位')
    if (password !== confirmation) return setError('两次输入的密码不一致')
    setBusy(true)
    try { await api.changePassword(password); setPasswordOpen(false); setPassword(''); setConfirmation(''); onLogout() }
    catch (error) { setError(error.message || '密码重置失败，请稍后重试') }
    finally { setBusy(false) }
  }
  return <><header className="topbar"><div className="top-left"><div className="brand compact"><b>⌁</b><span className="brand-copy"><strong>codeweave</strong><small>AI 协作工作台</small></span></div><SidebarToggle collapsed={collapsed} onToggle={onToggle} /><span>/</span><strong>{project?.name || '未选择项目'}</strong><Badge tone={project ? 'mint' : 'cyan'}>{project ? '工作区已连接' : '工作区为空'}</Badge></div><div className="top-center"><span><Dot /> Rust Local <b>127.0.0.1:9042</b></span><span><Dot tone="cyan" /> K8s Runner <b>cluster-ap-east</b></span><span className="model">✦ {modelName || '未配置模型'} <small>{modelName ? '默认' : '设置里添加'}</small></span></div><div className="top-right"><div className="account-menu-wrap" ref={menuRef}><button className="profile account-trigger" aria-label="账号菜单" aria-haspopup="menu" aria-expanded={menuOpen} onClick={() => setMenuOpen(value => !value)}><AvatarImage src={profile?.avatarUrl} fallback={defaultAvatarForGender(profile?.gender)} fallbackText="你" alt="当前用户头像" className="profile-avatar" /></button>{menuOpen && <div className="account-menu" role="menu"><label className="account-upload" role="menuitem">更换头像<input type="file" accept="image/png,image/jpeg" onChange={event => { if (event.target.files?.[0]) onAvatarUpload?.(event.target.files[0]); event.target.value = ''; setMenuOpen(false) }} /></label><button role="menuitem" onClick={() => { setMenuOpen(false); setPassword(''); setConfirmation(''); setError(''); setPasswordOpen(true) }}>重置密码</button><button role="menuitem" className="account-logout" onClick={onLogout}>退出登录</button></div>}</div></div></header>{passwordOpen && <div className="confirm-overlay"><form className="account-password-dialog" onSubmit={resetPassword} role="dialog" aria-modal="true" aria-labelledby="account-password-title"><h3 id="account-password-title">重置密码</h3><p>设置新密码后，请重新登录。</p><label>新密码<input autoFocus type="password" autoComplete="new-password" minLength={8} maxLength={72} required value={password} onChange={event => setPassword(event.target.value)} placeholder="至少 8 位" disabled={busy} /></label><label>确认新密码<input type="password" autoComplete="new-password" required value={confirmation} onChange={event => setConfirmation(event.target.value)} placeholder="再次输入新密码" disabled={busy} /></label>{error && <p role="alert" className="login-error">{error}</p>}<div className="confirm-dialog-actions"><button type="button" className="quiet" disabled={busy} onClick={() => setPasswordOpen(false)}>取消</button><button className="primary" disabled={busy}>{busy ? '保存中…' : '确认重置'}</button></div></form></div>}</> }


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
  const [modelName, setModelName] = useState('DeepSeek 配置')
  const [modelId, setModelId] = useState('')
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
  const openModel = model => { setEditingId(model?.id || null); setModelName(model?.name || '新模型配置'); setModelId(model?.modelId || ''); setEndpoint(model?.endpoint || 'https://api.deepseek.com/v1'); setApiKey(''); setModelTestResult(null); setDiscoveredModels([]); setShowForm(true) }
  const discoverModels = async () => {
    if (!endpoint.trim()) return setModelTestResult({ status: 'invalid_endpoint', message: '请先填写接口地址' })
    setDiscoveringModels(true); setModelTestResult(null)
    try {
      const result = await api.discoverModelList({ ...(editingId ? { id: editingId } : {}), endpoint: endpoint.trim(), ...(apiKey.trim() ? { apiKey: apiKey.trim() } : {}) })
      setDiscoveredModels(result.items || [])
      if (!modelId.trim() && result.items?.[0]?.id) setModelId(result.items[0].id)
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
      const result = await api.testModelConnection({ ...(editingId ? { id: editingId } : {}), name: modelId.trim(), endpoint: endpoint.trim(), ...(apiKey.trim() ? { apiKey: apiKey.trim() } : {}) })
      setModelTestResult(result)
      if (result.status === 'ready') {
        onToast('模型连接测试成功，请点击“保存配置”完成保存')
      } else {
        onToast(result.message || '连接测试完成')
      }
    } catch (error) {
      setModelTestResult({ status: 'error', message: error.message || 'Model connection failed.' })
      onToast(error.message || 'Model connection failed.')
    } finally { setTesting(false) }
  }
  const saveModel = async () => {
    if (!modelId.trim()) return setModelTestResult({ status: 'invalid_model', message: '请填写实际模型 ID，或先查询并选择一个可用模型。' })
    const currentModel = editingId ? models.find(item => item.id === editingId) : null
    const defaultModel = editingId ? Boolean(currentModel?.defaultModel) : models.length === 0 || !models.some(item => item.defaultModel)
    const payload = { name: modelName.trim(), modelId: modelId.trim(), endpoint: endpoint.trim(), ...(apiKey.trim() ? { apiKey: apiKey.trim() } : {}), defaultModel }
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
      const result = await api.updateModel(model.id, { name: model.name, modelId: model.modelId, endpoint: model.endpoint, defaultModel: true })
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
          {section === 'models' && <><div className="settings-title"><div><h3>模型配置</h3><p>配置后只对你本人可用，API Key 不会展示给其他用户。</p></div><button className="primary small" onClick={() => openModel()}>＋ 添加模型</button></div>{models.length ? models.map(model => <div className="model-card" key={model.id}><div className="model-card-main"><div className="model-logo">CW</div><div><strong>{model.name}</strong><span>{model.modelId} · {model.hasApiKey ? '已配置密钥 · 私人配置' : '未配置密钥'}</span></div></div><label className={`model-default-choice${model.defaultModel ? ' selected' : ''}`}><input type="radio" name="default-model" checked={Boolean(model.defaultModel)} onChange={() => selectDefaultModel(model)} />{model.defaultModel ? '默认配置' : '设为默认'}</label><button className="quiet" onClick={() => openModel(model)}>配置</button><button className="icon-danger" onClick={() => removeModel(model)}>×</button></div>) : <div className="empty-state settings-empty"><strong>还没有模型配置</strong><span>添加 OpenAI 兼容模型后，Agent 会话才能调用它。</span></div>}{showForm && <div className="model-form"><div className="form-section-head"><div><b>{editingId ? '编辑模型配置' : '添加模型配置'}</b><small>OpenAI 兼容接口</small></div><button onClick={() => setShowForm(false)}>收起</button></div><label>配置名称<input value={modelName} onChange={e => setModelName(e.target.value)} placeholder="例如：公司模型服务、研发环境" /><small className="field-help">仅用于 CodeWeave 内部展示和管理，可以自定义。</small></label><label>接口地址<input value={endpoint} onChange={e => { setEndpoint(e.target.value); setModelId(''); setDiscoveredModels([]) }} /><button className="quiet discover-models-button" onClick={discoverModels} disabled={discoveringModels}>{discoveringModels ? '查询中…' : '查询可用模型'}</button></label><label>实际模型 ID<input value={modelId} onChange={e => setModelId(e.target.value)} placeholder="例如：gpt-5.6-sol、deepseek-v4.1-flash" /><small className="field-help">必须填写服务商接口实际支持的模型 ID，不是自定义名称。</small></label>{discoveredModels.length > 0 && <div className="discovered-models"><small>接口返回的可用模型，点击即可填入</small>{discoveredModels.map(item => <button type="button" className="discovered-model-option" key={item.id} onClick={() => setModelId(item.id)}><strong>{item.id}</strong>{item.name && item.name !== item.id && <span>{item.name}</span>}</button>)}</div>}<label>API Key<input value={apiKey} onChange={e => setApiKey(e.target.value)} type="password" placeholder={editingId ? "留空则保留已保存的 Key" : "sk-••••••••••••"} /></label>{modelTestResult && <div className={`model-test-result ${modelTestResult.status === 'ready' ? 'success' : modelTestResult.status === 'error' ? 'error' : 'warning'}`}>{modelTestResult.message}</div>}<div className="model-form-actions"><button className="quiet" onClick={testConnection} disabled={testing}>{testing ? '测试中…' : '测试连接'}</button><button className="primary" onClick={saveModel}>保存配置</button></div></div>}<div className="settings-note"><span>▣</span><p>先查询并选择实际模型 ID，再测试连接；确认结果后点击“保存配置”。</p></div></>}
          {section === 'ragflow' && <><div className="settings-title"><div><h3>RAGFlow 知识库</h3><p>连接配置保存在当前账号中，项目只需要选择要使用的知识库。</p></div>{!ragflowConfigOpen && <button className="quiet small" onClick={() => setRagflowConfigOpen(true)}>编辑连接</button>}</div>{ragflowConfigOpen ? <div className="model-form"><div className="form-section-head"><div><b>连接配置</b><small>用于读取当前账号可见的知识库</small></div><div className="ragflow-form-actions"><Badge tone={ragflowTokenConfigured ? 'mint' : 'amber'}>{ragflowTokenConfigured ? '已配置 Token' : '未配置 Token'}</Badge>{ragflowTokenConfigured && <button className="quiet small" onClick={() => { setRagflowConfigOpen(false); setRagflowTest(null) }}>返回列表</button>}</div></div><label>RAGFlow URL<input value={ragflowUrl} onChange={e => setRagflowUrl(e.target.value)} placeholder="http://localhost:9380" /></label><label>Token<input value={ragflowToken} onChange={e => setRagflowToken(e.target.value)} type="password" placeholder={ragflowTokenConfigured ? '已保存，留空则继续使用' : '请输入 RAGFlow API Token'} /></label><div className="model-form-actions"><button className="quiet" onClick={testRagflow} disabled={ragflowTesting}>{ragflowTesting ? '测试中…' : '测试连接'}</button><button className="primary" onClick={saveRagflow} disabled={ragflowSaving}>{ragflowSaving ? '保存中…' : '保存连接配置'}</button></div>{ragflowTest && <div className={`model-test-result ${ragflowTest.tone}`}>{ragflowTest.text}</div>}</div> : <div className="model-card"><div className="model-card-main"><div className="model-logo">⌁</div><div><strong>{ragflowUrl}</strong><span>连接已保存，项目可以选择知识库</span></div></div><Badge tone="mint">已连接</Badge></div>}{!ragflowConfigOpen && <div className="dataset-picker settings-dataset-list"><small>当前账号可见的知识库</small>{ragflowDatasetList.length ? ragflowDatasetList.map(dataset => <div className="dataset-list-row" key={dataset.id}><b>{dataset.name || dataset.id}</b></div>) : <div className="settings-empty-copy">暂无可用知识库</div>}</div>}<div className="settings-note"><span>⌁</span><p>项目创建和编辑窗口会从这里读取知识库，并支持多选绑定。Token 只在服务端加密保存。</p></div></>}

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
function Overview({ nav, open, items = [], profile, metrics = { runningAgents: 0, pendingApprovals: 0, unresolvedConflicts: null, membersOnline: 0 }, collaboration = [], attention = [], activity = [] }) {
  const today = new Date().toLocaleDateString('zh-CN', { weekday: 'long', month: 'short', day: 'numeric' }).toUpperCase()
  const metricDetails = { runningAgents: metrics.runningAgents ? 'Agent 正在执行任务' : '当前没有运行中的 Agent', pendingApprovals: metrics.pendingApprovals ? '有事项等待你的决定' : '当前没有审批', unresolvedConflicts: metrics.unresolvedConflicts == null ? '冲突检测暂未接入' : metrics.unresolvedConflicts ? '有协作冲突需要处理' : '当前没有未解决冲突', membersOnline: `${metrics.membersOnline || 0} 位项目成员在线（跨项目去重）` }
  return <div className="page overview-page"><div className="page-head"><div><small>{today}</small><h1>早上好，{profile?.displayName || profile?.name || '你'}。</h1><p>这里是你和团队今天的协作状态。</p></div><button className="primary" onClick={() => nav('agent')}>＋ 新建任务</button></div><div className="metrics"><Metric label="运行中的 Agent" value={metrics.runningAgents} detail={metricDetails.runningAgents} tone="mint" icon="✦" /><Metric label="待处理审批" value={metrics.pendingApprovals} detail={metricDetails.pendingApprovals} tone="amber" icon="!" /><Metric label="未解决冲突" value={metrics.unresolvedConflicts ?? '—'} detail={metricDetails.unresolvedConflicts} tone="cyan" icon="◌" /><Metric label="协作成员" value={metrics.membersOnline} detail={metricDetails.membersOnline} tone="violet" icon="◉" /></div><section className="panel project-presence"><div className="panel-head"><div><small>PROJECT MEMBERS</small><h2>我的项目 · 在线成员</h2></div><span>{collaboration.length} 个项目</span></div>{collaboration.length ? collaboration.map(project => <div className="project-presence-row" key={project.projectId}><div><strong>{project.projectName}</strong><small>{project.onlineCount} / {project.members.length} 人在线</small></div><div className="project-presence-members">{project.members.map(member => <span className={member.online ? "member-chip is-online" : "member-chip"} key={member.userId}><i />{member.displayName}<small>{member.online ? "在线" : "离线"}</small></span>)}</div></div>) : <div className="empty-state"><span>你尚未加入任何项目。</span></div>}</section><div className="two-col"><section className="panel"><div className="panel-head"><div><small>PROJECT ACTIVITY</small><h2>项目最近协作</h2></div><button onClick={() => nav('agent')}>查看全部 →</button></div><div className="sessions">{items.length ? items.map(s => <SessionCard item={{ ...s, who: s.who || 'A', tone: s.tone || 'cyan', state: s.state || '运行中', detail: s.detail || '等待最新动态' }} open={() => open(s)} key={s.projectId || s.project || s.id} />) : <div className="empty-state"><strong>还没有项目协作</strong><span>创建任务并发送第一条指令后，最新进展会显示在这里。</span><button className="primary small" onClick={() => nav('agent')}>创建任务</button></div>}</div></section><section className="panel"><div className="panel-head"><div><small>NEEDS ATTENTION</small><h2>需要你关注</h2></div><button onClick={() => nav('conflicts')}>···</button></div>{attention.length ? attention.map(item => <div className={`notice ${item.tone || 'warning'}`} key={item.id || item.title}><b>{item.title}</b><p>{item.detail}</p><button onClick={() => nav(item.route || 'conflicts')}>去处理 →</button></div>) : <div className="empty-state"><strong>当前没有待处理事项</strong><span>冲突、审批和构建活动会在产生后显示在这里。</span></div>}</section></div><section className="panel activity"><div className="panel-head"><div><small>TEAM ACTIVITY</small><h2>团队最近动态</h2></div><button onClick={() => nav('audit')}>进入审计 →</button></div>{activity.length ? <div className="activity-grid">{activity.map(item => <p key={item.id || `${item.createdAt}-${item.target}`}><Dot tone={item.tone || 'cyan'} /><span><b>{item.actor || '团队成员'}</b> {item.action || '更新了工作区'}<small>{item.target || ''} · {formatActivityTime(item.createdAt)}</small></span></p>)}</div> : <div className="activity-grid"><div className="empty-state"><strong>暂无团队动态</strong><span>团队协作事件会在任务创建后记录。</span></div></div>}</section></div>
}

function Agent({ session, project, nav, onToast, profile, models = [], onSessionChange }) {
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
        <div className="agent-head-actions"><Badge tone={agentStatus === '连接失败' ? 'amber' : agentStatus === '已完成' ? 'mint' : 'cyan'}>{agentStatus}</Badge></div>
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
    </aside>
  </div>
}

function collaborationSessionTitle(item) { return item.title || item.latestPrompt || 'Agent 会话' }
function collaborationSessionTimestamp(value) {
  if (value === null || value === undefined || value === '') return 0
  const time = typeof value === 'number' || /^\d+$/.test(value) ? Number(value) : Date.parse(value)
  return Number.isFinite(time) ? time : 0
}

function CollaborationSession({ item, presence }) {
  const actor = presence.find(member => member.userId === item.ownerId)?.displayName || item.ownerName || '项目成员'
  const timestamp = collaborationSessionTimestamp(item.updatedAt)
  const time = timestamp ? new Date(timestamp).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : ''
  const state = item.state || '未知'
  const tone = state === '运行中' ? 'running' : state === '已完成' ? 'done' : state === '失败' ? 'failed' : 'idle'
  return <div className="collab-session">
    <div className="collab-session-heading"><b title={collaborationSessionTitle(item)}>{collaborationSessionTitle(item)}</b><span className={`collab-session-state ${tone}`}>{state}</span></div>
    <div className="collab-session-meta"><span title={actor}>{actor}</span>{time && <time dateTime={new Date(timestamp).toISOString()}>{time}</time>}</div>
    {(item.model || item.turns > 0) && <div className="collab-session-detail">{item.model && <span title={item.model}>{item.model}</span>}{item.turns > 0 && <span>{item.turns} 轮</span>}</div>}
  </div>
}

function DshConsole({ url, project, nav, onToast }) {
  const [collabCollapsed, setCollabCollapsed] = useState(false)
  const [presence, setPresence] = useState([])
  const [collabSessions, setCollabSessions] = useState([])
  const [collabError, setCollabError] = useState('')
  const [collabLoading, setCollabLoading] = useState(true)
  const previousOnline = useRef(new Set())
  useEffect(() => {
    if (!project?.id) return
    let active = true
    let pending = false
    setPresence([]); setCollabSessions([]); setCollabError(''); setCollabLoading(true)
    previousOnline.current = new Set()
    const load = async () => {
      if (pending) return
      pending = true
      const [p, s, native] = await Promise.allSettled([api.memberPresence(project.id).then(() => api.projectPresence(project.id)), api.sessions(), api.projectAgentSessions(project.id)])
      pending = false
      if (!active) return
      if (p.status === 'fulfilled') {
        const next = p.value.items || []
        const online = new Set(next.filter(item => item.online).map(item => item.userId))
        for (const userId of online) if (!previousOnline.current.has(userId) && previousOnline.current.size) onToast?.(`${next.find(item => item.userId === userId)?.displayName || '项目成员'} 已上线`)
        previousOnline.current = online
        setPresence(next)
      }
      setCollabLoading(false)
      setCollabError(s.status === 'rejected' && native.status === 'rejected' ? 'Agent 会话暂时无法更新，正在自动重试' : '')
      if (s.status === 'fulfilled' || native.status === 'fulfilled') {
        const merged = new Map()
        if (s.status === 'fulfilled') for (const item of s.value.items || []) if (item.projectId === project.id) merged.set(item.dshSessionId || item.id, item)
        if (native.status === 'fulfilled') for (const item of native.value.items || []) merged.set(item.id, { ...merged.get(item.id), ...item })
        setCollabSessions([...merged.values()].sort((a, b) => collaborationSessionTimestamp(b.updatedAt) - collaborationSessionTimestamp(a.updatedAt)).slice(0, 4))
      }
    }
    load()
    const timer = setInterval(load, 10000)
    return () => { active = false; clearInterval(timer) }
  }, [project?.id, onToast])
  const frameUrl = (() => {
    try {
      const parsed = new URL(url)
      parsed.searchParams.set('codeweaveLocale', 'zh')
      const parentHost = window.location.hostname
      const localHosts = host => host === 'localhost' || host === '::1' || /^127(?:\.\d{1,3}){3}$/.test(host)
      if (localHosts(parsed.hostname) && localHosts(parentHost)) parsed.hostname = parentHost
      return parsed.href
    } catch {
      return url
    }
  })()
  const projectLabel = project?.name || ''
  return <div className="dsh-console-shell"><header className="dsh-console-head"><div><b>Agent 工作台</b><span>{projectLabel}</span></div><div><a className="quiet small" href={frameUrl} target="_blank" rel="noreferrer">在新窗口打开 ↗</a></div></header><div className="dsh-console-body"><iframe className="dsh-console-frame" src={frameUrl} title="CodeWeave Agent 工作台" allow="clipboard-read; clipboard-write" /><aside className={`collab-panel${collabCollapsed ? " collapsed" : ""}`}><div className="collab-toolbar"><button className="collab-toggle" aria-label={collabCollapsed ? '展开项目协作' : '折叠项目协作'} title={collabCollapsed ? '展开项目协作' : '折叠项目协作'} aria-expanded={!collabCollapsed} onClick={() => setCollabCollapsed(value => !value)}><svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={collabCollapsed ? 'M14 6l-6 6 6 6' : 'M10 6l6 6-6 6'} /></svg></button></div><div className="collab-panel-head"><b>项目协作</b><small>{presence.filter(item => item.online).length} 人在线</small></div><div className="collab-members">{presence.map(item => <div className="collab-member" key={item.userId}><i className={item.online ? 'online' : ''} /><span title={item.displayName || '项目成员'}>{item.displayName || '项目成员'}</span><small>{item.online ? '在线' : '离线'}</small></div>)}</div><div className="collab-panel-head"><b>最近会话</b><small>{collabSessions.length ? `${collabSessions.length} 条` : ''}</small></div><div className="collab-sessions">{collabError && <p className="collab-error" role="status">{collabError}</p>}{collabSessions.length ? collabSessions.map(item => <CollaborationSession item={item} presence={presence} key={item.id} />) : <small className="rail-muted">{collabLoading ? '正在加载会话…' : collabError ? '请稍后查看' : '暂无项目会话，发送第一条消息后会显示在这里'}</small>}</div></aside></div></div>
}

function DshUnavailable({ nav, message, onRetry }) {
  const requiresModel = message?.includes('配置至少一个模型')
  return <div className="dsh-unavailable"><div><small>CODEWEAVE</small><h2>{requiresModel ? '请先配置模型' : 'CodeWeave 控制台暂时不可用'}</h2><p>{message || '请确认 CodeWeave Agent 工作台已启动，并检查后端里的控制台地址和认证 Token。'}</p>{requiresModel ? <button className="primary small" onClick={() => window.dispatchEvent(new CustomEvent('codeweave:open-settings'))}>打开模型配置</button> : <button className="primary small" onClick={onRetry}>重新连接</button>}<button className="quiet small" onClick={() => nav('projects')}>返回项目</button></div></div>
}

function DshNoProject({ nav, hasProjects }) {
  return <div className="dsh-unavailable"><div><small>CODEWEAVE</small><h2>{hasProjects ? '请先选择项目' : '你还没有项目与工作区'}</h2><p>{hasProjects ? '从你的项目列表中选择一个项目，进入对应的 Agent 工作区。' : '创建一个项目后即可使用 Agent 工作区，也可以请项目负责人邀请你加入已有项目。'}</p><button className="primary small" onClick={() => nav('projects')}>{hasProjects ? '选择项目' : '前往项目 · 创建项目'}</button></div></div>
}

function DshProjectNeedsDirectory({ project, nav }) {
  const status = project?.workspaceStatus || 'preparing'
  return <div className="dsh-unavailable"><div><small>CODEWEAVE PROJECT</small><h2>{status === 'waiting-upload' ? '等待服务器接收快照' : status === 'error' ? '服务器工作区准备失败' : '服务器工作区准备中'}</h2><p>{project?.workspaceError || '项目目录由服务器统一准备，准备完成后团队成员可以进入同一个工作区。'}</p><button className="primary small" onClick={() => nav('projects')}>返回项目</button></div></div>
}

function Conflicts({ nav }) {
 const [items,setItems]=useState([]),[loading,setLoading]=useState(true),[error,setError]=useState(''),[busy,setBusy]=useState('')
 const load=()=>{setLoading(true);api.conflicts().then(r=>setItems(r?.items||[])).catch(e=>setError(e.message)).finally(()=>setLoading(false))}
 useEffect(load,[])
 const resolve=async item=>{if(!window.confirm(`请确认你已在本地编辑并保存“${item.file}”，然后标记为已解决？`))return;setBusy(item.file);try{await api.resolveConflict(item.projectId,item.file);load()}catch(e){setError(e.message)}finally{setBusy('')}}
 return <div className="page"><div className="page-head"><div><small>COLLABORATION CONTROL</small><h1>冲突中心</h1><p>检测项目工作区中的 Git 未合并文件。请先手动编辑文件，再标记已解决。</p></div><button className="quiet" onClick={()=>nav('agent')}>返回工作台</button></div><section className="panel conflict-panel">{error&&<div className="notice error">{error}</div>}{loading?<div className="empty-state"><strong>正在扫描工作区…</strong></div>:items.length===0?<div className="empty-state"><strong>暂无协作冲突</strong><span>当前项目没有 Git 未合并文件。</span></div>:<div className="conflict-list">{items.map(item=><div className="conflict-row" key={`${item.projectId}:${item.file}`}><div><b>{item.file}</b><small>{item.projectName}</small></div><button className="primary small" disabled={busy===item.file} onClick={()=>resolve(item)}>{busy===item.file?'处理中…':'标记已解决'}</button></div>)}</div>}</section></div>
}

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
  const [gitUsername, setGitUsername] = useState('')
  const [gitToken, setGitToken] = useState('')
  const [gitTokenConfigured, setGitTokenConfigured] = useState(false)
  const [gitTesting, setGitTesting] = useState(false)
  const [gitTest, setGitTest] = useState(null)
  const [snapshotFile, setSnapshotFile] = useState(null)
  const [branch, setBranch] = useState('main')
  const [description, setDescription] = useState('')
  const [ragflowEnabled, setRagflowEnabled] = useState(false)
  const [ragflowDatasets, setRagflowDatasets] = useState([])
  const [ragflowDatasetIds, setRagflowDatasetIds] = useState([])
  const [ragflowLoading, setRagflowLoading] = useState(false)
  const [ragflowMessage, setRagflowMessage] = useState(null)
  const [syncingId, setSyncingId] = useState(null)
  const [memberProject, setMemberProject] = useState(null)
  const [members, setMembers] = useState([])
  const [memberUserId, setMemberUserId] = useState('')
  const [memberRole, setMemberRole] = useState('developer')
  const [systemUsers, setSystemUsers] = useState([])
  const [memberSearch, setMemberSearch] = useState('')
  const [selectedMembers, setSelectedMembers] = useState([])
  const [memberToRemove, setMemberToRemove] = useState(null)
  const [projectToDelete, setProjectToDelete] = useState(null)
  const [deleteConfirmStep, setDeleteConfirmStep] = useState(0)
  const openMembers = async project => { setMemberProject(project); setSelectedMembers([]); setMemberSearch(''); try { const [result, usersResult] = await Promise.all([api.projectMembers(project.id), api.projectUsers()]); setMembers(result.items || []); setSystemUsers(usersResult.items || usersResult || []) } catch (error) { setMembers([]); setSystemUsers([]); onToast?.(error.message || '成员列表加载失败') } }
  const addMember = async () => { if (!selectedMembers.length) return; try { for (const userId of selectedMembers) await api.addProjectMember(memberProject.id, { userId, role: memberRole }); const result = await api.projectMembers(memberProject.id); setMembers(result.items || []); setSelectedMembers([]); onToast?.('成员已加入项目') } catch (error) { onToast?.(error.message || '添加成员失败') } }
  const removeMember = async userId => { try { await api.removeProjectMember(memberProject.id, userId); setMembers(current => current.filter(item => item.userId !== userId)); onToast?.('成员已移除') } catch (error) { onToast?.(error.message || '移除成员失败') } }
  const openForm = project => {
    setEditingId(project?.id || null); setName(project?.name || ''); setType(project?.type || 'git')
    setRepoUrl(project?.repoUrl || ''); setGitUsername(project?.gitUsername || ''); setGitToken(''); setGitTokenConfigured(Boolean(project?.gitTokenConfigured)); setGitTest(null); setSnapshotFile(null); setBranch(project?.branch || 'main'); setDescription(project?.description || '')
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
        setRagflowMessage({ tone: 'success', text: `已获取 ${items.length} 个可用知识库，请选择要绑定的库` })
      } else setRagflowMessage({ tone: 'warning', text: '当前 Token 没有可用的知识库' })
    } catch (error) { setRagflowMessage({ tone: 'error', text: error.message || '获取知识库失败，请检查 URL 和 Token' }) }
    finally { setRagflowLoading(false) }
  }
  const testGit = async () => {
    if (!repoUrl.trim()) return setGitTest({ tone: 'error', text: '请填写 Git 仓库地址' })
    setGitTesting(true); setGitTest(null)
    try {
      const result = await api.testGitProject({ ...(editingId ? { projectId: editingId } : {}), repoUrl: repoUrl.trim(), branch: branch.trim() || 'main', gitUsername: gitUsername.trim(), gitToken: gitToken.trim() })
      setGitTest({ tone: 'success', text: result.message || 'Git 仓库连接成功' })
    } catch (error) { setGitTest({ tone: 'error', text: error.message || 'Git 仓库连接失败' }) }
    finally { setGitTesting(false) }
  }
  const save = async () => {
    if (!name.trim()) return onToast?.('请填写项目名称')
    if (type === 'git' && !repoUrl.trim()) return onToast?.('请填写 Git 仓库地址')
    if (type === 'snapshot' && !editingId && !snapshotFile) return onToast?.('请选择 ZIP 快照文件')
    if (snapshotFile && !snapshotFile.name.toLowerCase().endsWith('.zip')) return onToast?.('快照目前只支持 ZIP 文件')
    if (ragflowEnabled && !ragflowDatasetIds.length) return onToast?.('请选择要绑定的知识库')
    const selectedDatasets = ragflowDatasets.filter(item => ragflowDatasetIds.includes(item.id))
    const existingProject = projects.find(item => item.id === editingId)
    const existingNames = new Map((existingProject?.ragflowDatasetIds || []).map((id, index) => [id, existingProject?.ragflowDatasetNames?.[index] || id]))
    const datasetNames = new Map([...existingNames, ...selectedDatasets.map(item => [item.id, item.name || item.id])])
    const payload = { name: name.trim(), type, repoUrl: type === 'git' ? repoUrl.trim() : '', branch: branch.trim() || 'main', ...(type === 'git' ? { gitUsername: gitUsername.trim(), ...(gitToken.trim() ? { gitToken: gitToken.trim() } : {}) } : {}), description: description.trim(), ragflowEnabled, ragflowDatasetIds, ragflowDatasetNames: ragflowDatasetIds.map(id => datasetNames.get(id) || id) }
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
  const remove = async project => { setProjectToDelete(project); setDeleteConfirmStep(1) }
  const confirmProjectDelete = async () => {
    if (!projectToDelete) return
    try { await api.deleteProject(projectToDelete.id); onProjectsChange?.(current => current.filter(item => item.id !== projectToDelete.id)); onToast?.('项目已删除') }
    catch { onToast?.('项目删除失败') }
    finally { setProjectToDelete(null); setDeleteConfirmStep(0) }
  }
  const sync = async project => {
    setSyncingId(project.id)
    try { const result = await api.syncProject(project.id); onProjectsChange?.(current => current.map(item => item.id === project.id ? result : item)); onToast?.('服务器工作区已重新准备') }
    catch (error) { onProjectsChange?.(current => current.map(item => item.id === project.id ? { ...item, workspaceStatus: 'error', workspaceError: error.message || '服务器工作区准备失败' } : item)); onToast?.(error.message || '服务器工作区准备失败') }
    finally { setSyncingId(null) }
  }
  return <div className="page">{projectToDelete && <ConfirmDialog dialog={{ title: deleteConfirmStep === 1 ? '删除项目' : '确认永久删除', message: deleteConfirmStep === 1 ? '确定删除项目“' + projectToDelete.name + '”吗？' : '项目“' + projectToDelete.name + '”及其成员关系将被删除，此操作无法撤销。确定继续吗？', confirmLabel: deleteConfirmStep === 1 ? '继续删除' : '确认删除' }} onCancel={() => { setProjectToDelete(null); setDeleteConfirmStep(0) }} onConfirm={() => deleteConfirmStep === 1 ? setDeleteConfirmStep(2) : confirmProjectDelete} />}
    <div className="page-head"><div><small>YOUR WORKSPACES</small><h1>项目与工作区</h1><p>项目目录由服务器统一管理，所有有权限的成员进入同一个工作区。</p></div><button className="primary small" onClick={() => openForm()}>＋ 新建项目</button></div>
    <div className="workspace-guide"><div><b>一个项目，多个协作会话</b><span>Git 仓库在服务器 clone，快照在服务器解压，Agent 使用共享目录。</span></div><div className="workspace-types"><span><i className="type-dot git" />Git 仓库</span><span><i className="type-dot snapshot" />快照</span><span><i className="type-dot local" />服务器目录</span></div></div>
    <div className="project-grid">{projects.length ? projects.map(project => <article className="project" key={project.id}><div className="project-top"><Badge tone={project.workspaceReady ? 'mint' : project.workspaceStatus === 'error' ? 'danger' : 'amber'}>{project.workspaceReady ? '服务器可用' : project.workspaceStatus === 'error' ? '准备失败' : project.workspaceStatus === 'waiting-upload' ? '等待快照' : '准备中'}</Badge><button className="project-more" onClick={() => openForm(project)}>编辑</button></div><h2>{project.name}</h2><p className="project-source">{project.source || (project.type === 'git' ? 'Git 仓库' : project.type === 'snapshot' ? '快照项目' : '服务器目录')}</p><p className="project-location">{project.repoUrl || '服务器共享工作区'}</p>{project.ragflowConfigured && <span className="knowledge-bound">⌁ 已绑定 RAGFlow · {(project.ragflowDatasetNames || []).join('、') || `${(project.ragflowDatasetIds || []).length} 个知识库`}</span>}{project.workspaceError && <span className="field-help">{project.workspaceError}</span>}<footer><span>{project.activeTasks || 0} 个活跃任务</span><div>{(project.type === 'git' || !project.workspaceReady) && <button disabled={syncingId === project.id} onClick={() => sync(project)}>{syncingId === project.id ? '准备中…' : project.type === 'git' && project.workspaceReady ? '同步服务器' : '重新准备'}</button>}<button className="primary small project-member-button" onClick={() => openMembers(project)}>邀请成员</button><button onClick={() => onOpenProject?.(project)}>进入工作区 →</button><button className="danger-link" onClick={() => remove(project)}>删除</button></div></footer></article>) : <div className="empty-state project-empty"><strong>还没有项目</strong><span>先添加一个 Git 仓库或快照，Agent 会在服务器共享目录里执行任务。</span><button className="primary small" onClick={() => openForm()}>＋ 创建第一个项目</button></div>}<button className="project new" onClick={() => openForm()}><strong>＋</strong><b>新建项目</b><small>Git、快照或服务器目录</small></button></div>
          {memberProject && <div className="project-form-overlay"><section className="project-form member-dialog" role="dialog" aria-modal="true"><header><div><small>PROJECT MEMBERS</small><h2>{memberProject.name} · 邀请成员</h2></div><button className="settings-close" onClick={() => setMemberProject(null)}>×</button></header><div className="project-form-body"><div className="member-search-box"><span>⌕</span><input autoFocus value={memberSearch} onChange={event => setMemberSearch(event.target.value)} placeholder="输入姓名、邮箱" /></div>{memberSearch.trim() && <div className="member-search-results">{systemUsers.filter(user => !members.some(member => member.userId === user.id) && !selectedMembers.includes(user.id) && `${user.displayName || ''} ${user.email || ''}`.toLowerCase().includes(memberSearch.trim().toLowerCase())).slice(0, 8).map(user => <button className="member-search-result" key={user.id} onClick={() => { setSelectedMembers(current => [...current, user.id]); setMemberSearch('') }}><span className="user-avatar">{String(user.displayName || user.email).slice(0, 1).toUpperCase()}</span><span className="user-info"><b>{user.displayName || user.email}</b><small>{user.email}</small></span><strong>＋</strong></button>)}</div>}<div className="member-selection">{selectedMembers.map(userId => { const user = systemUsers.find(item => item.id === userId); return <span className="member-chip" key={userId}>{user?.displayName || user?.email || userId}<button onClick={() => setSelectedMembers(current => current.filter(id => id !== userId))}>×</button></span> })}</div><div className="member-invite-actions"><select value={memberRole} onChange={event => setMemberRole(event.target.value)}><option value="developer">开发者</option><option value="viewer">只读成员</option><option value="admin">项目管理员</option></select><button className="primary small" disabled={!selectedMembers.length} onClick={addMember}>{selectedMembers.length ? '邀请 ' + selectedMembers.length + ' 位成员' : '邀请成员'}</button></div><div className="member-current"><small>已加入项目 · {members.length} 人</small>{members.map(member => <div className="user-row" key={member.userId}><div className="user-info"><b>{systemUsers.find(user => user.id === member.userId)?.displayName || systemUsers.find(user => user.id === member.userId)?.name || systemUsers.find(user => user.id === member.userId)?.email || member.userId}</b><small>{({ admin: '项目管理员', developer: '开发者', viewer: '只读成员' })[member.role] || member.role}</small></div><button className="danger-link user-action" onClick={() => setMemberToRemove(member)}>移除</button></div>)}</div></div></section></div>}{memberToRemove && <ConfirmDialog dialog={{ title: '移除项目成员', message: '确定移除 ' + (systemUsers.find(user => user.id === memberToRemove.userId)?.displayName || memberToRemove.userId) + ' 吗？移除后仍可再次邀请。', confirmLabel: '确认移除' }} onCancel={() => setMemberToRemove(null)} onConfirm={() => { const userId = memberToRemove.userId; setMemberToRemove(null); removeMember(userId) }} />} {formOpen && <div className="project-form-overlay" onMouseDown={event => event.target === event.currentTarget && setFormOpen(false)}><section className="project-form" role="dialog" aria-modal="true"><header><div><small>{editingId ? 'EDIT WORKSPACE' : 'NEW WORKSPACE'}</small><h2>{editingId ? '编辑项目' : '新建项目'}</h2></div><button className="settings-close" onClick={() => setFormOpen(false)}>×</button></header><div className="project-form-body"><label>项目名称<input value={name} onChange={event => setName(event.target.value)} placeholder="例如：支付服务" /></label><label>项目类型<select value={type} onChange={event => { setType(event.target.value); setSnapshotFile(null) }}><option value="git">Git 仓库</option><option value="snapshot">快照项目</option></select></label>{type === 'git' && <><label>仓库地址<input value={repoUrl} onChange={event => { setRepoUrl(event.target.value); setGitTest(null) }} placeholder="https://git.example.com/team/project.git" /></label><label>默认分支<input value={branch} onChange={event => { setBranch(event.target.value); setGitTest(null) }} placeholder="main" /></label><label>Git 用户账号<input value={gitUsername} onChange={event => { setGitUsername(event.target.value); setGitTest(null) }} placeholder="私有仓库的登录账号（公开仓库可留空）" /></label><label>访问令牌 / 密码<input type="password" value={gitToken} onChange={event => { setGitToken(event.target.value); setGitTest(null) }} placeholder={gitTokenConfigured ? '已保存，留空沿用原令牌' : '私有 HTTPS 仓库的访问令牌'} /></label><p className="field-help">私有 HTTPS 仓库填写账号与访问令牌。SSH 地址使用服务器已配置的 SSH 凭据。</p><div className="git-test-actions"><button className="quiet small" onClick={testGit} disabled={gitTesting}>{gitTesting ? '测试中…' : '测试仓库连接'}</button>{gitTest && <span className={`binding-message ${gitTest.tone}`}>{gitTest.text}</span>}</div><p className="field-help">保存后由服务器拉取到共享工作区；测试连接不会创建项目。</p></>}{type === 'snapshot' && <div className="project-file-field"><label>上传快照文件</label><FileDropzone file={snapshotFile} onFileChange={setSnapshotFile} accept=".zip" hint="支持 ZIP 快照，文件会上传并解压到服务器共享工作区" />{editingId && <p className="field-help">已有快照项目可直接保存项目设置；选择新的 ZIP 将替换服务器工作区。</p>}</div>}<div className="knowledge-binding"><div className="binding-head"><div><b>使用 RAGFlow 知识库</b><small>连接配置在“设置 → RAGFlow”，这里可同时选择多个知识库</small></div><label className="switch"><input type="checkbox" aria-label="使用 RAGFlow 知识库" checked={ragflowEnabled} onChange={event => { const enabled = event.target.checked; setRagflowEnabled(enabled); setRagflowMessage(null); if (enabled) loadRagflowDatasets() }} /><span /></label></div>{ragflowEnabled && <div className="binding-fields"><button className="quiet small" onClick={loadRagflowDatasets} disabled={ragflowLoading}>{ragflowLoading ? '获取中…' : '刷新可用知识库'}</button>{ragflowDatasets.length > 0 && <div className="dataset-picker"><small>选择要绑定的知识库（可多选）</small>{ragflowDatasets.map(dataset => <label key={dataset.id}><input type="checkbox" checked={ragflowDatasetIds.includes(dataset.id)} onChange={event => setRagflowDatasetIds(current => event.target.checked ? [...new Set([...current, dataset.id])] : current.filter(id => id !== dataset.id))} /><span><b>{dataset.name || dataset.id}</b><em>{dataset.id}</em></span></label>)}</div>}{ragflowMessage && <div className={`binding-message ${ragflowMessage.tone}`}>{ragflowMessage.text}</div>}</div>}</div><label>项目说明<textarea value={description} onChange={event => setDescription(event.target.value)} placeholder="说明这个项目用于什么协作" /></label></div><footer><button className="quiet small" onClick={() => setFormOpen(false)}>取消</button><button className="primary small" onClick={save}>保存项目</button></footer></section></div>}
  </div>
}

function Audit() {
  const [items, setItems] = useState([])
  const [query, setQuery] = useState('')
  const [actor, setActor] = useState('')
  const [type, setType] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let disposed = false
    const load = () => api.audit().then(result => { if (!disposed) { setItems(result.items || []); setError('') } }).catch(e => { if (!disposed) setError(e.message || '加载失败') }).finally(() => { if (!disposed) setLoading(false) })
    load()
    const timer = setInterval(load, 10000)
    return () => { disposed = true; clearInterval(timer) }
  }, [revision])
  const filtered = items.filter(item => (!actor || item.actor === actor) && (!type || item.type === type) && ['actor', 'action', 'target', 'type'].some(key => String(item[key] || '').toLowerCase().includes(query.trim().toLowerCase())))
  const exportReport = () => {
    const cell = value => '"' + String(value || '').replaceAll('"', '""') + '"'
    const csv = '\uFEFF' + [['时间', '执行者', '事件类型', '操作', '对象'], ...filtered.map(item => [item.createdAt, item.actor, item.type, item.action, item.target])].map(row => row.map(cell).join(',')).join('\r\n')
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
    const link = document.createElement('a'); link.href = url; link.download = 'codeweave-audit.csv'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  return <div className="page audit-page"><div className="page-head"><div><small>SECURITY & OBSERVABILITY</small><h1>审计与活动</h1><p>团队协作的每一步，都有迹可循。</p></div><button className="quiet" disabled={!filtered.length || !!error} onClick={exportReport}>导出记录 ↓</button></div>
    <div className="audit-toolbar"><div className="audit-search"><span aria-hidden="true">⌕</span><input aria-label="搜索审计记录" placeholder="搜索执行者、操作或对象…" value={query} onChange={e => setQuery(e.target.value)} /></div><div className="audit-selects"><label>执行者<select value={actor} onChange={e => setActor(e.target.value)}><option value="">全部成员</option>{[...new Set(items.map(item => item.actor).filter(Boolean))].map(value => <option key={value}>{value}</option>)}</select></label><label>事件类型<select value={type} onChange={e => setType(e.target.value)}><option value="">全部事件</option>{[...new Set(items.map(item => item.type).filter(Boolean))].map(value => <option key={value}>{value}</option>)}</select></label>{(query || actor || type) && <button className="quiet" onClick={() => { setQuery(''); setActor(''); setType('') }}>重置</button>}</div></div>
    <div className="audit-caption"><span>最近 100 条记录 · 当前显示 {filtered.length} 条</span><button onClick={() => setRevision(value => value + 1)}>刷新 ↻</button></div>
    <section className="panel audit">{error ? <div className="empty-state" role="alert"><strong>审计记录加载失败</strong><span>{error}</span><button className="quiet" onClick={() => setRevision(value => value + 1)}>重试</button></div> : loading ? <div className="empty-state">正在加载记录…</div> : filtered.length ? <><div className="audit-row head"><span>时间</span><span>执行者</span><span>操作 / 对象</span><span>事件类型</span></div>{filtered.map(item => <div className="audit-row" key={item.id}><time dateTime={item.createdAt}>{new Date(item.createdAt).toLocaleString('zh-CN')}</time><span>{item.actor || '系统'}</span><div><b>{item.action}</b><small>{item.target}</small></div><Badge tone={item.tone || 'cyan'}>{item.type}</Badge></div>)}</> : <div className="empty-state"><strong>{items.length ? '没有匹配的记录' : '暂无审计记录'}</strong><span>{items.length ? '试试其他关键词或清除筛选条件。' : '项目、任务和 Agent 协作事件会显示在这里。'}</span></div>}</section></div>
}
export default function App() {
  const [logged, setLogged] = useState(Boolean(localStorage.getItem('codeweave-token')))
  useEffect(() => {
    if (!logged) return
    api.me().catch(() => {
      localStorage.removeItem('codeweave-token')
      setLogged(false)
    })
  }, [])
  // A full browser refresh starts at the workspace home page. Menu navigation
  // remains stateful during the current session, but stale page state should
  // not unexpectedly reopen an audit screen.
  const [active, setActive] = useState('overview')
  const [session, setSession] = useState(null)
  const [selectedProject, setSelectedProject] = useState(null)
  const [items, setItems] = useState([])
  const [summary, setSummary] = useState({ metrics: { runningAgents: 0, pendingApprovals: 0, unresolvedConflicts: null, membersOnline: 0 }, projects: [], attention: [], activity: [] })
  const [defaultModelName, setDefaultModelName] = useState('')
  const [models, setModels] = useState([])
  const [dshConsoleUrl, setDshConsoleUrl] = useState('')
  const [dshConsoleError, setDshConsoleError] = useState('')
  const [dshRetry, setDshRetry] = useState(0)
  const [toast, setToast] = useState('')
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [sidebarCollapsed, setSidebarCollapsed] = useState(() => localStorage.getItem('codeweave-sidebar-collapsed') === 'true')
  const [access, setAccess] = useState({ menus: ['overview', 'agent', 'projects', 'conflicts', 'audit', 'settings'], permissions: ['*'] })
  const [profile, setProfile] = useState(() => normalizeProfile({ gender: 'male' }))
  const nav = id => { setActive(id); localStorage.setItem('codeweave-active-page', id) }
  useEffect(() => {
    if (!logged) return
    const heartbeat = () => { if (localStorage.getItem('codeweave-token')) api.heartbeat().catch(() => {}) }
    heartbeat()
    const timer = setInterval(heartbeat, 20000)
    window.addEventListener('focus', heartbeat)
    window.addEventListener('online', heartbeat)
    document.addEventListener('visibilitychange', heartbeat)
    return () => {
      clearInterval(timer)
      window.removeEventListener('focus', heartbeat)
      window.removeEventListener('online', heartbeat)
      document.removeEventListener('visibilitychange', heartbeat)
    }
  }, [logged])
  useEffect(() => {
    const openSettings = () => setSettingsOpen(true)
    window.addEventListener('codeweave:open-settings', openSettings)
    return () => window.removeEventListener('codeweave:open-settings', openSettings)
  }, [])
  const login = async (email, password) => {
    const result = await api.login(email, password)
    if (!result?.token) throw new Error('登录失败：服务器未返回令牌')
    localStorage.setItem('codeweave-token', result.token)
    setLogged(true)
  }
  const logout = () => { localStorage.removeItem('codeweave-token'); localStorage.removeItem('codeweave-selected-project'); localStorage.removeItem('codeweave-session-id'); localStorage.removeItem('codeweave-active-page'); setLogged(false); setActive('overview'); setSelectedProject(null); setSession(null) }
  useEffect(() => {
    if (!logged) return
    const refreshWorkspace = () => Promise.allSettled([api.sessions(), api.summary(), api.me(), api.projects(), api.models(), api.modelCatalog(), api.profile()]).then(([sessionResult, summaryResult, accessResult, projectResult, modelResult, catalogResult, profileResult]) => {
      if (sessionResult.status === 'fulfilled') { const available = sessionResult.value?.items || []; setItems(latestSessionsByProject(available)); const savedId = localStorage.getItem('codeweave-session-id'); const saved = available.find(item => item.id === savedId); if (saved) setSession(saved) }
      if (summaryResult.status === 'fulfilled') setSummary(summaryResult.value)
      if (projectResult.status === 'fulfilled') { const projects = projectResult.value?.items || []; setSummary(current => ({ ...current, projects })); const storedId = localStorage.getItem('codeweave-selected-project'); const selected = projects.find(item => item.id === storedId); if (selected) setSelectedProject(selected); else { setSelectedProject(null); setDshConsoleUrl(''); if (storedId) localStorage.removeItem('codeweave-selected-project') } }
      if (accessResult.status === 'fulfilled') { setAccess(accessResult.value); setProfile(current => normalizeProfile({ ...current, ...accessResult.value })) }
      if (modelResult.status === 'fulfilled') { const available = modelResult.value?.items || []; const configured = available.filter(item => item.hasApiKey); setDefaultModelName((available.find(item => item.defaultModel) || available[0])?.name || ''); if (catalogResult.status !== 'fulfilled' || !(catalogResult.value?.items || []).length) setModels(configured.map(item => ({ ...item, id: `${item.id}::${item.name}`, modelId: item.name, providerName: item.name }))) }
      if (catalogResult.status === 'fulfilled' && (catalogResult.value?.items || []).length) setModels(catalogResult.value.items)
      if (profileResult.status === 'fulfilled') setProfile(current => normalizeProfile({ ...current, ...profileResult.value }))
    })
    refreshWorkspace()
    const timer = setInterval(refreshWorkspace, 30000)
    return () => clearInterval(timer)
  }, [logged])
  useEffect(() => {
    if (!logged) return undefined
    let disposed = false
    if (!selectedProject?.workspaceReady) { setDshConsoleUrl(''); setDshConsoleError(''); return undefined }
    setDshConsoleError('')
    api.dshConsole({ projectId: selectedProject.id, name: selectedProject.name }).then(result => { if (!disposed) setDshConsoleUrl(result?.url || '') }).catch(error => { if (!disposed) { setDshConsoleUrl(''); setDshConsoleError(error.status === 409 ? '请先在设置中配置至少一个模型，配置完成后再进入 Agent 工作台。' : (error.message || '')) } })
    return () => { disposed = true }
  }, [logged, selectedProject?.id, selectedProject?.name, selectedProject?.workspaceReady, dshRetry])
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
  let page = <Overview profile={profile} metrics={summary.metrics} collaboration={summary.collaboration} items={items} attention={summary.attention} activity={summary.activity} nav={nav} open={s => { setSession(s); localStorage.setItem('codeweave-session-id', s.id); nav('agent') }} />
  if (active === 'agent') page = !selectedProject ? <DshNoProject nav={nav} hasProjects={Boolean(summary.projects?.length)} /> : !selectedProject.workspaceReady ? <DshProjectNeedsDirectory project={selectedProject} nav={nav} /> : dshConsoleUrl ? <DshConsole url={dshConsoleUrl} project={selectedProject} nav={nav} onToast={message => { setToast(message); setTimeout(() => setToast(''), 3500) }} /> : <DshUnavailable nav={nav} message={dshConsoleError} onRetry={() => setDshRetry(value => value + 1)} />
  if (active === 'conflicts') page = <Conflicts nav={nav} />
  if (active === 'projects') page = <Projects projects={summary.projects} nav={nav} onProjectsChange={updateProjects} onOpenProject={project => { setSelectedProject(project); localStorage.setItem('codeweave-selected-project', project.id); nav('agent') }} onToast={message => { setToast(message); setTimeout(() => setToast(''), 3500) }} />
  if (active === 'audit') page = <Audit />
  return <div className="shell"><Header collapsed={sidebarCollapsed} onToggle={toggleSidebar} modelName={defaultModelName} project={selectedProject} profile={profile} onAvatarUpload={uploadAvatar} onLogout={logout} /><Sidebar active={active} setActive={setActive} logout={logout} openSettings={() => setSettingsOpen(true)} access={access} collapsed={sidebarCollapsed} /><main className={`main${sidebarCollapsed ? ' sidebar-collapsed' : ''}`}>{page}</main>{toast && <div className="toast">{toast}</div>}{settingsOpen && <SettingsModal access={access} onClose={() => setSettingsOpen(false)} onDefaultModelChange={setDefaultModelName} onToast={message => { setToast(message); setTimeout(() => setToast(''), 3500) }} />}<footer className="statusbar"><span>⌁ {selectedProject?.name || '未选择项目'}</span><span>Rust heartbeat 4ms</span><span>Tokens 38.4k / 500k</span><span className="online">◉ WS Connected · 3 active</span></footer></div>
}

const container = document.getElementById('root')
const rootKey = Symbol.for('codeweave.reactRoot')
// Keep the root on its container so re-executing this entry during HMR reuses it.
const root = container[rootKey] ?? (container[rootKey] = createRoot(container))
root.render(<App />)


