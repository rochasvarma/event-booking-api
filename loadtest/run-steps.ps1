# Windows PowerShell: step up concurrency to find the breaking point.
#   .\loadtest\run-steps.ps1 -BaseUrl https://your-app.onrender.com
param(
  [string]$BaseUrl = "http://localhost:8080",
  [int[]]$Steps = @(10, 25, 50, 100, 200, 400, 800),
  [string]$Duration = "20s"
)
New-Item -ItemType Directory -Force -Path loadtest/results | Out-Null
# Warm-up run (discarded): lets the JVM JIT-compile the hot path so step 1 isn't penalised.
Write-Host "Warming up the server (results discarded)..." -ForegroundColor DarkGray
k6 run -q -e BASE_URL=$BaseUrl -e VUS=10 -e DURATION=15s -e CUSTOMERS=10 loadtest/booking.js | Out-Null
foreach ($v in $Steps) {
  Write-Host "`n===== $v concurrent users =====" -ForegroundColor Cyan
  k6 run -q -e BASE_URL=$BaseUrl -e VUS=$v -e DURATION=$Duration loadtest/booking.js | Select-String "RESULT"
}
Write-Host "`nSummary:" -ForegroundColor Green
Get-ChildItem loadtest/results/vus-*.txt | Sort-Object { [int]($_.BaseName -replace 'vus-','') } | ForEach-Object { Get-Content $_ }
