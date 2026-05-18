# Atlas Migrate · smart-trim helper
#
# Detects long static "freeze" segments in a Playwright recording and
# applies a 4x speedup ONLY to those segments. Active moments
# (clicks, panel transitions, content streaming in) stay at 1x.
#
# IMPORTANT: the last $TailKeep seconds are always preserved at 1x
# regardless of freezedetect output. Without this, the video's
# end-hold (e.g. final closure document) gets compressed to a fraction
# of a second and the recording feels like it terminates abruptly.

param(
  [Parameter(Mandatory=$true)] [string] $Source,
  [Parameter(Mandatory=$true)] [string] $Output,
  [Parameter(Mandatory=$true)] [double] $TotalSeconds,
  [double] $SpeedUp  = 4.0,
  [double] $MinFreeze = 6.0,
  [double] $TailKeep  = 15.0
)

$work = "C:/Envestnet/demo-deliverables"

# 1. Detect freeze segments via ffmpeg freezedetect.
$raw = docker run --rm -v "${work}:/work" linuxserver/ffmpeg `
  -i "/work/$Source" -vf "freezedetect=n=0.003:d=2" -map 0:v -f null - 2>&1
$lines = $raw -split "`n" | Where-Object { $_ -match "freezedetect" }

$freezes = @(); $curStart = $null
foreach ($l in $lines) {
  if ($l -match "freeze_start:\s*([\d.]+)") { $curStart = [double]$matches[1] }
  elseif ($l -match "freeze_end:\s*([\d.]+)" -and $curStart -ne $null) {
    $freezes += [pscustomobject]@{ Start = $curStart; End = [double]$matches[1] }
    $curStart = $null
  }
}

# 2. Coalesce adjacent freezes (< 0.5s gap) into single dead segments.
$merged = @(); $cur = $null
foreach ($f in $freezes | Sort-Object Start) {
  if ($cur -eq $null) { $cur = [pscustomobject]@{ Start = $f.Start; End = $f.End }; continue }
  if ($f.Start - $cur.End -lt 0.5) { $cur.End = $f.End }
  else { $merged += $cur; $cur = [pscustomobject]@{ Start = $f.Start; End = $f.End } }
}
if ($cur -ne $null) { $merged += $cur }

# 3. Filter to "long enough to bother speeding up" AND clip away any
#    portion of a segment that overlaps the protected tail window.
$tailBoundary = $TotalSeconds - $TailKeep
$long = @()
foreach ($m in $merged) {
  if (($m.End - $m.Start) -lt $MinFreeze) { continue }
  if ($m.Start -ge $tailBoundary) { continue }  # entirely in tail - skip
  if ($m.End -gt $tailBoundary) {
    # Partial overlap: shorten the dead segment so the tail starts at 1x.
    $m.End = $tailBoundary
    if (($m.End - $m.Start) -lt $MinFreeze) { continue }
  }
  $long += $m
}

Write-Host "Long dead segments (>=${MinFreeze}s, before tail @ ${tailBoundary}s):"
$long | ForEach-Object { Write-Host ("  {0,6:F2}-{1,6:F2}  ({2,5:F1}s)" -f $_.Start, $_.End, ($_.End - $_.Start)) }

# 4. Walk the timeline and emit alternating active/dead segments.
$segments = @(); $cursor = 0.0
foreach ($d in $long | Sort-Object {$_.Start}) {
  if ($d.Start - $cursor -gt 0.05) {
    $segments += @{Type='active'; Start=$cursor; End=$d.Start}
  }
  $segments += @{Type='dead'; Start=$d.Start; End=$d.End}
  $cursor = $d.End
}
if ($TotalSeconds - $cursor -gt 0.05) {
  $segments += @{Type='active'; Start=$cursor; End=$TotalSeconds}
}

# 5. Build the ffmpeg filter_complex and run the concat.
$filters = @(); $labels = @()
for ($i = 0; $i -lt $segments.Count; $i++) {
  $s = $segments[$i]
  if ($s.Type -eq 'active') {
    $filters += "[0:v]trim=$($s.Start):$($s.End),setpts=PTS-STARTPTS[v$i]"
  } else {
    $filters += "[0:v]trim=$($s.Start):$($s.End),setpts=(PTS-STARTPTS)/$SpeedUp[v$i]"
  }
  $labels += "[v$i]"
}
$filterArg = ($filters -join ';') + ';' + ($labels -join '') +
             "concat=n=$($segments.Count):v=1:a=0[out]"
$filterArg | Out-File "$work/_filter.txt" -Encoding ASCII -NoNewline

docker run --rm -v "${work}:/work" linuxserver/ffmpeg `
  -y -hide_banner -loglevel error `
  -i "/work/$Source" `
  -filter_complex_script /work/_filter.txt `
  -map "[out]" -an -c:v libx264 -preset slow -crf 22 -pix_fmt yuv420p `
  -r 25 -movflags +faststart "/work/$Output" 2>&1 | Select-Object -Last 2
Remove-Item "$work/_filter.txt"

$dur = docker run --rm -v "${work}:/work" --entrypoint ffprobe linuxserver/ffmpeg `
  -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "/work/$Output" 2>&1
Write-Host "$Output  -> $dur s"
