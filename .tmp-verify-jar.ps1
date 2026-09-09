$jar = 'D:\workspace\data-agent\agentscope-examples\agents\agentscope-dataagent\target\agentscope-dataagent-2.0.3-SNAPSHOT-exec.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$md5 = [System.Security.Cryptography.MD5]::Create()
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
  $prefix = 'BOOT-INF/classes/'
  $pairs = @(
    @('shared/agents/data-agent/skills/chart-rendering/SKILL.md', 'D:\workspace\data-agent\shared\agents\data-agent\skills\chart-rendering\SKILL.md'),
    @('shared/agents/data-agent/skills/sql-analysis/SKILL.md',    'D:\workspace\data-agent\shared\agents\data-agent\skills\sql-analysis\SKILL.md'),
    @('shared/agents/data-agent/subagents/data-explorer.md',      'D:\workspace\data-agent\shared\agents\data-agent\subagents\data-explorer.md'),
    @('shared/agents/data-agent/subagents/report-writer.md',      'D:\workspace\data-agent\shared\agents\data-agent\subagents\report-writer.md'),
    @('application.yml',                                          'D:\workspace\data-agent\agentscope-examples\agents\agentscope-dataagent\src\main\resources\application.yml')
  )
  foreach ($pair in $pairs) {
    $entryName = $prefix + $pair[0]
    $diskPath = $pair[1]
    $e = $zip.Entries | Where-Object { $_.FullName -eq $entryName } | Select-Object -First 1
    if ($null -eq $e) { Write-Output "MISSING ENTRY: $entryName"; continue }
    $ms = New-Object System.IO.MemoryStream
    $s = $e.Open(); $s.CopyTo($ms); $s.Close()
    $h1 = [System.BitConverter]::ToString($md5.ComputeHash($ms.ToArray()))
    $h2 = [System.BitConverter]::ToString($md5.ComputeHash([System.IO.File]::ReadAllBytes($diskPath)))
    if ($h1 -eq $h2) { Write-Output "MATCH: $($pair[0])" } else { Write-Output "DIFFER: $($pair[0])" }
  }
} finally { $zip.Dispose() }
