param(
    [string]$Source = (Join-Path $PSScriptRoot '..\icon-original.png'),
    [string]$Output = (Join-Path $PSScriptRoot '..\src\main\resources\assets\overtone_party\icon.png')
)

Add-Type -AssemblyName System.Drawing

$bmp = New-Object System.Drawing.Bitmap 128, 128
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit

# Original icon stays untouched underneath the badge.
$src = [System.Drawing.Image]::FromFile($Source)
$g.DrawImage($src, 0, 0, 128, 128)
$src.Dispose()

$dark   = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 32, 12, 32))
$shadow = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(90, 0, 0, 0))
$white  = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
$accent = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 255, 60, 132))
$outline = New-Object System.Drawing.Pen -ArgumentList @([System.Drawing.Color]::White, 2)
$plusPen = New-Object System.Drawing.Pen -ArgumentList @([System.Drawing.Color]::FromArgb(255, 255, 60, 132), 2.5)
$plusPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$plusPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round

# Rounded badge in the bottom-right corner.
$cx = 101; $cy = 101; $d = 52
$g.FillEllipse($shadow, ($cx - 26 + 2), ($cy - 26 + 3), $d, $d)
$g.FillEllipse($dark, ($cx - 26), ($cy - 26), $d, $d)
$g.DrawEllipse($outline, ($cx - 26), ($cy - 26), $d, $d)

# Two listeners.
foreach ($dx in @(0, 20)) {
    $g.FillEllipse($white, (86 + $dx), 85, 10, 10)      # head
    $g.FillEllipse($white, (84 + $dx), 97, 15, 11)      # shoulders
    $g.FillRectangle($white, (86 + $dx), 102, 11, 10)   # torso
}

# Link between them.
$g.DrawLine($plusPen, 97, 92, 104, 92)
$g.DrawLine($plusPen, 100.5, 88.5, 100.5, 95.5)

# Label.
$font = New-Object System.Drawing.Font('Arial', 8, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$fmt = New-Object System.Drawing.StringFormat
$fmt.Alignment = [System.Drawing.StringAlignment]::Center
$fmt.LineAlignment = [System.Drawing.StringAlignment]::Center
$g.DrawString('CO-OP', $font, $white, (New-Object System.Drawing.RectangleF(75, 112, 52, 13)), $fmt)

$bmp.Save($Output, [System.Drawing.Imaging.ImageFormat]::Png)

$font.Dispose(); $fmt.Dispose(); $src = $null
$g.Dispose(); $bmp.Dispose()
Write-Host "Saved $Output"
