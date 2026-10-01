param([string]$Res, [string]$Out, [string]$ResBeta = '')
# Generates assets/js/strings.js from the app's res/values*/strings.xml.
#   -Res      res folder of the stable version (branch main)
#   -ResBeta  optional res folder of the beta version: only keys that differ from stable
#             (or are new, or whole new languages) are written to window.MG4_STRINGS_BETA.
# ASCII only on purpose: Windows PowerShell 5.1 reads scripts without BOM as ANSI.

$ARROW_ESC = [string][char]92 + "u2192"
$langs = [ordered]@{ fr = 'values'; en = 'values-en'; de = 'values-de'; es = 'values-es'; it = 'values-it'; pt = 'values-pt'; tr = 'values-tr' }

function Read-Strings([string]$dir) {
  $map = [ordered]@{}
  $path = Join-Path $dir 'strings.xml'
  if (-not (Test-Path $path)) { return $null }
  $xml = New-Object System.Xml.XmlDocument
  $xml.PreserveWhitespace = $true
  $xml.Load($path)
  foreach ($n in $xml.SelectNodes('/resources/string')) {
    $v = $n.InnerText
    $v = $v.Replace('\''', '''').Replace('\"', '"').Replace('\n', "`n").Replace('\t', ' ').Replace($ARROW_ESC, [string][char]0x2192)
    $map[$n.GetAttribute('name')] = $v
  }
  return $map
}

function Write-Dict($sb, [string]$name, $dicts) {
  [void]$sb.Append('window.' + $name + ' = {' + "`n")
  $first = $true
  foreach ($l in $dicts.Keys) {
    $d = $dicts[$l]
    if ($d.Count -eq 0) { continue }
    if (-not $first) { [void]$sb.Append(',' + "`n") }
    $first = $false
    [void]$sb.Append('  ' + $l + ': {')
    $firstKey = $true
    foreach ($k in $d.Keys) {
      if (-not $firstKey) { [void]$sb.Append(',') }
      $firstKey = $false
      [void]$sb.Append("`n    " + ($k | ConvertTo-Json) + ': ' + ($d[$k] | ConvertTo-Json))
    }
    [void]$sb.Append("`n  }")
  }
  [void]$sb.Append("`n" + '};' + "`n")
}

$stable = [ordered]@{}
foreach ($l in $langs.Keys) {
  $m = Read-Strings (Join-Path $Res $langs[$l])
  if ($m -ne $null) { $stable[$l] = $m }
}

$sb = New-Object System.Text.StringBuilder
[void]$sb.Append('// Generated from app/src/main/res/values(-xx)/strings.xml (MG4Control). Do not edit by hand.' + "`n")
Write-Dict $sb 'MG4_STRINGS' $stable

if ($ResBeta -ne '') {
  $beta = [ordered]@{}
  foreach ($l in $langs.Keys) {
    $m = Read-Strings (Join-Path $ResBeta $langs[$l])
    if ($m -eq $null) { continue }
    $diff = [ordered]@{}
    $base = $stable[$l]
    foreach ($k in $m.Keys) {
      if ($base -eq $null -or -not $base.Contains($k) -or $base[$k] -ne $m[$k]) { $diff[$k] = $m[$k] }
    }
    $beta[$l] = $diff
  }
  [void]$sb.Append('// Beta branch: only the keys that differ from the stable version.' + "`n")
  Write-Dict $sb 'MG4_STRINGS_BETA' $beta
}

$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($Out, $sb.ToString(), $utf8)
