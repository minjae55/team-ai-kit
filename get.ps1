# team-ai-kit 한 줄 설치 (Windows PowerShell)
#   irm https://raw.githubusercontent.com/minjae55/team-ai-kit/main/get.ps1 | iex
# 설치 스크립트가 bash 용이라 Git for Windows 의 Git Bash 를 찾아 get.sh 를 대신 실행한다.
# AI 가 대신 실행할 때(질문 없이): $env:KIT_NO_UI = "1"; irm .../get.ps1 | iex
$ErrorActionPreference = 'Stop'
function Fail($msg) { Write-Host "[오류] $msg" -ForegroundColor Red; exit 1 }

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Fail "git이 필요합니다. https://git-scm.com/download/win 에서 Git for Windows를 설치한 뒤 다시 실행하세요"
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Fail "Java 8 이상이 필요합니다. https://adoptium.net 에서 설치한 뒤 다시 실행하세요"
}

# Git Bash 찾기 (WSL 의 bash.exe 와 헷갈리지 않게 Git 설치 폴더의 bash.exe 만)
$gitRoot = Split-Path (Split-Path (Get-Command git).Source -Parent) -Parent
$candidates = @(
    (Join-Path $gitRoot 'bin\bash.exe'),
    "$env:ProgramFiles\Git\bin\bash.exe",
    "${env:ProgramFiles(x86)}\Git\bin\bash.exe",
    "$env:LOCALAPPDATA\Programs\Git\bin\bash.exe"
)
$bash = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $bash) { Fail "Git Bash(bash.exe)를 찾지 못했습니다. Git for Windows를 설치한 뒤 다시 실행하세요" }

Write-Host "Git Bash로 설치를 진행합니다: $bash"
$noUi = if ($env:KIT_NO_UI) { "KIT_NO_UI=$($env:KIT_NO_UI) " } else { "" }
& $bash -lc "curl -fsSL https://raw.githubusercontent.com/minjae55/team-ai-kit/main/get.sh | ${noUi}bash"
$code = $LASTEXITCODE
if ($code -eq 0) {
    $kitBin = Join-Path $env:USERPROFILE '.kit\bin'
    Write-Host ""
    Write-Host "설치 끝. PowerShell·CMD 에서도 kit 명령을 쓰려면 사용자 PATH 에 다음 폴더를 추가하세요: $kitBin"
    Write-Host "  (AI 가 설치 중이면, 사용자에게 먼저 묻고 추가한다)"
}
exit $code
