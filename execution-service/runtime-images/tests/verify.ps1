# =============================================================================
# US-14 & T-14 verification script (PowerShell for Windows)
# File: execution-service/runtime-images/tests/verify.ps1
#
# Purpose:
#   Runs each acceptance-criterion check against all supported runtime images
#   in priority order (DR1): C++, Python, C, Java, JavaScript.
# =============================================================================

[CmdletBinding()]
param()

$ErrorActionPreference = "Continue"

$RepoRoot = (Resolve-Path "$PSScriptRoot/../../..").Path
$RuntimeDir = "$RepoRoot/execution-service/runtime-images"

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

function Check-Skip([string]$Name, [string]$Msg) {
    $script:Results.Add("SKIP  $Name - $Msg")
    Write-Host "SKIP  $Name - $Msg" -ForegroundColor Yellow
}

Write-Host "============================================================"
Write-Host " US-14 runtime-images multi-language verification (DR1)"
Write-Host " Priority order: C++ -> Python -> C -> Java -> JavaScript"
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

function Verify-Language([string]$Lang, [string]$Image, [bool]$IsCompiled) {
    $sampleDir = "$RuntimeDir/$Lang/samples"

    Write-Host "------------------------------------------------------------" -ForegroundColor Cyan
    Write-Host " Verifying language: $Lang ($Image)" -ForegroundColor Cyan
    Write-Host "------------------------------------------------------------" -ForegroundColor Cyan

    # 1. Image exists
    docker image inspect $Image *>$null
    if ($LASTEXITCODE -ne 0) {
        Check-Fail "[$Lang] Image '$Image' exists" "Image not found. Build with: docker build -t $Image execution-service/runtime-images/$Lang"
        return
    }
    Check-Pass "[$Lang] Image '$Image' exists"

    # 2. Hello-world (no stdin)
    $out = switch ($Lang) {
        "cpp"        { docker run --rm --network none -v "${sampleDir}:/sandbox/code:ro" $Image sh -c "g++ -O2 /sandbox/code/hello_world.cpp -o /tmp/hw && /tmp/hw" 2>$null }
        "python"     { docker run --rm --network none -v "${sampleDir}:/sandbox/code:ro" $Image python /sandbox/code/hello_world.py 2>$null }
        "c"          { docker run --rm --network none -v "${sampleDir}:/sandbox/code:ro" $Image sh -c "gcc -O2 /sandbox/code/hello_world.c -o /tmp/hw && /tmp/hw" 2>$null }
        "java"       { docker run --rm --network none -v "${sampleDir}:/sandbox/code:ro" $Image sh -c "javac -d /tmp /sandbox/code/Main.java && java -cp /tmp Main" 2>$null }
        "javascript" { docker run --rm --network none -v "${sampleDir}:/sandbox/code:ro" $Image node /sandbox/code/hello_world.js 2>$null }
    }
    $exitCode = $LASTEXITCODE
    if ($out -eq "Hello, Sandbox!" -and $exitCode -eq 0) {
        Check-Pass "[$Lang] Hello-world (no stdin) - stdout='Hello, Sandbox!' exit=0"
    } else {
        Check-Fail "[$Lang] Hello-world (no stdin)" "Expected 'Hello, Sandbox!' exit=0, got '$out' exit=$exitCode"
    }

    # 3. Hello-world (with stdin, ignored)
    $out = switch ($Lang) {
        "cpp"        { "ignored input" | docker run --rm -i --network none -v "${sampleDir}:/sandbox/code:ro" $Image sh -c "g++ -O2 /sandbox/code/hello_world.cpp -o /tmp/hw && /tmp/hw" 2>$null }
        "python"     { "ignored input" | docker run --rm -i --network none -v "${sampleDir}:/sandbox/code:ro" $Image python /sandbox/code/hello_world.py 2>$null }
        "c"          { "ignored input" | docker run --rm -i --network none -v "${sampleDir}:/sandbox/code:ro" $Image sh -c "gcc -O2 /sandbox/code/hello_world.c -o /tmp/hw && /tmp/hw" 2>$null }
        "java"       { "ignored input" | docker run --rm -i --network none -v "${sampleDir}:/sandbox/code:ro" $Image sh -c "javac -d /tmp /sandbox/code/Main.java && java -cp /tmp Main" 2>$null }
        "javascript" { "ignored input" | docker run --rm -i --network none -v "${sampleDir}:/sandbox/code:ro" $Image node /sandbox/code/hello_world.js 2>$null }
    }
    $exitCode = $LASTEXITCODE
    if ($out -eq "Hello, Sandbox!" -and $exitCode -eq 0) {
        Check-Pass "[$Lang] Hello-world (stdin ignored) - stdout='Hello, Sandbox!' exit=0"
    } else {
        Check-Fail "[$Lang] Hello-world (stdin ignored)" "Expected 'Hello, Sandbox!' exit=0, got '$out' exit=$exitCode"
    }

    # 4. stdin pipe (uppercase echo)
    $out = switch ($Lang) {
        "cpp"        { "hello sandbox" | docker run --rm -i --network none -v "${sampleDir}:/sandbox/code:ro" $Image sh -c "g++ -O2 /sandbox/code/stdin_echo.cpp -o /tmp/echo && /tmp/echo" 2>$null }
        "python"     { "hello sandbox" | docker run --rm -i --network none -v "${sampleDir}:/sandbox/code:ro" $Image python /sandbox/code/stdin_echo.py 2>$null }
        "c"          { "hello sandbox" | docker run --rm -i --network none -v "${sampleDir}:/sandbox/code:ro" $Image sh -c "gcc -O2 /sandbox/code/stdin_echo.c -o /tmp/echo && /tmp/echo" 2>$null }
        "java"       { "hello sandbox" | docker run --rm -i --network none -v "${sampleDir}:/sandbox/code:ro" $Image sh -c "javac -d /tmp /sandbox/code/StdinEcho.java && java -cp /tmp StdinEcho" 2>$null }
        "javascript" { "hello sandbox" | docker run --rm -i --network none -v "${sampleDir}:/sandbox/code:ro" $Image node /sandbox/code/stdin_echo.js 2>$null }
    }
    $exitCode = $LASTEXITCODE
    if ($out -eq "HELLO SANDBOX" -and $exitCode -eq 0) {
        Check-Pass "[$Lang] stdin pipe - stdout='HELLO SANDBOX' exit=0"
    } else {
        Check-Fail "[$Lang] stdin pipe" "Expected 'HELLO SANDBOX' exit=0, got '$out' exit=$exitCode"
    }

    # 5. stdout / stderr independence
    $tmpOut = [System.IO.Path]::GetTempFileName()
    $tmpErr = [System.IO.Path]::GetTempFileName()
    switch ($Lang) {
        "cpp"        { & cmd.exe /c "docker run --rm --network none -v `"${sampleDir}:/sandbox/code:ro`" $Image sh -c `"g++ -O2 /sandbox/code/stderr_split.cpp -o /tmp/split && /tmp/split`" 1>`"${tmpOut}`" 2>`"${tmpErr}`"" }
        "python"     { & cmd.exe /c "docker run --rm --network none -v `"${sampleDir}:/sandbox/code:ro`" $Image python /sandbox/code/stderr_split.py 1>`"${tmpOut}`" 2>`"${tmpErr}`"" }
        "c"          { & cmd.exe /c "docker run --rm --network none -v `"${sampleDir}:/sandbox/code:ro`" $Image sh -c `"gcc -O2 /sandbox/code/stderr_split.c -o /tmp/split && /tmp/split`" 1>`"${tmpOut}`" 2>`"${tmpErr}`"" }
        "java"       { & cmd.exe /c "docker run --rm --network none -v `"${sampleDir}:/sandbox/code:ro`" $Image sh -c `"javac -d /tmp /sandbox/code/StderrSplit.java && java -cp /tmp StderrSplit`" 1>`"${tmpOut}`" 2>`"${tmpErr}`"" }
        "javascript" { & cmd.exe /c "docker run --rm --network none -v `"${sampleDir}:/sandbox/code:ro`" $Image node /sandbox/code/stderr_split.js 1>`"${tmpOut}`" 2>`"${tmpErr}`"" }
    }
    $exitCode = $LASTEXITCODE
    $stdout = if (Test-Path $tmpOut) { (Get-Content $tmpOut -Raw).Trim() } else { "" }
    $stderr = if (Test-Path $tmpErr) { (Get-Content $tmpErr -Raw).Trim() } else { "" }
    Remove-Item -Force $tmpOut, $tmpErr -ErrorAction SilentlyContinue

    if ($stdout -eq "STDOUT line" -and $stderr -eq "STDERR line" -and $exitCode -eq 0) {
        Check-Pass "[$Lang] stdout/stderr independence - each stream captured separately"
    } else {
        Check-Fail "[$Lang] stdout/stderr independence" "Expected stdout='STDOUT line' stderr='STDERR line' exit=0, got stdout='$stdout' stderr='$stderr' exit=$exitCode"
    }

    # 6. Nonzero exit preserved
    switch ($Lang) {
        "cpp"        { docker run --rm --network none -v "${sampleDir}:/sandbox/code:ro" $Image sh -c "g++ -O2 /sandbox/code/exit_nonzero.cpp -o /tmp/nz && /tmp/nz" *>$null }
        "python"     { docker run --rm --network none -v "${sampleDir}:/sandbox/code:ro" $Image python /sandbox/code/exit_nonzero.py *>$null }
        "c"          { docker run --rm --network none -v "${sampleDir}:/sandbox/code:ro" $Image sh -c "gcc -O2 /sandbox/code/exit_nonzero.c -o /tmp/nz && /tmp/nz" *>$null }
        "java"       { docker run --rm --network none -v "${sampleDir}:/sandbox/code:ro" $Image sh -c "javac -d /tmp /sandbox/code/ExitNonzero.java && java -cp /tmp ExitNonzero" *>$null }
        "javascript" { docker run --rm --network none -v "${sampleDir}:/sandbox/code:ro" $Image node /sandbox/code/exit_nonzero.js *>$null }
    }
    $exitCode = $LASTEXITCODE
    if ($exitCode -eq 42) {
        Check-Pass "[$Lang] Nonzero exit code preserved (exit=42)"
    } else {
        Check-Fail "[$Lang] Nonzero exit code" "Expected exit=42, got exit=$exitCode"
    }

    # 7. Non-root user (NFR3.2)
    $uid = docker run --rm --network none $Image id -u 2>$null
    if ($uid -eq "1001") {
        Check-Pass "[$Lang] Non-root user UID 1001 (NFR3.2)"
    } else {
        Check-Fail "[$Lang] Non-root user (NFR3.2)" "Expected UID 1001, got UID=$uid"
    }

    # 8. Network isolation (NFR3.1)
    $netOut = switch ($Lang) {
        "cpp"        { docker run --rm --network none -v "${sampleDir}:/sandbox/code:ro" $Image sh -c "g++ -O2 /sandbox/code/network_probe.cpp -o /tmp/net && /tmp/net" 2>$null }
        "python"     { docker run --rm --network none -v "${sampleDir}:/sandbox/code:ro" $Image python /sandbox/code/network_probe.py 2>$null }
        "c"          { docker run --rm --network none -v "${sampleDir}:/sandbox/code:ro" $Image sh -c "gcc -O2 /sandbox/code/network_probe.c -o /tmp/net && /tmp/net" 2>$null }
        "java"       { docker run --rm --network none -v "${sampleDir}:/sandbox/code:ro" $Image sh -c "javac -d /tmp /sandbox/code/NetworkProbe.java && java -cp /tmp NetworkProbe" 2>$null }
        "javascript" { docker run --rm --network none -v "${sampleDir}:/sandbox/code:ro" $Image node /sandbox/code/network_probe.js 2>$null }
    }
    $netExit = $LASTEXITCODE
    if ($netExit -eq 1 -and ($netOut -join " ") -match "NETWORK BLOCKED") {
        Check-Pass "[$Lang] Network isolation - connection refused as expected (NFR3.1)"
    } else {
        Check-Fail "[$Lang] Network isolation (NFR3.1)" "Expected exit=1 with NETWORK BLOCKED, got exit=$netExit output='$netOut'"
    }

    # 9. Code not baked in (FR5.2)
    $baked = docker run --rm --network none $Image sh -c "test -d /sandbox/code && ls /sandbox/code | wc -l || echo 0" 2>$null
    if ([int]($baked.Trim()) -eq 0) {
        Check-Pass "[$Lang] Code not baked in (FR5.2)"
    } else {
        Check-Fail "[$Lang] Code not baked in (FR5.2)" "Files found in bare image /sandbox/code"
    }

    # 10. Compiled languages: Compile error returns non-zero with compiler output in stderr (NFR4.5)
    if ($IsCompiled) {
        $tmpErr = [System.IO.Path]::GetTempFileName()
        switch ($Lang) {
            "cpp"  { & cmd.exe /c "docker run --rm --network none -v `"${sampleDir}:/sandbox/code:ro`" $Image g++ /sandbox/code/compile_error.cpp -o /tmp/a.out 1>nul 2>`"${tmpErr}`"" }
            "c"    { & cmd.exe /c "docker run --rm --network none -v `"${sampleDir}:/sandbox/code:ro`" $Image gcc /sandbox/code/compile_error.c -o /tmp/a.out 1>nul 2>`"${tmpErr}`"" }
            "java" { & cmd.exe /c "docker run --rm --network none -v `"${sampleDir}:/sandbox/code:ro`" $Image javac -d /tmp /sandbox/code/CompileError.java 1>nul 2>`"${tmpErr}`"" }
        }
        $compExit = $LASTEXITCODE
        $compErr = if (Test-Path $tmpErr) { (Get-Content $tmpErr -Raw).Trim() } else { "" }
        Remove-Item -Force $tmpErr -ErrorAction SilentlyContinue

        if ($compExit -ne 0 -and $compErr.Length -gt 0) {
            Check-Pass "[$Lang] Compile error verification - exit=$compExit with compiler stderr (NFR4.5)"
        } else {
            Check-Fail "[$Lang] Compile error verification" "Expected non-zero exit and compiler stderr, got exit=$compExit"
        }
    }

    # 11. C and C++ sanitizer toolchain support (US-73 ready)
    if ($Lang -eq "cpp" -or $Lang -eq "c") {
        $sanOut = switch ($Lang) {
            "cpp" { docker run --rm --network none -v "${sampleDir}:/sandbox/code:ro" $Image sh -c "g++ -fsanitize=address,undefined -no-pie -g -O1 /sandbox/code/sanitizer_sample.cpp -o /tmp/san && /tmp/san" 2>$null }
            "c"   { docker run --rm --network none -v "${sampleDir}:/sandbox/code:ro" $Image sh -c "gcc -fsanitize=address,undefined -no-pie -g -O1 /sandbox/code/sanitizer_sample.c -o /tmp/san && /tmp/san" 2>$null }
        }
        $sanExit = $LASTEXITCODE
        if ($sanOut -eq "SANITIZER_SUPPORT_OK" -and $sanExit -eq 0) {
            Check-Pass "[$Lang] Sanitizer toolchain ready (AddressSanitizer + UndefinedBehaviorSanitizer) (US-73)"
        } else {
            Check-Fail "[$Lang] Sanitizer toolchain" "Failed to build or run sanitizer sample; exit=$sanExit out='$sanOut'"
        }
    }

    # 12. Image size
    $rawBytes = docker image inspect $Image --format "{{.Size}}" 2>$null
    if ($rawBytes) {
        $mb = [Math]::Round([double]$rawBytes / 1MB, 1)
        Check-Pass "[$Lang] Image size: $mb MB - record this in README (AC8)"
    } else {
        Check-Skip "[$Lang] Image size" "Could not determine image size"
    }
    Write-Host ""
}

# Run in strict priority order (DR1): C++ -> Python -> C -> Java -> JavaScript
Verify-Language "cpp"        "collab-sandbox-cpp:0.1.0"        $true
Verify-Language "python"     "collab-sandbox-python:0.2.0"     $false
Verify-Language "c"          "collab-sandbox-c:0.1.0"          $true
Verify-Language "java"       "collab-sandbox-java:0.1.0"       $true
Verify-Language "javascript" "collab-sandbox-javascript:0.1.0" $false

Write-Host "============================================================"
Write-Host " Results: $Pass passed, $Fail failed"
Write-Host "============================================================"
foreach ($r in $Results) {
    if ($r -match "^PASS") {
        Write-Host "  $r" -ForegroundColor Green
    } elseif ($r -match "^SKIP") {
        Write-Host "  $r" -ForegroundColor Yellow
    } else {
        Write-Host "  $r" -ForegroundColor Red
    }
}
Write-Host ""

if ($Fail -eq 0) {
    Write-Host "All checks passed for all five languages." -ForegroundColor Green
    exit 0
} else {
    Write-Host "$Fail check(s) failed." -ForegroundColor Red
    exit 1
}
