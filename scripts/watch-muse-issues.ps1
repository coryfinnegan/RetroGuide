$announced = @{}
while ($true) {
  try {
    $issues = gh issue list --label muse-task --state open --limit 50 --json number,title,labels | ConvertFrom-Json
    $ready = $issues | Where-Object {
      $names = $_.labels.name
      ($names -notcontains 'muse-working') -and ($names -notcontains 'muse-blocked')
    } | Sort-Object number
    foreach ($i in $ready) {
      if (-not $announced.ContainsKey($i.number)) {
        $announced[$i.number] = $true
        Write-Output "ISSUE $($i.number): $($i.title)"
      }
    }
  } catch { }
  Start-Sleep -Seconds 60
}
