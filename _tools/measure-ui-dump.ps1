<#
  measure-ui-dump.ps1  -  turn a uiautomator dump XML into a px -> dp measurement table.
  Usage: & .\_tools\measure-ui-dump.ps1 -Path <dump.xml> [-Density 3] [-Out <out.txt>]
  Rules: read UTF-8; bounds="[x1,y1][x2,y2]"; dp = px / density.
  ASCII-only on purpose (safe under PS 5.1 GBK parsing rules).
#>
param(
  [Parameter(Mandatory=$true)][string]$Path,
  [double]$Density = 3,
  [string]$Out,
  [int]$GapLimit = 400
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Path)) { throw "missing dump: $Path" }
if (-not $Out) { $Out = [System.IO.Path]::ChangeExtension($Path, '.measure.txt') }

$xml = Get-Content -Raw -Encoding UTF8 -LiteralPath $Path
$lines = New-Object System.Collections.Generic.List[string]
$scr = [regex]::Match($xml, '<hierarchy[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
$sw = 0; $sh = 0
if ($scr.Success) { $sw = [int]$scr.Groups[3].Value; $sh = [int]$scr.Groups[4].Value }
$swDp = [math]::Round($sw/$Density,1); $shDp = [math]::Round($sh/$Density,1)

$lines.Add("# UI dump measurement table")
$lines.Add("# source   : $Path")
$lines.Add("# density  : $Density   (dp = px / $Density)")
$lines.Add("# screen   : $sw x $sh px  ->  $swDp x $shDp dp")
$lines.Add("# nodes    : $(([regex]::Matches($xml,'<node')).Count)")
$lines.Add("")
$lines.Add("idx | kind | label | px[x1,y1][x2,y2] | dp[x1,y1][x2,y2] | w x h (dp)")

$items = New-Object System.Collections.Generic.List[object]
$i = 0
foreach ($m in [regex]::Matches($xml, '<node[^>]*>')) {
  $n = $m.Value
  $b = [regex]::Match($n, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if (-not $b.Success) { continue }
  $i++
  $x1 = [int]$b.Groups[1].Value; $y1 = [int]$b.Groups[2].Value
  $x2 = [int]$b.Groups[3].Value; $y2 = [int]$b.Groups[4].Value
  $t = [regex]::Match($n, 'text="([^"]*)"').Groups[1].Value
  $d = [regex]::Match($n, 'content-desc="([^"]*)"').Groups[1].Value
  $r = [regex]::Match($n, 'resource-id="([^"]*)"').Groups[1].Value
  if ($t) { $kind = 'text'; $label = $t }
  elseif ($d) { $kind = 'desc'; $label = $d }
  else { $kind = 'box'; $label = '' }
  $w = [math]::Round(($x2-$x1)/$Density,1); $h = [math]::Round(($y2-$y1)/$Density,1)
  $items.Add([pscustomobject]@{ i=$i; kind=$kind; label=$label; rid=$r; x1=$x1; y1=$y1; x2=$x2; y2=$y2; w=$w; h=$h })
  $dp = "[$([math]::Round($x1/$Density,1)),$([math]::Round($y1/$Density,1))][$([math]::Round($x2/$Density,1)),$([math]::Round($y2/$Density,1))]"
  $short = if ($r) { "$label  <$r>" } else { $label }
  $lines.Add("$i | $kind | $short | px[$x1,$y1][$x2,$y2] | dp$dp | $w x $h")
}

$lines.Add("")
$lines.Add("## distinct left edges (x1 px -> count)")
$lefts = @{}
foreach ($it in $items) { $k = "$($it.x1)"; if ($lefts.ContainsKey($k)) { $lefts[$k]++ } else { $lefts[$k] = 1 } }
foreach ($k in ($lefts.Keys | Sort-Object { [int]$_ })) {
  $lines.Add(("  px {0,5} = {1,5} dp   x{2}" -f $k, ([math]::Round([int]$k/$Density,1)), $lefts[$k]))
}

$lines.Add("")
$lines.Add("## vertical steps between labelled nodes (dy <= $GapLimit px), sorted by y1")
$lab = @($items | Where-Object { $_.label } | Sort-Object y1)
for ($j = 1; $j -lt $lab.Count; $j++) {
  $dy = $lab[$j].y1 - $lab[$j-1].y1
  if ($dy -gt 0 -and $dy -le $GapLimit) {
    $lines.Add(("  dy {0,4} px = {1,6} dp   {2} -> {3}" -f $dy, ([math]::Round($dy/$Density,1)), $lab[$j-1].label, $lab[$j].label))
  }
}

Set-Content -LiteralPath $Out -Value $lines -Encoding utf8
Write-Output ("WROTE " + $Out + " lines=" + $lines.Count)
