param([string]$Src = '', [string]$Out = '', [switch]$Cards)
# Generates the published site from site-src/index.src.html:
#   docs/index.html       French page   https://slideen.github.io/MG4Control/
#   docs/en/index.html    English page  https://slideen.github.io/MG4Control/en/
#   docs/sitemap.xml
#   -Cards  also renders the 1200x630 social preview images (docs/assets/img/og-fr.png and
#           og-en.png) from site-src/og-card.html, with a headless Edge or Chrome.
#
# One language per page: a search engine reads each page in a single language. The former single
# page carried both, hid one with CSS and picked it from the browser language, so crawlers (which
# announce English) never saw the French version.
#
# ASCII only on purpose: Windows PowerShell 5.1 reads scripts without BOM as ANSI. Every localized
# text therefore lives in the HTML source, never in this script.

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
if ($Src -eq '') { $Src = Join-Path $here 'index.src.html' }
if ($Out -eq '') { $Out = Join-Path (Split-Path -Parent $here) 'docs' }

$BASE = 'https://slideen.github.io/MG4Control/'
$REPO = 'https://github.com/SliDeeN/MG4Control'
$LANG_TAGS = 'span|p|td|ul|ol'    # tags allowed to carry data-l
$NL = "`n"
$utf8 = New-Object System.Text.UTF8Encoding($false)
$dotall = [System.Text.RegularExpressions.RegexOptions]::Singleline
$source = [IO.File]::ReadAllText($Src, $utf8)

# relFr / relEn: links between the two pages, relative so that a local preview stays local.
$pages = [ordered]@{
  fr = @{ dir = '';    locale = 'fr_FR'; relFr = './';  relEn = 'en/' }
  en = @{ dir = 'en/'; locale = 'en_US'; relFr = '../'; relEn = './' }
}
# Shown to visitors whose language matches neither page.
$XDEFAULT = 'en'

function Get-One([string]$text, [string]$pattern, [string]$what) {
  $m = [regex]::Match($text, $pattern, $dotall)
  if (-not $m.Success) { throw ('Source: ' + $what + ' not found') }
  return $m.Groups[1].Value
}

function Set-Attr([string]$tag, [string]$name, [string]$value) {
  $m = [regex]::Match($tag, '\s' + $name + '="[^"]*"')
  if ($m.Success) { return $tag.Substring(0, $m.Index) + ' ' + $name + '="' + $value + '"' + $tag.Substring($m.Index + $m.Length) }
  return $tag.Substring(0, $tag.Length - 1) + ' ' + $name + '="' + $value + '">'
}

# ---- Texts taken from the source --------------------------------------------------------------
$title = @{
  fr = Get-One $source 'data-title-fr="([^"]*)"' 'data-title-fr'
  en = Get-One $source 'data-title-en="([^"]*)"' 'data-title-en'
}
$metaTag = Get-One $source '(<meta name="description"[^>]*>)' 'meta description'
$desc = @{
  fr = Get-One $metaTag 'data-fr="([^"]*)"' 'description data-fr'
  en = Get-One $metaTag 'data-en="([^"]*)"' 'description data-en'
}
$htmlTag  = Get-One $source '(<html\b[^>]*>)' 'html tag'
$titleTag = Get-One $source '(<title>[^<]*</title>)' 'title tag'
$version  = Get-One $source '<span class="pill">v([0-9.]+)</span>' 'version pill'
foreach ($needed in @('<!-- @seo -->', '{{REL_FR}}', '{{REL_EN}}')) {
  if (-not $source.Contains($needed)) { throw ('Source: ' + $needed + ' not found') }
}

# ---- Guard: the removal below relies on it ------------------------------------------------------
# An element carrying data-l must not contain another element of the same tag name: the first
# closing tag of that name is then its own. $LANG_BODY stops at that first closing tag and never
# runs past it (a lazy ".*?" would, as soon as what follows the element fails to match).
$LANG_BODY = '(?:(?!</\1>).)*</\1>'
$langOpen = '<(' + $LANG_TAGS + ')\b[^>]*\bdata-l="(fr|en)"[^>]*>'
foreach ($m in [regex]::Matches($source, $langOpen + $LANG_BODY, $dotall)) {
  $tag = $m.Groups[1].Value
  if ([regex]::Matches($m.Value, '<' + $tag + '\b').Count -ne 1) {
    throw ('Source: a <' + $tag + ' data-l> contains another <' + $tag + '>: ' + $m.Value.Substring(0, [Math]::Min(120, $m.Value.Length)))
  }
}
$stray = [regex]::Match($source, '<(?!(?:' + $LANG_TAGS + ')\b)[a-zA-Z][^>]*\bdata-l="')
if ($stray.Success) { throw ('Source: data-l on an unsupported tag: ' + $stray.Value) }

function Remove-Lang([string]$html, [string]$lang) {
  $open = '<(' + $LANG_TAGS + ')\b[^>]*\bdata-l="' + $lang + '"[^>]*>'
  # Alone on its line(s): the line goes with it.
  $html = [regex]::Replace($html, '(?m)^[ \t]*' + $open + $LANG_BODY + '[ \t]*\r?\n', '', $dotall)
  $html = [regex]::Replace($html, $open + $LANG_BODY, '', $dotall)
  if ($html.Contains('data-l="' + $lang + '"')) { throw ('data-l="' + $lang + '" still present after removal') }
  return $html
}

# aria-label (and title, when the tag has one) in the page language, from data-aria-fr / -en.
function Resolve-Aria([string]$html, [string]$lang) {
  $sb = New-Object System.Text.StringBuilder
  $pos = 0
  foreach ($m in [regex]::Matches($html, '<[a-zA-Z][^>]*\bdata-aria-fr="[^"]*"[^>]*>')) {
    $tag = $m.Value
    $value = Get-One $tag ('data-aria-' + $lang + '="([^"]*)"') ('data-aria-' + $lang + ' in ' + $tag)
    $hadTitle = [regex]::IsMatch($tag, '\stitle="')
    $tag = [regex]::Replace($tag, '\s+data-aria-(?:fr|en)="[^"]*"', '')
    $tag = Set-Attr $tag 'aria-label' $value
    if ($hadTitle) { $tag = Set-Attr $tag 'title' $value }
    [void]$sb.Append($html.Substring($pos, $m.Index - $pos)).Append($tag)
    $pos = $m.Index + $m.Length
  }
  [void]$sb.Append($html.Substring($pos))
  return $sb.ToString()
}

function New-JsonLd([string]$lang) {
  $data = [ordered]@{
    '@context'          = 'https://schema.org'
    '@type'             = 'SoftwareApplication'
    name                = 'MG4Control'
    description         = [System.Net.WebUtility]::HtmlDecode($desc[$lang])
    url                 = $BASE + $pages[$lang].dir
    image               = $BASE + 'assets/img/icon-512.png'
    inLanguage          = $lang
    applicationCategory = 'UtilitiesApplication'
    operatingSystem     = 'Android Automotive OS (Android 9)'
    softwareVersion     = $version
    isAccessibleForFree = $true
    license             = 'https://www.gnu.org/licenses/gpl-3.0.html'
    downloadUrl         = $REPO + '/releases/latest'
    sameAs              = @($REPO)
    author              = [ordered]@{ '@type' = 'Person'; name = 'SliDeeN'; url = 'https://github.com/SliDeeN' }
    offers              = [ordered]@{ '@type' = 'Offer'; price = '0'; priceCurrency = 'EUR' }
  }
  return ($data | ConvertTo-Json -Depth 5 -Compress)
}

# Canonical address, alternate languages, Open Graph / Twitter cards and structured data.
function New-Seo([string]$lang) {
  $url = $BASE + $pages[$lang].dir
  $image = $BASE + 'assets/img/og-' + $lang + '.png'
  $lines = @('<link rel="canonical" href="' + $url + '">')
  foreach ($l in $pages.Keys) { $lines += '<link rel="alternate" hreflang="' + $l + '" href="' + $BASE + $pages[$l].dir + '">' }
  $lines += '<link rel="alternate" hreflang="x-default" href="' + $BASE + $pages[$XDEFAULT].dir + '">'
  $lines += '<meta property="og:type" content="website">'
  $lines += '<meta property="og:site_name" content="MG4Control">'
  $lines += '<meta property="og:url" content="' + $url + '">'
  $lines += '<meta property="og:title" content="' + $title[$lang] + '">'
  $lines += '<meta property="og:description" content="' + $desc[$lang] + '">'
  $lines += '<meta property="og:locale" content="' + $pages[$lang].locale + '">'
  foreach ($l in $pages.Keys) { if ($l -ne $lang) { $lines += '<meta property="og:locale:alternate" content="' + $pages[$l].locale + '">' } }
  $lines += '<meta property="og:image" content="' + $image + '">'
  $lines += '<meta property="og:image:width" content="1200">'
  $lines += '<meta property="og:image:height" content="630">'
  $lines += '<meta property="og:image:alt" content="' + $title[$lang] + '">'
  $lines += '<meta name="twitter:card" content="summary_large_image">'
  $lines += '<meta name="twitter:title" content="' + $title[$lang] + '">'
  $lines += '<meta name="twitter:description" content="' + $desc[$lang] + '">'
  $lines += '<meta name="twitter:image" content="' + $image + '">'
  $lines += '<script type="application/ld+json">' + (New-JsonLd $lang) + '</script>'
  return ($lines -join ($NL + '  '))
}

# "?v=<hash of the file>" on the local stylesheets and scripts: a browser never pairs a new page
# with the copy of an older script it kept in cache (GitHub Pages lets it keep them 10 minutes).
function Add-AssetVersions([string]$html) {
  $sb = New-Object System.Text.StringBuilder
  $pos = 0
  foreach ($m in [regex]::Matches($html, '="(assets/[^"?]+\.(?:css|js))"')) {
    $rel = $m.Groups[1].Value
    $file = Join-Path $Out ($rel -replace '/', '\')
    if (-not (Test-Path $file)) { throw ('Source: ' + $rel + ' does not exist in ' + $Out) }
    $hash = (Get-FileHash -Algorithm MD5 -LiteralPath $file).Hash.Substring(0, 8).ToLower()
    [void]$sb.Append($html.Substring($pos, $m.Index - $pos)).Append('="' + $rel + '?v=' + $hash + '"')
    $pos = $m.Index + $m.Length
  }
  [void]$sb.Append($html.Substring($pos))
  return $sb.ToString()
}

function Build-Page([string]$lang) {
  $page = $pages[$lang]
  $other = if ($lang -eq 'fr') { 'en' } else { 'fr' }
  $altRel = if ($lang -eq 'fr') { $page.relEn } else { $page.relFr }

  # The source header first: it quotes data-l and the tokens, which the checks below would catch.
  $h = [regex]::Replace($source, '<!-- @source.*?-->', '<!-- Generated by site-src/build.ps1 from site-src/index.src.html. Do not edit by hand. -->', $dotall)
  $h = Remove-Lang $h $other
  $h = Resolve-Aria $h $lang
  $h = $h.Replace($htmlTag,'<html lang="' + $lang + '" data-lang="' + $lang + '" data-alt="' + $altRel + '">')
  $h = $h.Replace($titleTag, '<title>' + $title[$lang] + '</title>')
  $h = $h.Replace($metaTag, '<meta name="description" content="' + $desc[$lang] + '">')
  $h = $h.Replace('<!-- @seo -->', (New-Seo $lang))
  $h = $h.Replace('{{REL_FR}}', $page.relFr).Replace('{{REL_EN}}', $page.relEn)
  $h = $h.Replace('data-lang-btn="' + $lang + '"', 'data-lang-btn="' + $lang + '" aria-current="true"')
  $h = Add-AssetVersions $h
  # Pages below the root reach the shared assets one level up.
  if ($page.dir -ne '') { $h = $h.Replace('="assets/', '="../assets/') }

  foreach ($left in @('{{', 'data-aria-', 'data-title-', '@seo', '@source', ('data-l="' + $other + '"'))) {
    if ($h.Contains($left)) { throw ('Page ' + $lang + ': "' + $left + '" left in the output') }
  }
  $dir = Join-Path $Out ($page.dir -replace '/', '\')
  if (-not (Test-Path $dir)) { [void](New-Item -ItemType Directory -Path $dir) }
  $file = Join-Path $dir 'index.html'
  [IO.File]::WriteAllText($file, $h, $utf8)
  Write-Host ('  ' + $lang + ': ' + $file + ' (' + $h.Length + ' chars)')
}

Write-Host ('Site ' + $version + ' from ' + $Src)
foreach ($lang in $pages.Keys) { Build-Page $lang }

# ---- Sitemap ------------------------------------------------------------------------------------
# lastmod follows the source file, not the build date: rebuilding without a change changes nothing.
$lastmod = (Get-Item $Src).LastWriteTimeUtc.ToString('yyyy-MM-dd')
$sm = New-Object System.Text.StringBuilder
[void]$sm.Append('<?xml version="1.0" encoding="UTF-8"?>' + $NL)
[void]$sm.Append('<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">' + $NL)
foreach ($lang in $pages.Keys) {
  [void]$sm.Append('  <url>' + $NL)
  [void]$sm.Append('    <loc>' + $BASE + $pages[$lang].dir + '</loc>' + $NL)
  [void]$sm.Append('    <lastmod>' + $lastmod + '</lastmod>' + $NL)
  foreach ($l in $pages.Keys) { [void]$sm.Append('    <xhtml:link rel="alternate" hreflang="' + $l + '" href="' + $BASE + $pages[$l].dir + '"/>' + $NL) }
  [void]$sm.Append('    <xhtml:link rel="alternate" hreflang="x-default" href="' + $BASE + $pages[$XDEFAULT].dir + '"/>' + $NL)
  [void]$sm.Append('  </url>' + $NL)
}
[void]$sm.Append('</urlset>' + $NL)
[IO.File]::WriteAllText((Join-Path $Out 'sitemap.xml'), $sm.ToString(), $utf8)
Write-Host ('  sitemap.xml (lastmod ' + $lastmod + ')')

# ---- Social preview images (optional) -----------------------------------------------------------
if ($Cards) {
  $candidates = @(
    (Join-Path ${env:ProgramFiles(x86)} 'Microsoft\Edge\Application\msedge.exe'),
    (Join-Path $env:ProgramFiles 'Microsoft\Edge\Application\msedge.exe'),
    (Join-Path $env:ProgramFiles 'Google\Chrome\Application\chrome.exe'),
    (Join-Path ${env:ProgramFiles(x86)} 'Google\Chrome\Application\chrome.exe')
  )
  $browser = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
  if (-not $browser) { throw 'Neither Edge nor Chrome found: cannot render the preview images.' }
  $card = ([System.Uri](Join-Path $here 'og-card.html')).AbsoluteUri
  # A profile of its own: a browser already open would otherwise swallow the command.
  $browserProfile = Join-Path $env:TEMP 'mg4control-og-profile'
  foreach ($lang in $pages.Keys) {
    $png = Join-Path $Out ('assets\img\og-' + $lang + '.png')
    if (Test-Path $png) { Remove-Item $png }
    # Quoted by hand: Start-Process joins the arguments with spaces and does not quote them.
    $browserArgs = @('--headless', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1',
      '--window-size=1200,630', '--virtual-time-budget=6000', ('--user-data-dir="' + $browserProfile + '"'),
      ('--screenshot="' + $png + '"'), ('"' + $card + '?lang=' + $lang + '"'))
    $p = Start-Process -FilePath $browser -ArgumentList $browserArgs -Wait -PassThru -WindowStyle Hidden
    if (-not (Test-Path $png)) { throw ('Preview image not written: ' + $png + ' (exit code ' + $p.ExitCode + ')') }
    Write-Host ('  ' + $png + ' (' + [Math]::Round((Get-Item $png).Length / 1KB) + ' KB)')
  }
  Remove-Item -Recurse -Force $browserProfile -ErrorAction SilentlyContinue
}
