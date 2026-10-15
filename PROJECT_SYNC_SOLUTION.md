# CodeWeave 项目切换与 DSH 同步解决方案

## 问题描述

用户登录后从"项目与工作区"点击不同项目时，DSH 控制台的左侧文件夹不会切换成对应的项目。

**需求：实现双向同步**
- 从项目列表切换项目 → DSH 跟着切换
- 从 DSH 中间切换项目 → 项目列表跟着切换
- 右侧的用户在线状态和会话跟随项目切换

## 修改内容

### 1. 前端修改 (codeweave-frontend/src/main.jsx)

#### 修改点 1：添加 iframe 引用和 workspace 切换逻辑

**位置：** `DshConsole` 组件（第 519-579 行）

**改动：**
```javascript
function DshConsole({ url, project, nav, onToast, onWorkspaceChange }) {
  // ✅ 新增：iframe 引用
  const iframeRef = useRef(null)
  const currentWorkspaceId = useRef('')
  
  // ✅ 已有：监听来自 DSH 的切换消息（DSH → 前端）
  useEffect(() => {
    const receive = event => {
      if (event.data?.type !== 'codeweave:dsh-workspace-changed') return
      onWorkspaceChange?.(String(event.data.workspaceId || ''))
    }
    window.addEventListener('message', receive)
    return () => window.removeEventListener('message', receive)
  }, [onWorkspaceChange])
  
  // ✅ 新增：监听项目变化，通知 DSH 切换（前端 → DSH）
  useEffect(() => {
    const workspaceId = project?.dshWorkspaceId || ''
    if (!workspaceId || workspaceId === currentWorkspaceId.current) return
    currentWorkspaceId.current = workspaceId
    const frame = iframeRef.current
    if (!frame || !frame.contentWindow) return
    // 等待 iframe 加载完成后再发送消息
    const timer = setTimeout(() => {
      try {
        frame.contentWindow.postMessage({ type: 'codeweave:switch-workspace', workspaceId }, '*')
        console.info('[CodeWeave] 通知 DSH 切换到 workspace:', workspaceId)
      } catch (error) {
        console.warn('[CodeWeave] 无法向 DSH 发送切换消息:', error)
      }
    }, 1000)
    return () => clearTimeout(timer)
  }, [project?.dshWorkspaceId])
  
  // ... 其他代码
  
  // ✅ 修改：添加 ref，移除导致重载的 key
  return <div className="dsh-console-shell">
    {/* ... */}
    <iframe 
      ref={iframeRef}  // ← 新增 ref
      // key={frameUrl}  // ← 移除，避免不必要的重载
      className="dsh-console-frame" 
      src={frameUrl} 
      title="CodeWeave Agent 工作台" 
      allow="clipboard-read; clipboard-write" 
    />
    {/* ... */}
  </div>
}
```

### 2. DSH 修改 (deepseek-harness/packages/client/ui-workspace/src/client/index.ts)

**位置：** `apply` 函数（第 154 行之后）

**改动：**
```typescript
// ✅ 新增：监听来自 CodeWeave 前端的 workspace 切换请求
if (typeof window !== 'undefined' && window.parent !== window) {
  const handleCodeWeaveSwitch = (event: MessageEvent): void => {
    if (event.data?.type !== 'codeweave:switch-workspace') return
    const targetWorkspaceId = String(event.data.workspaceId || '')
    if (!targetWorkspaceId) return
    console.info('[DSH] CodeWeave 请求切换到 workspace:', targetWorkspaceId)
    const snapshot = workspaces.list.getSnapshot()
    if (snapshot.phase !== 'ready') {
      console.warn('[DSH] Workspace 列表未就绪，无法切换')
      return
    }
    if (!snapshot.items.some(item => item.workspaceId === targetWorkspaceId)) {
      console.warn('[DSH] 目标 workspace 不存在:', targetWorkspaceId)
      return
    }
    void uiWorkspace.openWorkspace(targetWorkspaceId as WorkspaceId).catch(error => {
      console.warn('[DSH] 切换 workspace 失败:', error)
    })
  }
  window.addEventListener('message', handleCodeWeaveSwitch)
  ctx.effect(() => {
    return () => window.removeEventListener('message', handleCodeWeaveSwitch)
  }, 'ui-workspace: CodeWeave workspace switch listener')
}
```

## 工作原理

### 双向通信流程

```
┌─────────────────────────────────────────────────────────────┐
│                       CodeWeave 前端                          │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  项目列表                                            │    │
│  │  - 项目 A (workspaceId: ws-001)                     │    │
│  │  - 项目 B (workspaceId: ws-002) ← 用户点击          │    │
│  │  - 项目 C (workspaceId: ws-003)                     │    │
│  └─────────────────────────────────────────────────────┘    │
│                          ↓                                    │
│              useEffect 监听 project.dshWorkspaceId          │
│                          ↓                                    │
│        postMessage({ type: 'codeweave:switch-workspace',     │
│                      workspaceId: 'ws-002' })                │
└───────────────────────────┬─────────────────────────────────┘
                            │ iframe.contentWindow.postMessage
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                       DSH iframe                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  监听 'message' 事件                                 │    │
│  │  ↓                                                   │    │
│  │  type === 'codeweave:switch-workspace'               │    │
│  │  ↓                                                   │    │
│  │  uiWorkspace.openWorkspace(workspaceId)              │    │
│  │  ↓                                                   │    │
│  │  左侧文件夹切换到项目 B                              │    │
│  └─────────────────────────────────────────────────────┘    │
│                          ↓                                    │
│              用户在 DSH 中手动切换到项目 C                   │
│                          ↓                                    │
│        window.parent.postMessage({                           │
│          type: 'codeweave:dsh-workspace-changed',            │
│          workspaceId: 'ws-003' })                            │
└───────────────────────────┬─────────────────────────────────┘
                            │ window.parent.postMessage
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                       CodeWeave 前端                          │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  监听 'message' 事件                                 │    │
│  │  ↓                                                   │    │
│  │  type === 'codeweave:dsh-workspace-changed'          │    │
│  │  ↓                                                   │    │
│  │  根据 workspaceId 查找对应项目                       │    │
│  │  ↓                                                   │    │
│  │  setSelectedProject(项目 C)                          │    │
│  │  ↓                                                   │    │
│  │  右侧显示项目 C 的在线成员和会话                     │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

## 测试步骤

### 测试 1：前端 → DSH 切换

1. 启动 CodeWeave 后端和前端
2. 登录系统
3. 创建至少 2 个项目（确保都有 `dshWorkspaceId`）
4. 进入 "Agent 工作台"
5. 在项目列表中点击项目 A
6. **预期结果：**
   - DSH 左侧文件夹显示项目 A 的内容
   - 右侧显示项目 A 的在线成员和会话
7. 点击项目 B
8. **预期结果：**
   - DSH 左侧文件夹**自动切换**到项目 B
   - 右侧显示项目 B 的在线成员和会话
   - **控制台输出：** `[CodeWeave] 通知 DSH 切换到 workspace: <workspaceId>`
   - **控制台输出：** `[DSH] CodeWeave 请求切换到 workspace: <workspaceId>`

### 测试 2：DSH → 前端切换

1. 在 DSH 中间的项目切换器中选择另一个项目
2. **预期结果：**
   - 前端项目列表的选中状态自动切换
   - 右侧在线成员和会话更新为新项目的内容
   - **控制台输出：** `[DSH] CodeWeave workspace changed: <workspaceId>`

### 测试 3：验证同步状态

1. 切换到项目 A
2. 验证：
   - DSH 左侧显示项目 A 的文件
   - 右侧显示项目 A 的在线成员（如果有其他用户在线）
   - 右侧显示项目 A 的会话列表
3. 在 DSH 中切换到项目 B
4. 验证：
   - 前端项目选中状态变为项目 B
   - 右侧成员和会话自动更新为项目 B 的内容

## 关键技术点

### 1. 避免 iframe 重载
- **问题：** 之前使用 `key={frameUrl}` 导致项目切换时 iframe 完全重新加载
- **解决：** 移除 `key`，使用 `postMessage` 通知 DSH 切换，避免重载

### 2. 消息类型
- **前端 → DSH：** `codeweave:switch-workspace`
- **DSH → 前端：** `codeweave:dsh-workspace-changed`

### 3. 防重复切换
使用 `currentWorkspaceId.useRef()` 记录当前 workspaceId，避免重复发送切换消息

### 4. 延迟发送消息
```javascript
setTimeout(() => {
  frame.contentWindow.postMessage(...)
}, 1000)
```
确保 iframe 加载完成后再发送消息

## Backup 文件夹说明

**问题 1：** `deepseek-harness-backup-20261015-112933` 是做什么的？

**回答：** 这是 DeepSeek Harness 的自动备份文件夹，从时间戳来看是 2026年10月15日 11:29:33 创建的备份。

**建议：** 
- ✅ **可以删除** - 如果当前 DSH 运行正常，这个备份不需要保留
- 备份通常在更新或重大操作前自动创建
- 如果近期没有遇到 DSH 问题，可以安全删除

```bash
# 删除备份（可选）
rm -rf deepseek-harness-backup-20261015-112933
```

## 注意事项

1. **workspaceId 必须存在：** 项目必须有 `dshWorkspaceId` 才能切换
2. **跨域通信安全：** 消息使用 `'*'` 作为 targetOrigin，生产环境可以改为具体的域名
3. **错误处理：** 如果 workspace 不存在，控制台会输出警告但不会中断用户操作
4. **性能考虑：** 使用 `useRef` 避免不必要的重复切换

## 后续改进建议

1. **优化切换延迟：** 当前使用固定 1000ms 延迟，可以改为监听 iframe 的 `load` 事件
2. **添加加载状态：** 在切换过程中显示加载指示器
3. **错误提示：** 当切换失败时，向用户显示友好的错误提示
4. **持久化选择：** 记住用户最后选择的项目，下次登录自动恢复

## 文件修改清单

- ✅ `codeweave-frontend/src/main.jsx` - DshConsole 组件
- ✅ `deepseek-harness/packages/client/ui-workspace/src/client/index.ts` - apply 函数
