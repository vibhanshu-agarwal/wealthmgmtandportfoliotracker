$InstallDir = "$env:USERPROFILE\bin"
$JunieExe = Join-Path $InstallDir "junie.exe"

# Create folder if missing
if (-not (Test-Path $InstallDir))
{
    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
}

# Get latest release info from GitHub
$Release = Invoke-RestMethod "https://api.github.com/repos/JetBrains/junie/releases/latest"

# Find the Windows binary asset
$Asset = $Release.assets | Where-Object { $_.name -match "windows-amd64" }

if (-not $Asset)
{
    Write-Host "Could not find a Windows binary in the latest Junie release."
    exit 1
}

# Download the binary
Invoke-WebRequest -Uri $Asset.browser_download_url -OutFile $JunieExe

# Unblock the file
Unblock-File $JunieExe

# Add to PATH if missing
$UserPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($UserPath -notlike "*$InstallDir*")
{
    [Environment]::SetEnvironmentVariable("Path", "$UserPath;$InstallDir", "User")
    Write-Host "`n[Junie] Added $InstallDir to PATH. Restart your terminal.`n"
}

Write-Host "[Junie] Installed successfully at $JunieExe"
Write-Host "[Junie] Run 'junie --version' after restarting your terminal."
