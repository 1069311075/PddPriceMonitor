# 创建 GitHub Release v0.11.63 并上传 APK（凭据取自 git 凭据管理器，不落盘）
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ErrorActionPreference = 'Stop'

$repo = '1069311075/PddPriceMonitor'
$tag = 'v0.11.63'
$apkName = 'app-v0.11.63-debug.apk'
$apkPath = "D:\开发\pdd\PddPriceMonitor\dist\$apkName"

# 1. 取已存的 GitHub 凭据（与 git push 同一身份）；经临时文件传参，避开 PS5.1 管道编码差异
$qfile = Join-Path $env:TEMP 'git_cred_query.txt'
[System.IO.File]::WriteAllText($qfile, "protocol=https`nhost=github.com`n`n", [System.Text.Encoding]::ASCII)
$credLines = Get-Content $qfile | git credential fill
Remove-Item $qfile -ErrorAction SilentlyContinue
$token = ($credLines | Where-Object { $_ -match '^password=' }) -replace '^password=', ''
if (-not $token) { throw "未能从凭据管理器取到 GitHub token: $credLines" }

$headers = @{
    Authorization          = "Bearer $token"
    Accept                 = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = '2022-11-28'
}

# 2. 创建 Release
$notes = @'
## 本版亮点（自 v0.11.3 以来）

**识别与匹配**
- 两遍 OCR 融合：全屏识别 + 标题区裁剪放大重读，编辑距离对齐 + 上下文投票修正形近字（李/季、味/昧）
- 价格紧聚焦二级放大：整行 3 倍初读定位数字，再裁数字区放大 12–18 倍精读，小数点识别率大幅提升
- 数字守卫：同系列不同规格（如 24 盒 vs 12 盒）不再误合并成一条价格史；新增 OCR 签名比对（DB v6）

**识别后自动保存（默认关）**
- 开启后识别成功即入库，面板转为「回执」：对了不用管，错了点「修改保存」或「重新识别」，自动保存的行会被撤回重记（DB v7）
- 结果面板显示时长可配：4 / 8 / 15 / 30 秒 / 常驻

**比价应用**
- 长按商品卡片弹出径向菜单，滑到图标上松手即复制标题并跳转该应用
- 双击悬浮球按顺序轮换比价，长按悬浮球回拼多多后从头开始；已选应用可用 ↑↓ 调整顺序

**其它**
- 识别截图存档：识别画面随价格记录保存，历史明细点「截图」可回看当时商品页
- 悬浮球位置记忆：拖到哪贴哪，重启不丢
- 打开 App 安静模式：只看记录不再自动启球、不弹授权
- 界面重设计：弹窗设计系统、iOS 分组式设置、卡片层级强化

## 安装说明

1. 文件约 70MB，下载后请确认后缀为 `.apk`
2. **不要用迅雷下载**（会直接打开已装旧版而不是安装更新），请用系统浏览器或文件管理器
3. 同签名覆盖安装：**无需卸载旧版**，价格历史与全部数据保留
4. 需要 Android 10 及以上
'@

$body = @{
    tag_name         = $tag
    name             = $tag
    target_commitish = 'main'
    body             = $notes
} | ConvertTo-Json
$release = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases" `
    -Method Post -Headers $headers `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
    -ContentType 'application/json; charset=utf-8'
Write-Output ("Release 已创建: " + $release.html_url)

# 3. 上传 APK 资产
$uploadBase = $release.upload_url -replace '\{\?name,label\}', ''
$asset = Invoke-RestMethod -Uri "$uploadBase`?name=$apkName" `
    -Method Post -Headers $headers -InFile $apkPath `
    -ContentType 'application/vnd.android.package-archive'
Write-Output ("APK 已上传: " + $asset.browser_download_url + "  (" + [math]::Round($asset.size / 1MB, 1) + " MB)")
