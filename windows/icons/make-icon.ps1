# Genera el icono de VYBRA (barras con degradado sobre fondo oscuro) en PNG e ICO.
# Uso: powershell -ExecutionPolicy Bypass -File icons\make-icon.ps1
Add-Type -AssemblyName System.Drawing
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

function New-VybraBitmap([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)
    $s = $size / 108.0
    # Fondo redondeado.
    $r = 24 * $s
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $w = $size - 1
    $path.AddArc(0, 0, 2 * $r, 2 * $r, 180, 90)
    $path.AddArc($w - 2 * $r, 0, 2 * $r, 2 * $r, 270, 90)
    $path.AddArc($w - 2 * $r, $w - 2 * $r, 2 * $r, 2 * $r, 0, 90)
    $path.AddArc(0, $w - 2 * $r, 2 * $r, 2 * $r, 90, 90)
    $path.CloseFigure()
    $bg = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 14, 14, 18))
    $g.FillPath($bg, $path)
    # Barras (mismas coordenadas que el icono de Android, sobre 108x108).
    $p1 = New-Object System.Drawing.PointF (34 * $s), (32 * $s)
    $p2 = New-Object System.Drawing.PointF (74 * $s), (76 * $s)
    $grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush $p1, $p2, ([System.Drawing.Color]::FromArgb(255, 255, 122, 143)), ([System.Drawing.Color]::FromArgb(255, 255, 46, 85))
    $pen = New-Object System.Drawing.Pen $grad, ([Math]::Max(1.5, 6.5 * $s))
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $bars = @(@(34, 49, 59), @(44, 40, 68), @(54, 32, 76), @(64, 42, 66), @(74, 49, 59))
    foreach ($b in $bars) {
        $g.DrawLine($pen, [float]($b[0] * $s), [float]($b[1] * $s), [float]($b[0] * $s), [float]($b[2] * $s))
    }
    $g.Dispose()
    return $bmp
}

$sizes = @(16, 24, 32, 48, 64, 128, 256)
$pngs = @()
foreach ($sz in $sizes) {
    $bmp = New-VybraBitmap $sz
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $pngs += , $ms.ToArray()
    if ($sz -eq 256) { $bmp.Save((Join-Path $here "..\src\main\resources\vybra.png"), [System.Drawing.Imaging.ImageFormat]::Png) }
    $bmp.Dispose()
}

# ICO con imágenes PNG incrustadas.
$out = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter $out
$bw.Write([UInt16]0); $bw.Write([UInt16]1); $bw.Write([UInt16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $sz = $sizes[$i]; $len = $pngs[$i].Length
    $dim = if ($sz -ge 256) { 0 } else { $sz }
    $bw.Write([byte]$dim); $bw.Write([byte]$dim); $bw.Write([byte]0); $bw.Write([byte]0)
    $bw.Write([UInt16]1); $bw.Write([UInt16]32); $bw.Write([UInt32]$len); $bw.Write([UInt32]$offset)
    $offset += $len
}
foreach ($p in $pngs) { $bw.Write($p) }
$bw.Flush()
[System.IO.File]::WriteAllBytes((Join-Path $here "vybra.ico"), $out.ToArray())
Write-Output "Icono generado"
