# Optional pre-build; normal Maven package also rebuilds the frontend.
$ErrorActionPreference = 'Stop'
$moduleDirectory = Split-Path -Parent $PSScriptRoot
& mvn -f (Join-Path $moduleDirectory 'pom.xml') generate-resources
if ($LASTEXITCODE -ne 0) { throw '前端构建失败，请查看 Maven/npm 输出。' }
Write-Host '前端已更新；正常 Maven package 仍会自动构建。'
