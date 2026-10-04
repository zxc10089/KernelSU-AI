param(
  [Parameter(Mandatory = $true)][string]$A,
  [Parameter(Mandatory = $true)][string]$B
)
# Compare two source trees by relative path + SHA256, ignoring build/VCS noise.

function Get-TreeMap([string]$root) {
  $map = @{}
  $rootLen = $root.TrimEnd('\').Length
  Get-ChildItem -Recurse -File -Force $root | ForEach-Object {
    $rel = $_.FullName.Substring($rootLen).TrimStart('\')
    if ($rel -like '.git\*') { return }
    if ($rel -like '*\build\*' -or $rel -like 'build\*') { return }
    if ($rel -like '.gradle\*' -or $rel -like '*\.gradle\*') { return }
    if ($rel -like '*\.cxx\*') { return }
    if ($rel -like '*\.kotlin\*') { return }
    $map[$rel] = (Get-FileHash -Algorithm SHA256 $_.FullName).Hash
  }
  return $map
}

$ma = Get-TreeMap $A
$mb = Get-TreeMap $B

$onlyA = @($ma.Keys | Where-Object { -not $mb.ContainsKey($_) } | Sort-Object)
$onlyB = @($mb.Keys | Where-Object { -not $ma.ContainsKey($_) } | Sort-Object)
$diff = @($ma.Keys | Where-Object { $mb.ContainsKey($_) -and $mb[$_] -ne $ma[$_] } | Sort-Object)

"FILE_COUNT A=$($ma.Count) B=$($mb.Count)"
"ONLY_IN_A=$($onlyA.Count) ONLY_IN_B=$($onlyB.Count) DIFFERING=$($diff.Count)"
'--- ONLY_IN_A ---'
$onlyA | ForEach-Object { "  $_" }
'--- ONLY_IN_B ---'
$onlyB | ForEach-Object { "  $_" }
'--- DIFFERING ---'
$diff | ForEach-Object { "  $_" }
