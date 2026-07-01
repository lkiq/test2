$base = "http://localhost:8080"

# Step 1: Login to get JWT token
Write-Host "=== LOGIN ===" -ForegroundColor Cyan
$lb = @{username="test_student2";password="123456"} | ConvertTo-Json
$lr = Invoke-RestMethod -Uri "$base/api/auth/login" -Method Post -Body $lb -ContentType "application/json"
$token = $lr.data.token
Write-Host "Login: code=$($lr.code), user=$($lr.data.username), token=...$($token.Substring($token.Length-10))"

# Common headers for all authenticated requests
$headers = @{
    "Authorization" = "Bearer $token"
    "Content-Type" = "application/json"
}

# Step 2: Start Interview
Write-Host "`n=== START INTERVIEW ===" -ForegroundColor Cyan
$sb = @{targetJobId=1;interviewType="TECHNICAL"} | ConvertTo-Json
$sr = Invoke-RestMethod -Uri "$base/api/student/interview/start" -Method Post -Body $sb -Headers $headers
Write-Host "Start: code=$($sr.code)"
Write-Host "Q: $($sr.data.question)"
Write-Host "Type: $($sr.data.questionType)"
Write-Host "Total questions: $($sr.data.totalQuestions)"
$sid = $sr.data.sessionId
$qLen = $sr.data.question.Length
if ($qLen -gt 50) { Write-Host "[AI] Long question - likely AI-generated" -ForegroundColor Green }
else { Write-Host "[FALLBACK] Short question - local fallback" -ForegroundColor Yellow }

# Step 3: Submit high-quality answer (test follow-up)
Write-Host "`n=== SUBMIT HIGH-QUALITY ANSWER (Follow-up Test) ===" -ForegroundColor Cyan
$answer = "I developed a microservice architecture using Spring Cloud with service discovery via Eureka and API gateway with Spring Cloud Gateway. For inter-service communication, I used Feign clients with Hystrix circuit breakers. Data consistency was ensured through the Saga pattern implemented with RabbitMQ message queues. I also set up centralized configuration with Spring Cloud Config and distributed tracing with Sleuth and Zipkin."
$ab = @{answer=$answer} | ConvertTo-Json
Write-Host "Submitting answer (waiting for AI, max 90s)..."
$s1 = Get-Date
$ar = Invoke-RestMethod -Uri "$base/api/student/interview/$sid/answer" -Method Post -Body $ab -Headers $headers -TimeoutSec 90
$t1 = [math]::Round(((Get-Date)-$s1).TotalSeconds, 1)
Write-Host "Submit: code=$($ar.code), time=${t1}s"
Write-Host "isFollowUp: $($ar.data.isFollowUp)"
Write-Host "Next: $($ar.data.question)"
if ($ar.data.feedback) { Write-Host "Feedback: $($ar.data.feedback)" -ForegroundColor DarkYellow }
if ($ar.data.isFollowUp -eq $true) { 
    Write-Host "*** FOLLOW-UP TRIGGERED! ***" -ForegroundColor Green 
    Write-Host "`n--- Follow-up Chain Test ---" -ForegroundColor Yellow
    $fuAnswer = @{answer="For distributed locks I used Redisson framework with SETNX + Lua scripts for atomic operations. The watch-dog mechanism prevents premature lock release. I also introduced Bloom filters to prevent cache penetration issues."} | ConvertTo-Json
    try {
        $fuResp = Invoke-RestMethod -Uri "$base/api/student/interview/$sid/answer" -Method Post -Body $fuAnswer -Headers $headers -TimeoutSec 90
        Write-Host "Follow-up answer: code=$($fuResp.code), nextFollowUp=$($fuResp.data.isFollowUp)" 
    } catch { Write-Host "Follow-up answer failed: $_" -ForegroundColor Yellow }
} else { 
    Write-Host "[WARN] No follow-up triggered" -ForegroundColor Yellow 
}

# Step 4: End interview (generate report)
Write-Host "`n=== END INTERVIEW (Generate Report) ===" -ForegroundColor Cyan
$er = Invoke-RestMethod -Uri "$base/api/student/interview/$sid/end" -Method Post -Body "{}" -Headers $headers -TimeoutSec 90
Write-Host "End: code=$($er.code)"
Write-Host "Total Score: $($er.data.totalScore)"
Write-Host "Overall Comment: $($er.data.overallComment)"
if ($er.data.dimensionScores) {
    $ds = $er.data.dimensionScores
    Write-Host "Dimensions: L=$($ds.logic) P=$($ds.professionalism) C=$($ds.communication) A=$($ds.adaptability) J=$($ds.jobFit)"
}
if ($er.data.highlights) { Write-Host "Highlights: $($er.data.highlights -join ' | ')" }
if ($er.data.improvements) { Write-Host "Improvements: $($er.data.improvements -join ' | ')" }

$cLen = if ($er.data.overallComment) { $er.data.overallComment.Length } else { 0 }
if ($cLen -gt 80) { Write-Host "*** AI Scoring (detailed comment) ***" -ForegroundColor Green }
else { Write-Host "[FALLBACK] Local scoring" -ForegroundColor Yellow }

Write-Host "`n=== TEST COMPLETE ===" -ForegroundColor Cyan
