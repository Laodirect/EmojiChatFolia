@'
$RemoteUrl = "https://github.com/Laodirect/EmojiChatFolia.git"
$BranchName = "main"
$CommitMessage = "release: upload latest project"
$ForceOverwrite = $true

Write-Host "====================================="
Write-Host " 自动上传当前项目到 GitHub"
Write-Host "====================================="
Write-Host ""

git --version > $null 2>&1

if ($LASTEXITCODE -ne 0) {
    Write-Host "错误：未检测到 Git，请先安装 Git for Windows。" -ForegroundColor Red
    exit 1
}

git config --global http.sslBackend schannel

Write-Host "当前目录：$(Get-Location)"
Write-Host "目标仓库：$RemoteUrl"
Write-Host ""

if (!(Test-Path ".git")) {
    Write-Host "当前项目还不是 Git 仓库，正在初始化..."
    git init
}

git branch -M $BranchName

$remoteList = git remote
$remoteExists = $remoteList -contains "origin"

if ($remoteExists) {
    Write-Host "检测到已有 origin，正在更新远程地址..."
    git remote set-url origin $RemoteUrl
} else {
    Write-Host "未检测到 origin，正在添加远程仓库..."
    git remote add origin $RemoteUrl
}

Write-Host ""
Write-Host "当前远程仓库："
git remote -v
Write-Host ""

Write-Host "正在添加所有本地改动..."
git add -A

$status = git status --porcelain

if ($status) {
    Write-Host "检测到改动，正在提交..."
    git commit -m $CommitMessage
} else {
    Write-Host "没有新的改动需要提交。"
}

Write-Host ""
Write-Host "正在推送到 GitHub..."

if ($ForceOverwrite) {
    Write-Host "注意：当前模式为强制覆盖 GitHub main 分支。" -ForegroundColor Yellow
    git push -u origin $BranchName --force
} else {
    git push -u origin $BranchName
}

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "上传完成！" -ForegroundColor Green
    Write-Host "仓库地址：$RemoteUrl"
} else {
    Write-Host ""
    Write-Host "上传失败，请查看上方错误信息。" -ForegroundColor Red
    exit 1
}
'@ | Set-Content -Encoding UTF8 update.ps1