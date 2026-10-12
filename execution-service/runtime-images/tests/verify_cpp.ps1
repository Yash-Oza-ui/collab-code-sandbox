# =============================================================================
# T-14 C++ verification script (PowerShell for Windows)
# File: execution-service/runtime-images/tests/verify_cpp.ps1
#
# Purpose:
#   Runs each acceptance-criterion check against the C++ runtime image
#   directly in PowerShell without requiring WSL or Git Bash.
# =============================================================================

[CmdletBinding()]
param(
    [string]$Image = "collab-sandbox-cpp:0.1.0"
)

$ErrorActionPreference = "Continue"

$RepoRoot = (Resolve-Path "$PSScriptRoot/../../..").Path
$SampleDir = "$RepoRoot/execution-service/runtime-images/cpp/samples"

$Pass = 0
$Fail = 0
$Results = [System.Collections.Generic.List[string]]::new()

function Check-Pass([string]$Name) {
    $script:Pass++
    $script:Results.Add("PASS  $Name")
    Write-Host "PASS  $Name" -ForegroundColor Green
}

function Check-Fail([string]$Name, [string]$Msg) {
    $script:Fail++
    $script:Results.Add("FAIL  $Name - $Msg")
    Write-Host "FAIL  $Name - $Msg" -ForegroundColor Red
}

Write-Host "============================================================"
Write-Host " T-14 C++ runtime-image verification - image: $Image"
Write-Host "============================================================"
Write-Host ""

# [0] Docker daemon check
Write-Host "[0] Checking Docker daemon..."
docker info *>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "FATAL: Docker daemon is not running. Cannot proceed." -ForegroundColor Red
    exit 1
}
Write-Host "    Docker daemon: OK"
Write-Host ""

# [1] Image exists
Write-Host "[1] Image exists (build check)..."
docker image inspect $Image *>$null
if ($LASTEXITCODE -eq 0) {
    Check-Pass "Image '$Image' exists (AC2)"
} else {
    Check-Fail "Image '$Image' exists (AC2)" "Image not found. Build with: docker build -t $Image execution-service/runtime-images/cpp"
}
Write-Host ""

# [2] Hello-world (no stdin)
Write-Host "[2] Hello-world (no stdin)..."
$cmd = "g++ -O2 -std=c++20 /sandbox/code/hello_world.cpp -o /sandbox/run/hello && /sandbox/run/hello"
$out = docker run --rm --network none --tmpfs /sandbox/run:rw,noexec=false,size=64m -v "${SampleDir}:/sandbox/code:ro" $Image sh -c $cmd 2>$null
$exitCode = $LASTEXITCODE
if ($out -eq "Hello, Sandbox!" -and $exitCode -eq 0) {
    Check-Pass "Hello-world (no stdin) - stdout='Hello, Sandbox!' exit=0 (AC3, AC4a)"
} else {
    Check-Fail "Hello-world (no stdin) (AC3, AC4a)" "Expected 'Hello, Sandbox!' exit=0, got '$out' exit=$exitCode"
}
Write-Host ""

# [3] Hello-world (with stdin, ignored)
Write-Host "[3] Hello-world (with stdin, ignored)..."
$cmd = "g++ -O2 -std=c++20 /sandbox/code/hello_world.cpp -o /sandbox/run/hello && /sandbox/run/hello"
$out = "ignored input" | docker run --rm -i --network none --tmpfs /sandbox/run:rw,noexec=false,size=64m -v "${SampleDir}:/sandbox/code:ro" $Image sh -c $cmd 2>$null
$exitCode = $LASTEXITCODE
if ($out -eq "Hello, Sandbox!" -and $exitCode -eq 0) {
    Check-Pass "Hello-world (stdin ignored) - stdout='Hello, Sandbox!' exit=0 (AC4b)"
} else {
    Check-Fail "Hello-world (stdin ignored) (AC4b)" "Expected 'Hello, Sandbox!' exit=0, got '$out' exit=$exitCode"
}
Write-Host ""

# [4] stdin pipe (uppercase echo)
Write-Host "[4] stdin pipe (uppercase echo)..."
$cmd = "g++ -O2 -std=c++20 /sandbox/code/stdin_echo.cpp -o /sandbox/run/stdin_echo && /sandbox/run/stdin_echo"
$out = "hello sandbox" | docker run --rm -i --network none --tmpfs /sandbox/run:rw,noexec=false,size=64m -v "${SampleDir}:/sandbox/code:ro" $Image sh -c $cmd 2>$null
$exitCode = $LASTEXITCODE
if ($out -eq "HELLO SANDBOX" -and $exitCode -eq 0) {
    Check-Pass "stdin pipe - stdout='HELLO SANDBOX' exit=0 (AC5)"
} else {
    Check-Fail "stdin pipe (AC5)" "Expected 'HELLO SANDBOX' exit=0, got '$out' exit=$exitCode"
}
Write-Host ""

# [5] stdout/stderr independence
Write-Host "[5] stdout/stderr independence..."
$tmpOut = [System.IO.Path]::GetTempFileName()
$tmpErr = [System.IO.Path]::GetTempFileName()
$cmd = "g++ -O2 -std=c++20 /sandbox/code/stderr_split.cpp -o /sandbox/run/stderr_split && /sandbox/run/stderr_split"
& cmd.exe /c "docker run --rm --network none --tmpfs /sandbox/run:rw,noexec=false,size=64m -v `"${SampleDir}:/sandbox/code:ro`" $Image sh -c `"$cmd`" 1>`"${tmpOut}`" 2>`"${tmpErr}`""
$exitCode = $LASTEXITCODE
$stdout = if (Test-Path $tmpOut) { (Get-Content $tmpOut -Raw).Trim() } else { "" }
$stderr = if (Test-Path $tmpErr) { (Get-Content $tmpErr -Raw).Trim() } else { "" }
Remove-Item -Force $tmpOut, $tmpErr -ErrorAction SilentlyContinue

if ($stdout -eq "STDOUT line" -and $stderr -eq "STDERR line" -and $exitCode -eq 0) {
    Check-Pass "stdout/stderr independence - each stream captured separately (AC5)"
} else {
    Check-Fail "stdout/stderr independence (AC5)" "Expected stdout='STDOUT line' stderr='STDERR line' exit=0, got stdout='$stdout' stderr='$stderr' exit=$exitCode"
}
Write-Host ""

# [6] Nonzero exit code preserved
Write-Host "[6] Nonzero exit code preserved..."
$cmd = "g++ -O2 -std=c++20 /sandbox/code/exit_nonzero.cpp -o /sandbox/run/exit_nonzero && /sandbox/run/exit_nonzero"
docker run --rm --network none --tmpfs /sandbox/run:rw,noexec=false,size=64m -v "${SampleDir}:/sandbox/code:ro" $Image sh -c $cmd *>$null
$exitCode = $LASTEXITCODE
if ($exitCode -eq 42) {
    Check-Pass "Nonzero exit code - exit code 42 preserved (AC5)"
} else {
    Check-Fail "Nonzero exit code (AC5)" "Expected exit=42, got exit=$exitCode"
}
Write-Host ""

# [7] Non-root user
Write-Host "[7] Non-root user..."
$uid = docker run --rm --network none $Image id -u 2>$null
$exitCode = $LASTEXITCODE
$user = docker run --rm --network none $Image id -un 2>$null
if ($uid -ne "0" -and $exitCode -eq 0) {
    Check-Pass "Non-root user - UID=$uid (user '$user'), not root (NFR3.2)"
} else {
    Check-Fail "Non-root user (NFR3.2)" "Expected non-zero UID, got UID=$uid"
}
Write-Host ""

# [8] Network isolation
Write-Host "[8] Network isolation..."
$cmd = "g++ -O2 -std=c++20 /sandbox/code/network_probe.cpp -o /sandbox/run/network_probe && /sandbox/run/network_probe"
$netOut = docker run --rm --network none --tmpfs /sandbox/run:rw,noexec=false,size=64m -v "${SampleDir}:/sandbox/code:ro" $Image sh -c $cmd 2>$null
$netExit = $LASTEXITCODE
if ($netExit -eq 1 -and ($netOut -join " ") -match "NETWORK BLOCKED") {
    Check-Pass "Network isolation - connection refused as expected (NFR3.1)"
} else {
    Check-Fail "Network isolation (NFR3.1)" "Expected exit=1 with NETWORK BLOCKED, got exit=$netExit output='$netOut'"
}
Write-Host ""

# [9] Source code not baked into image
Write-Host "[9] Source code not baked into image..."
$baked = docker run --rm --network none $Image sh -c "test -f /sandbox/code/hello_world.cpp && echo FOUND || echo ABSENT" 2>$null
if ($baked -eq "ABSENT") {
    Check-Pass "Code not baked in - /sandbox/code/hello_world.cpp absent in bare image (FR5.2)"
} else {
    Check-Fail "Code not baked in (FR5.2)" "hello_world.cpp was found in bare image"
}
Write-Host ""

# [10] Container cleanup
Write-Host "[10] Container cleanup..."
$uniqueName = "t14-cpp-cleanup-$PID"
$cmd = "g++ -O2 -std=c++20 /sandbox/code/hello_world.cpp -o /sandbox/run/hello && /sandbox/run/hello"
docker run --rm --network none --name $uniqueName --tmpfs /sandbox/run:rw,noexec=false,size=64m -v "${SampleDir}:/sandbox/code:ro" $Image sh -c $cmd *>$null
$running = docker ps -a --filter "name=$uniqueName" --format "{{.Names}}"
if (-not $running) {
    Check-Pass "Container cleanup - container removed after exit (FR5.2)"
} else {
    Check-Fail "Container cleanup (FR5.2)" "Container '$uniqueName' still exists after run"
    docker rm -f $uniqueName *>$null
}
Write-Host ""

# [11] Image size
Write-Host "[11] Image size (informational)..."
$rawBytes = docker image inspect $Image --format "{{.Size}}" 2>$null
if ($rawBytes) {
    $mb = [Math]::Round([double]$rawBytes / 1MB, 1)
    Check-Pass "Image size: $mb MB - record this in the README (AC8)"
} else {
    Check-Fail "Image size (AC8)" "Could not determine image size"
}
Write-Host ""

Write-Host "============================================================"
Write-Host " Results: $Pass passed, $Fail failed"
Write-Host "============================================================"
foreach ($r in $Results) {
    if ($r -match "^PASS") {
        Write-Host "  $r" -ForegroundColor Green
    } else {
        Write-Host "  $r" -ForegroundColor Red
    }
}
Write-Host ""

if ($Fail -eq 0) {
    Write-Host "All C++ checks passed." -ForegroundColor Green
    exit 0
} else {
    Write-Host "$Fail check(s) failed." -ForegroundColor Red
    exit 1
}
