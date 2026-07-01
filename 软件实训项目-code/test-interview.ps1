# test-interview.ps1
# AI Interview Functional Test Script (v2.1)
# Usage: .\test-interview.ps1

$ErrorActionPreference = "Stop"
$baseUrl = "http://localhost:8080"

# Test user credentials
$username = "test_student"
$password = "123456"

# Create web session to store cookies
$webSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   AI Interview Functional Test" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# ========================================
# Pre-check: Backend health
# ========================================
Write-Host "`n[Pre-check] Backend service..." -ForegroundColor Yellow
try {
    $healthResp = Invoke-RestMethod -Uri "$baseUrl/actuator/health" -Method Get -TimeoutSec 5 -WebSession $webSession
    Write-Host "[PASS] Backend UP: status=$($healthResp.status)" -ForegroundColor Green
} catch {
    Write-Host "[FAIL] Backend not reachable at $baseUrl" -ForegroundColor Red
    exit 1
}

# ========================================
# Pre-check: Login
# ========================================
Write-Host "`n[Pre-check] Login..." -ForegroundColor Yellow
try {
    $loginBody = @{ username = $username; password = $password } | ConvertTo-Json
    $loginResp = Invoke-RestMethod -Uri "$baseUrl/api/auth/login" -Method Post `
        -Body $loginBody -ContentType "application/json" -WebSession $webSession

    if ($loginResp.code -eq 200) {
        Write-Host "[PASS] Login OK: $($loginResp.data.username)" -ForegroundColor Green
    } else {
        Write-Host "[FAIL] Login failed: $($loginResp.message)" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "[FAIL] Login error: $_" -ForegroundColor Red
    exit 1
}

# ========================================
# TC-001: Start Interview
# ========================================
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "[TC-001] Start Interview" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

$startBody = @{ targetJobId = 1; interviewType = "TECHNICAL" } | ConvertTo-Json

try {
    $startResp = Invoke-RestMethod -Uri "$baseUrl/api/student/interview/start" -Method Post `
        -Body $startBody -ContentType "application/json" -WebSession $webSession

    if ($startResp.code -eq 200) {
        $sessionId = $startResp.data.sessionId
        $question = $startResp.data.question
        $isFollowUp = $startResp.data.isFollowUp
        $totalQuestions = $startResp.data.totalQuestions
        $questionType = $startResp.data.questionType

        Write-Host "[PASS] Interview started" -ForegroundColor Green
        Write-Host "  Session ID: $sessionId"
        Write-Host "  Q1: $question"
        Write-Host "  Type: $questionType"
        Write-Host "  Total: $totalQuestions questions"
        Write-Host "  isFollowUp: $isFollowUp"

        # Check if AI-generated or local fallback
        $localQ1 = "Please provide a brief self-introduction"
        $localQ2 = "Please describe your most challenging project"
        if ($question -match "self-introduction|project|SOLID|troubleshoot|career plan" -or 
            $question.Length -lt 15) {
            # These patterns might indicate local questions
        }
        # Better check: AI-generated questions are usually longer and more specific
        if ($question.Length -gt 60) {
            Write-Host "  [AI] Likely AI-generated question (long+detailed)" -ForegroundColor Green
        } else {
            Write-Host "  [WARN] Possibly local fallback question" -ForegroundColor Yellow
        }
    } else {
        Write-Host "[FAIL] code=$($startResp.code): $($startResp.message)" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "[FAIL] $_" -ForegroundColor Red
    exit 1
}

# ========================================
# TC-003: Submit High-Quality Answer (Test Follow-up)
# ========================================
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "[TC-003] Follow-up Test (High-Quality Answer)" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

$highQualityAnswer = "I used Spring Boot in my last project to build a user authentication module with JWT token mechanism for stateless login. I implemented token generation, refresh, and blacklist features. For performance optimization, I used Redis caching for user info, reducing API response time from 200ms to 50ms. I also used Redis distributed locks to solve concurrency issues and implemented idempotency guarantees through custom annotations."

$answerBody = @{ answer = $highQualityAnswer } | ConvertTo-Json

Write-Host "Submitting answer (waiting for AI, max 90s)..." -ForegroundColor Gray
$submitStartTime = Get-Date

try {
    $submitResp = Invoke-RestMethod -Uri "$baseUrl/api/student/interview/$sessionId/answer" -Method Post `
        -Body $answerBody -ContentType "application/json" -WebSession $webSession -TimeoutSec 90

    $elapsed = [math]::Round(((Get-Date) - $submitStartTime).TotalSeconds, 1)
    Write-Host "  Response time: ${elapsed}s"

    if ($submitResp.code -eq 200) {
        $nextQuestion = $submitResp.data.question
        $isFollowUp = $submitResp.data.isFollowUp
        $feedback = $submitResp.data.feedback

        Write-Host "[PASS] Answer submitted" -ForegroundColor Green
        Write-Host "  Next Q: $nextQuestion"
        Write-Host "  isFollowUp: $isFollowUp"
        if ($feedback) {
            Write-Host "  Feedback: $feedback" -ForegroundColor DarkYellow
        }

        if ($isFollowUp -eq $true) {
            Write-Host "  [SUCCESS] Follow-up triggered!" -ForegroundColor Green
        } else {
            Write-Host "  [WARN] No follow-up triggered" -ForegroundColor Yellow
            Write-Host "  Possible causes:" -ForegroundColor Gray
            Write-Host "    1. DeepSeek API key not configured" -ForegroundColor Gray
            Write-Host "    2. AI decided no follow-up needed" -ForegroundColor Gray
            Write-Host "    3. isAnswerSubstantial() returned false" -ForegroundColor Gray
            Write-Host "    4. Check backend logs for AI call status" -ForegroundColor Gray
        }
    } else {
        Write-Host "[FAIL] code=$($submitResp.code): $($submitResp.message)" -ForegroundColor Red
    }
} catch {
    $elapsed = [math]::Round(((Get-Date) - $submitStartTime).TotalSeconds, 1)
    Write-Host "[FAIL] Submit error (${elapsed}s): $_" -ForegroundColor Red
    Write-Host "  This usually means DeepSeek API call failed" -ForegroundColor Yellow
}

# ========================================
# TC-004: Submit Low-Quality Answer (Skip Follow-up)
# ========================================
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "[TC-004] Low-Quality Answer Test (Skip Follow-up)" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

$lowQualityAnswer = "OK"

$answerBody2 = @{ answer = $lowQualityAnswer } | ConvertTo-Json

Write-Host "Submitting short answer (expect no follow-up)..." -ForegroundColor Gray

try {
    $submitResp2 = Invoke-RestMethod -Uri "$baseUrl/api/student/interview/$sessionId/answer" -Method Post `
        -Body $answerBody2 -ContentType "application/json" -WebSession $webSession -TimeoutSec 30

    if ($submitResp2.code -eq 200) {
        $isFollowUp2 = $submitResp2.data.isFollowUp
        
        Write-Host "[PASS] Answer submitted" -ForegroundColor Green
        Write-Host "  isFollowUp: $isFollowUp2"

        if ($isFollowUp2 -eq $false) {
            Write-Host "  [SUCCESS] Short answer skipped follow-up" -ForegroundColor Green
        } else {
            Write-Host "  [WARN] Short answer still triggered follow-up" -ForegroundColor Yellow
        }
    }
} catch {
    Write-Host "[FAIL] $_" -ForegroundColor Red
}

# ========================================
# TC-007/008: End Interview (Generate Report)
# ========================================
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "[TC-007/008] End Interview (Report)" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

Write-Host "Ending interview session..." -ForegroundColor Gray

try {
    $endResp = Invoke-RestMethod -Uri "$baseUrl/api/student/interview/$sessionId/end" -Method Post `
        -Body "{}" -ContentType "application/json" -WebSession $webSession -TimeoutSec 90

    if ($endResp.code -eq 200) {
        $report = $endResp.data

        Write-Host "[PASS] Report generated" -ForegroundColor Green
        Write-Host "  Total Score: $($report.totalScore)"
        Write-Host "  Comment: $($report.overallComment)"

        if ($report.dimensionScores) {
            Write-Host "  Dimensions:"
            Write-Host "    - Logic: $($report.dimensionScores.logic)"
            Write-Host "    - Professionalism: $($report.dimensionScores.professionalism)"
            Write-Host "    - Communication: $($report.dimensionScores.communication)"
            Write-Host "    - Adaptability: $($report.dimensionScores.adaptability)"
            Write-Host "    - JobFit: $($report.dimensionScores.jobFit)"
        }

        if ($report.highlights) {
            Write-Host "  Highlights: $($report.highlights -join '; ')"
        }
        if ($report.improvements) {
            Write-Host "  Improvements: $($report.improvements -join '; ')"
        }

        # Determine scoring method
        if ($report.overallComment -and $report.overallComment.Length -gt 50) {
            Write-Host "  [AI] Smart AI scoring (detailed comment)" -ForegroundColor Green
        } else {
            Write-Host "  [WARN] Possibly local algorithm scoring" -ForegroundColor Yellow
        }
    } else {
        Write-Host "[FAIL] code=$($endResp.code): $($endResp.message)" -ForegroundColor Red
    }
} catch {
    Write-Host "[FAIL] $_" -ForegroundColor Red
    Write-Host "  Note: All questions must be answered before generating report" -ForegroundColor Gray
}

# ========================================
# Test Summary
# ========================================
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "   Test Summary" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

Write-Host "`nKey Indicators:" -ForegroundColor Yellow
Write-Host "  [PASS][PASS][PASS] Follow-up + AI question = API FULLY OK" -ForegroundColor Green
Write-Host "  [PASS][WARN][WARN] Follow-up + local question = API OK, question fallback" -ForegroundColor Yellow
Write-Host "  [FAIL][FAIL][FAIL] No follow-up + local question = DeepSeek API unavailable" -ForegroundColor Red

Write-Host "`nCheck Backend Logs:" -ForegroundColor Yellow
Write-Host "  'DeepSeek API Key configured: sk-...' -> Key loaded OK" -ForegroundColor Gray
Write-Host "  'DeepSeek API call success: duration=...ms' -> API call OK" -ForegroundColor Gray
Write-Host "  'DeepSeek API call failed: ...' -> API call FAILED" -ForegroundColor Gray

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "   Test Complete" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
