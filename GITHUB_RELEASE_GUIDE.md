# CodeWeave 上传 GitHub 开源操作文档

本文档针对当前 CodeWeave 仓库编写，适用于将以下内容作为一个 GitHub 仓库发布：

- `codeweave-backend`：Spring Boot 后端
- `codeweave-frontend`：React + Vite 前端
- `deepseek-harness`：基于 MIT 项目的二次开发版本

## 一、发布前确认

### 1. 确认当前目录

在 PowerShell 中执行：

```powershell
Set-Location D:\Workspace\codeweave
git status --short --branch
```

确认当前目录就是 CodeWeave 根目录。

### 2. 确认 deepseek-harness 是二开代码

查看其当前状态：

```powershell
git -C deepseek-harness status
git ls-files --stage deepseek-harness
```

当前仓库把 `deepseek-harness` 记录成了 Git 子模块形式，但没有 `.gitmodules`。为了让用户一次克隆就获得完整二开代码，本文采用“普通目录随主仓库发布”的方案。

### 3. 确认 MIT 许可证文件

检查原项目许可证：

```powershell
Get-ChildItem deepseek-harness -Force -Include LICENSE,LICENCE,COPYING,NOTICE
```

必须保留原项目的 MIT License、版权声明和 NOTICE（如果存在）。不要删除或覆盖这些文件。

## 二、将二开版 Harness 纳入主仓库

> 以下操作会改变 Git 的跟踪方式，但不会删除你的二开源文件。执行前建议先复制一份 `deepseek-harness` 目录作为备份。

### 1. 移除 Gitlink 记录

```powershell
git rm --cached deepseek-harness
```

如果提示路径不是已知文件，可以跳过这一步，继续检查下一步。

### 2. 删除二开目录内部的 Git 元数据

仅删除二开目录内部的 `.git`，不要删除仓库根目录的 `.git`：

```powershell
if (Test-Path deepseek-harness\.git) {
    Remove-Item -Recurse -Force deepseek-harness\.git
}
```

### 3. 重新作为普通目录加入

```powershell
git add deepseek-harness
git status --short
```

此时应该能看到大量 `deepseek-harness/...` 文件被加入，而不是只有一行以 `160000` 开头的 Gitlink。

## 三、补齐主项目开源文件

根目录建议至少包含：

```text
README.md
LICENSE
GITHUB_RELEASE_GUIDE.md
codeweave-backend/README.md
codeweave-frontend/README.md
deepseek-harness/LICENSE
```

根目录 `README.md` 应说明：

- CodeWeave 的用途和主要功能
- 前端、后端和 Harness 的目录结构
- Java、Maven、Node.js、Docker 等环境要求
- PostgreSQL、Redis、MinIO、RAGFlow 等依赖
- 后端启动方法
- 前端启动方法
- DeepSeek Harness 启动方法
- 本地配置仅用于开发，生产环境需要自行修改
- `deepseek-harness` 基于 MIT 项目二次开发，并保留原许可证
- 项目整体采用的许可证

如果主项目也采用 MIT，请在根目录添加 `LICENSE`，并保留 `deepseek-harness/LICENSE`。

## 四、发布前本地验证

### 1. 后端验证

```powershell
Set-Location D:\Workspace\codeweave\codeweave-backend
docker compose up -d
mvn test
```

确认测试通过后再停止服务或继续启动：

```powershell
mvn spring-boot:run
```

后端默认地址：`http://localhost:8080`

### 2. 前端验证

在新的 PowerShell 窗口执行：

```powershell
Set-Location D:\Workspace\codeweave\codeweave-frontend
npm ci
npm run build
npm run dev
```

前端默认地址：`http://localhost:5173`

### 3. 检查不应提交的文件

```powershell
Set-Location D:\Workspace\codeweave
git status --short
git ls-files | Select-String 'node_modules|target|dist|\.env|\.idea|\.project-knowledge'
```

第二条命令不应显示构建产物、依赖目录或本地环境文件。

## 五、提交第一次开源版本

### 1. 查看待提交内容

```powershell
git diff --stat
git diff --cached --stat
git status --short
```

确认没有不应公开的个人文件、日志、数据库数据或真实线上凭据。

### 2. 创建提交

```powershell
git add .
git commit -m "chore: prepare CodeWeave open source release"
```

如果 Git 尚未配置身份：

```powershell
git config user.name "你的 GitHub 用户名或姓名"
git config user.email "你的 GitHub 邮箱"
```

### 3. 确认提交成功

```powershell
git log --oneline -3
git status --short --branch
```

## 六、创建 GitHub 仓库并推送

1. 登录 GitHub。
2. 点击 `New repository`。
3. 仓库名称建议使用 `codeweave`。
4. 选择 `Public`。
5. 创建时不要勾选自动生成 README、License 或 `.gitignore`，因为本地已经准备这些文件。

创建完成后，在本地执行：

```powershell
Set-Location D:\Workspace\codeweave
git remote add origin https://github.com/<你的用户名>/codeweave.git
git branch -M main
git push -u origin main
```

如果本地已经存在错误的 `origin`，先查看：

```powershell
git remote -v
```

需要更换地址时执行：

```powershell
git remote set-url origin https://github.com/<你的用户名>/codeweave.git
git push -u origin main
```

## 七、GitHub 页面发布后检查

打开仓库页面，逐项确认：

- 根目录 README 正常显示
- `codeweave-backend` 和 `codeweave-frontend` 存在
- `deepseek-harness` 显示为普通目录，不是无法进入的子模块链接
- 根目录和 `deepseek-harness` 的许可证文件存在
- 没有上传 `node_modules`、`target`、`dist`、`.env` 或数据库文件
- README 中的克隆和启动命令与实际一致

## 八、从全新目录验证克隆

在其他目录执行：

```powershell
Set-Location $env:TEMP
git clone https://github.com/<你的用户名>/codeweave.git codeweave-checkout
Set-Location codeweave-checkout
Get-ChildItem deepseek-harness -Force | Select-Object -First 10
```

确认 `deepseek-harness` 文件完整后，再按 README 启动前后端。

## 九、创建第一个 Release

确认 GitHub 版本可用后执行：

```powershell
Set-Location D:\Workspace\codeweave
git tag -a v0.1.0 -m "Initial open source release"
git push origin v0.1.0
```

然后在 GitHub 的 `Releases` 页面创建 `v0.1.0`，说明：

- 当前支持的功能
- 启动方式
- 已知限制
- 后续计划

## 十、后续日常更新流程

以后每次修改使用：

```powershell
git status
git add .
git commit -m "feat: 描述本次修改"
git push
```

发布新版本时：

```powershell
git tag -a v0.2.0 -m "Release v0.2.0"
git push origin v0.2.0
```

## 重要提醒

- MIT 允许你发布二次开发版本，但必须保留原项目的许可证和版权声明。
- `deepseek-harness` 如果作为普通目录提交，用户不需要执行子模块初始化。
- 不要删除仓库根目录的 `.git`。
- 本地开发配置可以保留，但部署到公网前应更换默认密码、加密密钥和服务凭据。
- 如果未来把 `deepseek-harness` 拆成独立仓库，应在 CodeWeave README 中写清楚两个仓库的关系和版本对应关系。
