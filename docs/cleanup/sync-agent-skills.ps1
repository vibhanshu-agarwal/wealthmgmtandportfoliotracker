[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [switch]$UseSymbolicLink,
    [switch]$IncludeJunie
)

$ErrorActionPreference = 'Stop'

$homeDir      = $HOME
$sourceSkills = Join-Path $homeDir '.agents\skills'
$timestamp    = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupRoot   = Join-Path $homeDir ".agents\skills-backups\$timestamp"

$agentFolders = @(
    '.claude', '.cursor', '.kiro', '.copilot', '.kilocode', '.pi', '.qoder',
    '.openhands', '.qwen', '.roo', '.gemini', '.zencoder', '.windsurf',
    '.pochi', '.neovate', '.adal', '.vibe', '.trae', '.mux',
    '.codebuddy', '.commandcode', '.continue', '.cortex', '.crush',
    '.factory', '.goose', '.iflow', '.kode', '.mcpjam'
)

if ($IncludeJunie) {
    $agentFolders += '.junie'
}

$agentFolders = $agentFolders | Sort-Object -Unique
$linkType = if ($UseSymbolicLink) { 'SymbolicLink' } else { 'Junction' }

function Ensure-Directory {
    param([Parameter(Mandatory = $true)][string]$PathToEnsure)
    if (-not (Test-Path -LiteralPath $PathToEnsure)) {
        if ($PSCmdlet.ShouldProcess($PathToEnsure, 'Create directory')) {
            New-Item -ItemType Directory -Path $PathToEnsure -Force | Out-Null
        }
    }
}

function Is-ReparsePoint {
    param([Parameter(Mandatory = $true)][System.IO.FileSystemInfo]$Item)
    return (($Item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)
}

if (-not (Test-Path -LiteralPath $sourceSkills)) {
    Write-Host "Source skills directory not found. Creating: $sourceSkills" -ForegroundColor Yellow
    if ($PSCmdlet.ShouldProcess($sourceSkills, 'Create source skills directory')) {
        New-Item -ItemType Directory -Path $sourceSkills -Force | Out-Null
    }
}

if ($UseSymbolicLink) {
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).
        IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    if (-not $isAdmin) {
        Write-Warning "Creating Symbolic Links may require Administrator privileges or Windows Developer Mode."
    }
}

Write-Host "Source skills directory: $sourceSkills"
Write-Host "Link type: $linkType"
Write-Host "Backup root (for non-link existing skills): $backupRoot"
Write-Host ""

foreach ($agent in $agentFolders) {
    $agentRoot    = Join-Path $homeDir $agent
    $targetSkills = Join-Path $agentRoot 'skills'
    $backupPath   = Join-Path (Join-Path $backupRoot $agent.TrimStart('.')) 'skills'

    Ensure-Directory -PathToEnsure $agentRoot

    if (Test-Path -LiteralPath $targetSkills) {
        $item = Get-Item -LiteralPath $targetSkills -Force

        if (Is-ReparsePoint -Item $item) {
            if ($PSCmdlet.ShouldProcess($targetSkills, 'Remove existing reparse point')) {
                Remove-Item -LiteralPath $targetSkills -Force
                Write-Host "[$agent] Removed existing link/reparse-point at $targetSkills"
            }
        }
        else {
            Ensure-Directory -PathToEnsure (Split-Path -Parent $backupPath)
            if ($PSCmdlet.ShouldProcess($targetSkills, "Backup to $backupPath")) {
                Move-Item -LiteralPath $targetSkills -Destination $backupPath -Force
                Write-Host "[$agent] Backed up existing non-link skills to $backupPath"
            }
        }
    }

    if ($PSCmdlet.ShouldProcess($targetSkills, "Create $linkType -> $sourceSkills")) {
        New-Item -ItemType $linkType -Path $targetSkills -Target $sourceSkills | Out-Null
        Write-Host "[$agent] Linked $targetSkills -> $sourceSkills ($linkType)"
    }
}

Write-Host ""
Write-Host "Verification:"

$agentFolders | ForEach-Object {
    $agent = $_
    $p = Join-Path (Join-Path $homeDir $agent) 'skills'

    if (-not (Test-Path -LiteralPath $p)) {
        [pscustomobject]@{
            AgentFolder = $agent
            FullName    = $p
            LinkType    = '<missing>'
            Target      = $null
            Notes       = 'Path not found'
        }
        return
    }

    $it = Get-Item -LiteralPath $p -Force
    $isRp = Is-ReparsePoint -Item $it

    $targetValue = $null
    if ($it.PSObject.Properties.Name -contains 'Target' -and $it.Target) {
        $targetValue = ($it.Target -join '; ')
    }

    $notes = ''
    if ($isRp -and [string]::IsNullOrWhiteSpace($targetValue)) {
        $notes = 'Reparse point detected; target may be unavailable in Windows PowerShell 5.1.'
    }

    [pscustomobject]@{
        AgentFolder = $agent
        FullName    = $it.FullName
        LinkType    = if ($it.PSObject.Properties.Name -contains 'LinkType' -and $it.LinkType) { $it.LinkType } elseif ($isRp) { 'ReparsePoint' } else { '<not-link>' }
        Target      = $targetValue
        Notes       = $notes
    }
} | Format-Table -AutoSize
