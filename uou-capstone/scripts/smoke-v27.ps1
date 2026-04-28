param(
    [string]$SpringBaseUrl = "http://localhost:8081",

    [string]$FastApiBaseUrl = "",

    [long]$LectureId = 1,

    [string]$PdfPath = "",
    [string]$TeacherJwt = "",
    [int]$TimeoutSec = 20
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Result {
    param([string]$Name, [string]$Status, [string]$Detail)
    $line = "[{0}] {1} - {2}" -f $Status, $Name, $Detail
    if ($Status -eq "PASS") { Write-Host $line -ForegroundColor Green; return }
    if ($Status -eq "WARN") { Write-Host $line -ForegroundColor Yellow; return }
    Write-Host $line -ForegroundColor Red
}

function Invoke-JsonRequest {
    param(
        [string]$Method,
        [string]$Url,
        [hashtable]$Headers = @{},
        [object]$Body = $null,
        [int]$Timeout = 20
    )

    $jsonBody = $null
    if ($null -ne $Body) { $jsonBody = $Body | ConvertTo-Json -Depth 20 }

    try {
        $resp = Invoke-WebRequest -Method $Method -Uri $Url -Headers $Headers -Body $jsonBody -ContentType "application/json" -TimeoutSec $Timeout
        return [pscustomobject]@{ StatusCode = [int]$resp.StatusCode; Body = $resp.Content }
    }
    catch [System.Net.WebException] {
        $webEx = $_.Exception
        if ($null -ne $webEx.Response) {
            $httpResp = [System.Net.HttpWebResponse]$webEx.Response
            $status = [int]$httpResp.StatusCode
            $reader = New-Object System.IO.StreamReader($httpResp.GetResponseStream())
            $body = $reader.ReadToEnd()
            $reader.Close()
            return [pscustomobject]@{ StatusCode = $status; Body = $body }
        }
        return [pscustomobject]@{ StatusCode = -1; Body = $webEx.Message }
    }
    catch {
        return [pscustomobject]@{ StatusCode = -1; Body = $_.Exception.Message }
    }
}

function Check-Not404Or405 {
    param([string]$Name, [int]$StatusCode, [string]$Body)

    if ($StatusCode -eq 404 -or $StatusCode -eq 405 -or $StatusCode -eq -1) {
        Write-Result -Name $Name -Status "FAIL" -Detail "status=$StatusCode body=$Body"
        return $false
    }
    if ($StatusCode -ge 200 -and $StatusCode -lt 300) {
        Write-Result -Name $Name -Status "PASS" -Detail "status=$StatusCode"
        return $true
    }
    Write-Result -Name $Name -Status "PASS" -Detail "status=$StatusCode (endpoint alive)"
    return $true
}

$ngrokHeader = @{ "ngrok-skip-browser-warning" = "true" }
$springAuthHeader = @{}
if ($TeacherJwt.Trim().Length -gt 0) { $springAuthHeader = @{ "Authorization" = "Bearer $TeacherJwt" } }

Write-Host "=== v2.7 smoke start ==="
Write-Host "Spring:  $SpringBaseUrl"
if ($FastApiBaseUrl.Trim().Length -gt 0) { Write-Host "FastAPI: $FastApiBaseUrl" } else { Write-Host "FastAPI: (skipped)" }
Write-Host "Lecture: $LectureId"
if ($PdfPath.Trim().Length -gt 0) { Write-Host "PdfPath: $PdfPath" }

# B-0 Spring basic (no auth)
$springActuator = Invoke-JsonRequest -Method "GET" -Url "$SpringBaseUrl/actuator/health" -Headers @{} -Timeout $TimeoutSec
if ($springActuator.StatusCode -eq 200) { Write-Result -Name "B-0 /actuator/health" -Status "PASS" -Detail "status=200" }
else { Write-Result -Name "B-0 /actuator/health" -Status "FAIL" -Detail "status=$($springActuator.StatusCode) body=$($springActuator.Body)" }

$swagger = Invoke-JsonRequest -Method "GET" -Url "$SpringBaseUrl/swagger-ui.html" -Headers @{} -Timeout $TimeoutSec
if ($swagger.StatusCode -ge 200 -and $swagger.StatusCode -lt 400) { Write-Result -Name "B-0 /swagger-ui.html" -Status "PASS" -Detail "status=$($swagger.StatusCode)" }
else { Write-Result -Name "B-0 /swagger-ui.html" -Status "WARN" -Detail "status=$($swagger.StatusCode) (springdoc 설정에 따라 경로가 다를 수 있음)" }

# A-1 Health
if ($FastApiBaseUrl.Trim().Length -eq 0) {
    Write-Result -Name "A-1 /health" -Status "WARN" -Detail "skipped (FastApiBaseUrl not provided)"
} else {
    $health = Invoke-JsonRequest -Method "GET" -Url "$FastApiBaseUrl/health" -Headers $ngrokHeader -Timeout $TimeoutSec
    if ($health.StatusCode -eq 200) { Write-Result -Name "A-1 /health" -Status "PASS" -Detail "status=200" }
    else { Write-Result -Name "A-1 /health" -Status "FAIL" -Detail "status=$($health.StatusCode) body=$($health.Body)" }
}

# A-3 v3 session by lecture
if ($FastApiBaseUrl.Trim().Length -eq 0) {
    Write-Result -Name "A-3 /api/v3/session/by-lecture/{id}" -Status "WARN" -Detail "skipped (FastApiBaseUrl not provided)"
    $sessionId = $null
} else {
    $sessionUrl = "$FastApiBaseUrl/api/v3/session/by-lecture/$LectureId"
    if ($PdfPath.Trim().Length -gt 0) {
        $encoded = [System.Uri]::EscapeDataString($PdfPath)
        $sessionUrl = "$sessionUrl?pdf_path=$encoded"
    }
    $sessionRes = Invoke-JsonRequest -Method "GET" -Url $sessionUrl -Headers $ngrokHeader -Timeout $TimeoutSec
    $sessionId = $null
    if ($sessionRes.StatusCode -eq 200) {
        try { $sessionJson = $sessionRes.Body | ConvertFrom-Json; $sessionId = $sessionJson.session_id } catch { $sessionId = $null }
    }
    if ($sessionRes.StatusCode -eq 200 -and $null -ne $sessionId) {
        Write-Result -Name "A-3 /api/v3/session/by-lecture/{id}" -Status "PASS" -Detail "session_id=$sessionId"
    } else {
        Write-Result -Name "A-3 /api/v3/session/by-lecture/{id}" -Status "FAIL" -Detail "status=$($sessionRes.StatusCode) body=$($sessionRes.Body)"
    }
}

# A-4 v3 session event (non-stream)
if ($null -ne $sessionId) {
    $eventBody = @{
        type = "SESSION_ENTERED"
        lecture_id = $LectureId
        payload = @{}
    }
    $eventRes = Invoke-JsonRequest -Method "POST" -Url "$FastApiBaseUrl/api/v3/session/$sessionId/event" -Headers $ngrokHeader -Body $eventBody -Timeout $TimeoutSec
    [void](Check-Not404Or405 -Name "A-4 /api/v3/session/{id}/event" -StatusCode $eventRes.StatusCode -Body $eventRes.Body)
} else {
    Write-Result -Name "A-4 /api/v3/session/{id}/event" -Status "WARN" -Detail "skipped (session_id unavailable)"
}

# A-5 v3 bridge grade/result (length check should not fail)
if ($FastApiBaseUrl.Trim().Length -eq 0) {
    Write-Result -Name "A-5 /api/v3/bridge/grade/result" -Status "WARN" -Detail "skipped (FastApiBaseUrl not provided)"
} else {
    $gradeBody = @{
        exam_type = "Five_Choice"
        problems = @(@{ id = 1; question = "smoke"; options = @("1","2"); answer = "1" })
        user_answers = @(@{ problem_id = 1; user_response = "1" })
        lecture_content = ""
    }
    if ($PdfPath.Trim().Length -gt 0) { $gradeBody.pdf_path = $PdfPath }
    $grade = Invoke-JsonRequest -Method "POST" -Url "$FastApiBaseUrl/api/v3/bridge/grade/result" -Headers $ngrokHeader -Body $gradeBody -Timeout $TimeoutSec
    [void](Check-Not404Or405 -Name "A-5 /api/v3/bridge/grade/result" -StatusCode $grade.StatusCode -Body $grade.Body)
}

# B-1 Spring protected endpoints (optional)
if ($springAuthHeader.Count -eq 0) {
    Write-Result -Name "B-1 Spring /api/learning/sessions" -Status "WARN" -Detail "skipped (TeacherJwt not provided)"
} else {
    $springUrl = "$SpringBaseUrl/api/learning/sessions/$LectureId"
    if ($PdfPath.Trim().Length -gt 0) {
        $encoded2 = [System.Uri]::EscapeDataString($PdfPath)
        $springUrl = "$springUrl?pdfPath=$encoded2"
    }
    $springSession = Invoke-JsonRequest -Method "POST" -Url $springUrl -Headers $springAuthHeader -Timeout $TimeoutSec
    if ($springSession.StatusCode -ge 200 -and $springSession.StatusCode -lt 300) {
        Write-Result -Name "B-1 POST /api/learning/sessions/{lectureId}" -Status "PASS" -Detail "status=$($springSession.StatusCode)"
    } else {
        Write-Result -Name "B-1 POST /api/learning/sessions/{lectureId}" -Status "FAIL" -Detail "status=$($springSession.StatusCode) body=$($springSession.Body)"
    }
}

# B-3a Exam generation (TEACHER)
if ($springAuthHeader.Count -eq 0) {
    Write-Result -Name "B-3a POST /api/exams/generation" -Status "WARN" -Detail "skipped (TeacherJwt not provided)"
} else {
    $examGenBody = @{
        lectureId = $LectureId
        examType = "FLASH_CARD"
        targetCount = 5
        lectureContent = "smoke test lecture content"
    }
    $examGenRes = Invoke-JsonRequest -Method "POST" -Url "$SpringBaseUrl/api/exams/generation" -Headers $springAuthHeader -Body $examGenBody -Timeout $TimeoutSec
    [void](Check-Not404Or405 -Name "B-3a POST /api/exams/generation" -StatusCode $examGenRes.StatusCode -Body $examGenRes.Body)
}

# B-3b Exam submission (STUDENT/TEACHER)
# 스모크 목적상 존재하지 않는 examSessionId를 보내 경로 생존만 확인 (4xx/404는 doc 기준 PASS 처리)
if ($springAuthHeader.Count -eq 0) {
    Write-Result -Name "B-3b POST /api/exams/submission" -Status "WARN" -Detail "skipped (TeacherJwt not provided)"
} else {
    $examSubBody = @{
        examSessionId = 0
        answers = @(
            @{
                questionId = 0
                answerText = $null
                selectedOptionId = "O"
                additionalData = $null
            }
        )
    }
    $examSubRes = Invoke-JsonRequest -Method "POST" -Url "$SpringBaseUrl/api/exams/submission" -Headers $springAuthHeader -Body $examSubBody -Timeout $TimeoutSec
    # submission은 존재하지 않는 examSessionId → 404(BusinessException)일 수 있으므로 405/타임아웃만 FAIL로 구분
    if ($examSubRes.StatusCode -eq 405 -or $examSubRes.StatusCode -eq -1) {
        Write-Result -Name "B-3b POST /api/exams/submission" -Status "FAIL" -Detail "status=$($examSubRes.StatusCode) body=$($examSubRes.Body)"
    } elseif ($examSubRes.StatusCode -ge 200 -and $examSubRes.StatusCode -lt 300) {
        Write-Result -Name "B-3b POST /api/exams/submission" -Status "PASS" -Detail "status=$($examSubRes.StatusCode)"
    } else {
        Write-Result -Name "B-3b POST /api/exams/submission" -Status "PASS" -Detail "status=$($examSubRes.StatusCode) (endpoint alive)"
    }
}

Write-Host "=== v2.7 smoke done ==="

