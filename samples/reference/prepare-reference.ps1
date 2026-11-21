<#
  prepare-reference.ps1 —— 把一段录音整理成 GPT-SoVITS / cosyvoice 可用的参考音频
  做的事：转 32kHz 单声道 → 响度归一 → 截取前 N 秒 → 去首尾静音
  不做的事：人声分离（BGM 必须先用 UVR / demucs 处理，见同目录 README）

  用法：
    .\prepare-reference.ps1 -In my-record.mp4 -Out ref-zh.wav -Seconds 6
#>
param(
    [Parameter(Mandatory = $true)][string]$In,
    [Parameter(Mandatory = $true)][string]$Out,
    [int]$Seconds = 6,
    [int]$SampleRate = 32000
)

if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
    Write-Error '找不到 ffmpeg，请先安装并加入 PATH'
    exit 1
}
if (-not (Test-Path -LiteralPath $In)) {
    Write-Error "输入文件不存在: $In"
    exit 1
}

$filter = "atrim=0:$Seconds,asetpts=N/SR/TB," +
          "loudnorm=I=-16:TP=-1.5:LRA=11," +
          "silenceremove=start_periods=1:start_silence=0.1:start_threshold=-45dB:" +
          "stop_periods=1:stop_silence=0.2:stop_threshold=-45dB"

Write-Host "处理: $In → $Out (${Seconds}s, ${SampleRate}Hz, 单声道)" -ForegroundColor Cyan
& ffmpeg -y -i $In -vn -ac 1 -ar $SampleRate -af $filter $Out 2>&1 |
    Select-String -Pattern 'error|Error|Invalid' | ForEach-Object { Write-Host $_.Line -ForegroundColor Red }

if (Test-Path -LiteralPath $Out) {
    $kb = [math]::Round((Get-Item -LiteralPath $Out).Length / 1KB, 1)
    Write-Host "✅ 完成: $Out ($kb KB)" -ForegroundColor Green
    Write-Host '   下一步：自己听一遍句首/句中/句尾，确认没有音乐残留' -ForegroundColor Yellow
} else {
    Write-Error '处理失败，输出文件未生成'
}