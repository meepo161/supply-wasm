# Базовый smoke-нагрузочный тест (NFR-05, TST-27)
param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Requests = 50
)

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$ok = 0
for ($i = 1; $i -le $Requests; $i++) {
    try {
        $resp = Invoke-WebRequest -Uri "$BaseUrl/api/health" -UseBasicParsing -TimeoutSec 5
        if ($resp.StatusCode -eq 200) { $ok++ }
    } catch { }
}
$sw.Stop()
Write-Host "Completed $ok / $Requests requests in $($sw.ElapsedMilliseconds) ms"
