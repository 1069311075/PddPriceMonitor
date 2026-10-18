<#
.SYNOPSIS
  截图相册（hd_captures）一键真机验收脚本。

.DESCRIPTION
  自动完成：环境预检 → 注入边界测试夹具 → 启动应用 → 人工过一遍界面 →
  按「你运行了哪个清理」对设备上的文件状态逐项断言 → 清除夹具恢复原状。
  只增删 fix-* / .staging-fixture 这几个脚本自建的目录，绝不碰真实截图。

  用法（在仓库根目录）：
    powershell -ExecutionPolicy Bypass -File regression\verify_hd_gallery.ps1            # 默认不装 APK
    powershell ... -Install                                                              # 先 adb install -r -d dist 里的 APK
    powershell ... -Serial 26ea2937 -SkipLaunch                                          # 指定设备序列号 / 不自动启动应用
    powershell ... -Keep                                                                 # 断言后保留夹具（便于继续手动检查）

  断言口径（与 HdCaptureStore.cutoffMillis 一致：只删 capturedAt < 本机零点截止）：
    30 天清理 → 删 31天/100天/90天整点 三条，边界 30 天 00:00 与 29 天保留，日期未知跳过
    90 天清理 → 只删 100 天一条，90 天 00:00 边界保留，日期未知跳过
    全部     → 九条夹具全删（含日期未知），.staging 暂存目录永不删
  时区假设：电脑与手机同一时区（无跨时区校验）。
#>
param(
    [string]$Adb = 'C:\Users\djjjjj\AppData\Local\Android\Sdk\platform-tools\adb.exe',
    [string]$Serial = '26ea2937',
    [string]$Package = 'com.example.pddpricemonitor',
    [string]$ExpectedVersion = '0.11.95',
    [int]$ExpectedCode = 124,
    [switch]$Install,
    [switch]$SkipLaunch,
    [switch]$Keep
)

$ErrorActionPreference = 'Stop'
$script:Passed = 0
$script:Failed = 0
$script:FailLines = @()
$OutDir = Join-Path $env:TEMP 'verify_hd_gallery_out'
$RemoteTmp = '/data/local/tmp/vhdg'
$Hd = "files/hd_captures"

function Check($name, $ok, $detail) {
    if ($ok) { $script:Passed++; Write-Output ("  [OK]   {0}" -f $name) }
    else {
        $script:Failed++
        $line = if ($detail) { "{0} —— {1}" -f $name, $detail } else { $name }
        $script:FailLines += $line
        Write-Output ("  [FAIL] {0}" -f $line)
    }
}

# ---- 阶段 0：环境预检 -------------------------------------------------------
Write-Output '== 阶段 0：环境预检 =='
Check 'adb 存在' (Test-Path $Adb) "未找到 $Adb，用 -Adb 指定"
if (-not (Test-Path $Adb)) { exit 1 }

$devices = & $Adb devices | Select-Object -Skip 1 | ForEach-Object { ($_ -split '\s+')[0].Trim() } | Where-Object { $_ }
Check ('设备已连接（{0}）' -f $Serial) ($devices -contains $Serial) ("当前在线：{0}" -f ($devices -join ', '))
if ($devices -notcontains $Serial) { exit 1 }
$A = @('-s', $Serial)

if ($Install) {
    $apk = Get-ChildItem dist\app-v*.apk | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($apk) {
        Write-Output ("  安装 {0}" -f $apk.Name)
        & $Adb @A install -r -d $apk.FullName | Write-Output
    } else { Write-Output '  [WARN] dist 下没有 APK，跳过安装' }
}

$dump = (& $Adb @A shell "dumpsys package $Package") -join "`n"
$verName = if ($dump -match 'versionName=([\d\.]+)') { $Matches[1] } else { '' }
$verCode = if ($dump -match 'versionCode=(\d+)') { $Matches[1] } else { '' }
Check '应用已安装' ($verName -ne '')
if ($verName) {
    Check ('版本 {0}({1})' -f $ExpectedVersion, $ExpectedCode) `
        ($verName -eq $ExpectedVersion -and $verCode -eq [string]$ExpectedCode) `
        "设备上是 $verName($verCode)"
}

function RemoteList {
    # 列出 hd_captures 一级目录；目录不存在按空处理
    $out = (& $Adb @A shell "run-as $Package ls $Hd 2>/dev/null") | ForEach-Object { $_.Trim() } | Where-Object { $_ }
    @($out)
}
$baseline = @(RemoteList | Where-Object { $_ -notmatch '^fix-' -and $_ -notmatch '^\.staging-fixture$' })
Write-Output ('  设备现有真实截图 {0} 条（验收后应保持不变）' -f $baseline.Count)

# ---- 阶段 1：本地生成夹具 ---------------------------------------------------
Write-Output ''
Write-Output '== 阶段 1：生成本地夹具 =='
Add-Type -AssemblyName System.Drawing
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Path $OutDir | Out-Null

# 本机零点 epoch 毫秒（与 cutoffMillis 的 Calendar 零点对齐）
function MidnightMs([int]$DaysAgo) {
    $d = [DateTime]::Today.AddDays(-$DaysAgo)
    [DateTimeOffset]::new($d.Year, $d.Month, $d.Day, 0, 0, 0, [DateTimeOffset]::Now.Offset).ToUnixTimeMilliseconds()
}
function DaysAgoNoonMs([int]$DaysAgo) { [DateTimeOffset]::Now.AddDays(-$DaysAgo).ToUnixTimeMilliseconds() }
$NowMs = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()

# id / 格式 / capturedAt（null=不写该键）/ 说明
$Fixtures = @(
    , @('fix-a-today',     'jpg',  $NowMs,                    '今天：任何清理都保留')
    , @('fix-b-29d',       'jpg',  (DaysAgoNoonMs 29),        '29天前：30天清理应保留')
    , @('fix-c-30d-exact', 'jpg',  (MidnightMs 30),           '恰好30天00:00：=截止，应保留（边界）')
    , @('fix-d-31d',       'jpg',  (DaysAgoNoonMs 31),        '31天前：30天清理应删除')
    , @('fix-e-100d',      'png',  (DaysAgoNoonMs 100),       '100天前(PNG)：30/90清理都应删除')
    , @('fix-f-90d-exact', 'png',  (MidnightMs 90),           '恰好90天00:00(PNG)：90天清理应保留（边界）')
    , @('fix-g-unknown',   'png',  $null,                     '无capturedAt：日期未知，时间清理跳过、全部删除')
    , @('fix-h-broken',    'jpg',  $NowMs,                    '主图缺失：broken占位，相册不崩')
)

function NewImage([string]$Path, [string]$Text, [bool]$IsPng) {
    $bmp = New-Object System.Drawing.Bitmap(540, 960)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear([System.Drawing.Color]::WhiteSmoke)
    $font = New-Object System.Drawing.Font('Arial', 30)
    $g.DrawString($Text, $font, [System.Drawing.Brushes]::Firebrick, 20, 60)
    $g.Dispose()
    if ($IsPng) { $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png) }
    else { $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Jpeg) }
    $bmp.Dispose()
}

$stagingLocal = Join-Path $OutDir '.staging-fixture'
New-Item -ItemType Directory -Path $stagingLocal -Force | Out-Null
Set-Content -Path (Join-Path $stagingLocal 'partial.jpg') -Value 'staging junk' -Encoding Ascii

foreach ($f in $Fixtures) {
    $id = $f[0]; $ext = $f[1]; $ts = $f[2]; $note = $f[3]
    $dir = Join-Path $OutDir $id
    New-Item -ItemType Directory -Path $dir | Out-Null
    $mainName = "original.$ext"
    NewImage (Join-Path $dir $mainName) "VERIFY $id" ($ext -eq 'png')
    # 主图大小先记下：broken 夹具随后会把主图删掉
    $mainSize = (Get-Item (Join-Path $dir $mainName)).Length

    if ($id -eq 'fix-h-broken') {
        # 主图故意缺档：只留元数据 → readEntry 应给 broken 占位
        Remove-Item (Join-Path $dir $mainName) -Force
    } else {
        # 缩略图直接复制主图充数（应用只要求存在，不校验内容）
        Copy-Item (Join-Path $dir $mainName) (Join-Path $dir 'thumb.jpg')
    }

    $meta = New-Object System.Collections.Specialized.OrderedDictionary
    $meta.schemaVersion = 2
    $meta.id = $id
    if ($null -ne $ts) {
        $meta.capturedAt = $ts
        $meta.width = 540; $meta.height = 960
        $meta.sizeBytes = $mainSize
        $meta.path = $mainName
        $meta.thumb = 'thumb.jpg'
        $meta.mime = if ($ext -eq 'png') { 'image/png' } else { 'image/jpeg' }
    }
    $meta.source = 'manual_hd_capture'
    $json = ($meta.GetEnumerator() | ForEach-Object {
        $v = if ($_.Value -is [string]) { '"{0}"' -f $_.Value } else { '{0}' -f $_.Value }
        '  "{0}": {1}' -f $_.Key, $v
    }) -join ",`n"
    $json = "{`n$json`n}"
    # 无 BOM：JSONObject 读到 BOM 会直接解析失败
    [System.IO.File]::WriteAllText((Join-Path $dir 'metadata.json'), $json, $Utf8NoBom)
    Write-Output ("  {0,-18} {1}" -f $id, $note)
}

# ---- 阶段 2：推送到设备 -----------------------------------------------------
Write-Output ''
Write-Output '== 阶段 2：注入设备 =='
# 先清掉上次运行可能残留的夹具，保证幂等
foreach ($id in @($Fixtures | ForEach-Object { $_[0] }) + @('.staging-fixture')) {
    & $Adb @A shell "run-as $Package rm -rf '$Hd/$id'" | Out-Null
}
& $Adb @A shell "mkdir -p $RemoteTmp" | Out-Null
& $Adb @A shell "run-as $Package mkdir -p $Hd" | Out-Null
# 逐目录 push，语义明确：/data/local/tmp/vhdg/<id>/...
foreach ($id in @($Fixtures | ForEach-Object { $_[0] }) + @('.staging-fixture')) {
    & $Adb @A push (Join-Path $OutDir $id) "$RemoteTmp/$id" | Out-Null
    & $Adb @A shell "run-as $Package cp -r '$RemoteTmp/$id' '$Hd/'" | Out-Null
}
& $Adb @A shell "rm -rf $RemoteTmp" | Out-Null

$after = @(RemoteList)
foreach ($f in $Fixtures) { Check ("注入 {0}" -f $f[0]) ($after -contains $f[0]) 'cp 失败' }
Check '注入 .staging-fixture' ($after -contains '.staging-fixture') 'cp 失败'

# ---- 阶段 3：启动应用 -------------------------------------------------------
if (-not $SkipLaunch) {
    Write-Output ''
    Write-Output '== 阶段 3：启动应用 =='
    & $Adb @A shell "am start -n $Package/.MainActivity" | Out-Null
    Start-Sleep -Seconds 2
    & $Adb @A shell "screencap -p /sdcard/vhdg_screen.png"
    & $Adb @A pull /sdcard/vhdg_screen.png (Join-Path $OutDir 'screen_main.png') | Out-Null
    & $Adb @A shell "rm /sdcard/vhdg_screen.png" | Out-Null
    Write-Output ('  首屏截图已存 {0}\screen_main.png' -f $OutDir)
}

# ---- 阶段 4：人工操作（打印检查清单） ---------------------------------------
Write-Output ''
Write-Output '== 阶段 4：请在手机上操作（脚本等待） =='
Write-Output @'
  1. 打开主界面「截图」相册，应看到：
     - 入口计数比验收前多 9
     - 「今天」组 2 条（fix-a-today、fix-h-broken 占位）
     - 各日期组按天分组，PNG/JPEG 缩略图都能显示
     - 「日期未知」单独成组 1 条（fix-g-unknown）
     - fix-h-broken 显示占位/错误样式而不是崩溃
  2. 点开 fix-e-100d 大图 → 导出，文件名应为 .png；点开 fix-a-today → 导出为 .jpg
  3. 长按进入选择态 → 全选 → 取消，0 张时仍停留选择态；返回键先退选择再退相册
  4. 运行一次「清理」（选一项）：
     - 30 天以前 → 预览应为「将删除 xxxx年xx月xx日 00:00 以前的 3 张截图」，并提示跳过 1 张日期未知
     - 90 天以前 → 预览应为 1 张，跳过 1 张日期未知
     - 全部      → 预览应为 8 张
     或不清理（回车选 none）。
'@
$choice = Read-Host '你运行了哪个清理？[30/90/all/none]（回车=none）'
if ($choice -notin @('30', '90', 'all', 'none')) { $choice = 'none' }
Read-Host '手机上操作完成后按回车开始断言'

# ---- 阶段 5：断言 -----------------------------------------------------------
Write-Output ''
Write-Output ('== 阶段 5：断言（你选择了清理：{0}） ==' -f $choice)

$Gone = @{
    '30'  = @('fix-d-31d', 'fix-e-100d', 'fix-f-90d-exact')
    '90'  = @('fix-e-100d')
    'all' = @('fix-a-today', 'fix-b-29d', 'fix-c-30d-exact', 'fix-d-31d',
              'fix-e-100d', 'fix-f-90d-exact', 'fix-g-unknown', 'fix-h-broken')
    'none' = @()
}
$expectGone = $Gone[$choice]
$allFix = @($Fixtures | ForEach-Object { $_[0] })

$final = @(RemoteList)
foreach ($id in $allFix) {
    $shouldGone = $expectGone -contains $id
    $isGone = $final -notcontains $id
    $verb = if ($shouldGone) { '应删' } else { '应留' }
    $detail = if ($shouldGone) { '仍存在' } else { '被误删' }
    Check ("{0} {1}" -f $verb, $id) ($shouldGone -eq $isGone) $detail
}
Check '.staging-fixture 永不删除' ($final -contains '.staging-fixture') '被清理误删'

# 真实截图一条不能少
$baselineAfter = @($final | Where-Object { $_ -notmatch '^fix-' -and $_ -notmatch '^\.staging-fixture$' })
$missingUser = @($baseline | Where-Object { $baselineAfter -notcontains $_ })
Check ('真实截图 {0} 条未受影响' -f $baseline.Count) ($missingUser.Count -eq 0) ("丢失：{0}" -f ($missingUser -join ', '))

# ---- 阶段 6：清场 -----------------------------------------------------------
Write-Output ''
Write-Output '== 阶段 6：清场 =='
if ($Keep) {
    Write-Output '  -Keep：夹具保留在设备上，手动检查完可重跑本脚本（自动幂等清理）'
} else {
    foreach ($id in $allFix + @('.staging-fixture')) {
        & $Adb @A shell "run-as $Package rm -rf '$Hd/$id'" | Out-Null
    }
    $rest = @(RemoteList | Where-Object { $_ -match '^fix-' -or $_ -match '^\.staging-fixture$' })
    Check '夹具全部清除' ($rest.Count -eq 0) ('残留：' + ($rest -join ', '))
    Remove-Item $OutDir -Recurse -Force -ErrorAction SilentlyContinue
}

# ---- 结果 -------------------------------------------------------------------
Write-Output ''
Write-Output ('================ 通过 {0} / 失败 {1} ================' -f $script:Passed, $script:Failed)
if ($script:Failed -gt 0) {
    Write-Output '失败项：'
    $script:FailLines | ForEach-Object { Write-Output ('  - ' + $_) }
    exit 1
}
Write-Output '截图相册文件层验收全部通过。界面手感（缩放/滚动/反馈）仍以人工为准。'
exit 0
