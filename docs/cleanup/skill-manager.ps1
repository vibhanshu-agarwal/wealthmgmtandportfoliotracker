<#
.SYNOPSIS
    Unified agent skill discovery and installation manager.

.DESCRIPTION
    Wraps npx skills (skills.sh registry) and gh/git (GitHub) to discover,
    install, list, update, and remove agent skills.

    Single source of truth: $HOME\.agents\skills
    All agent <agent>\skills folders are junctions to this directory (set up by
    sync-agent-skills.ps1). Installing once makes the skill available everywhere.

.PARAMETER Mode
    search   - Find skills in the registry or on GitHub
    install  - Install a skill globally
    list     - List installed skills
    update   - Update installed skills
    remove   - Remove an installed skill
    preview  - List available skills in a package without installing

.PARAMETER Source
    npx   - Use skills.sh registry (npx skills)
    gh    - Use GitHub CLI + git (direct repo install)
    both  - Use both sources (default; applies to search only)

.PARAMETER Query      Search term (Mode=search)
.PARAMETER Package    Package identifier:
                        npx: owner/repo or owner/repo@skill-name
                        gh:  owner/repo, owner/repo@branch, or full HTTPS URL
.PARAMETER SkillName  Override the local folder name for gh installs.
.PARAMETER SubPath    Sub-directory within the GitHub repo containing the skill.
                      Uses git sparse-checkout; no full clone needed.
                      Example: -SubPath "skills/redis-development"
.PARAMETER SkillsRoot Override the skills root. Default: $HOME\.agents\skills
.PARAMETER Limit      Max results for search (default: 20)
.PARAMETER Yes        Skip all confirmation prompts.
.PARAMETER DryRun     Print what would happen without executing.

.EXAMPLE
    # Search skills.sh registry (interactive)
    .\skill-manager.ps1 search

    # Search registry by keyword
    .\skill-manager.ps1 search -Query "typescript testing"

    # Search GitHub only
    .\skill-manager.ps1 search -Source gh -Query "react"

    # Preview skills available in a repo before installing
    .\skill-manager.ps1 preview -Package "vercel-labs/agent-skills"

    # Install from skills.sh registry (global, all agents)
    .\skill-manager.ps1 install -Source npx -Package "vercel-labs/agent-skills" -Yes

    # Install a single named skill from a registry repo
    .\skill-manager.ps1 install -Source npx -Package "vercel-labs/agent-skills@react-best-practices" -Yes

    # Install a GitHub repo as a skill (direct clone into ~/.agents/skills)
    .\skill-manager.ps1 install -Source gh -Package "owner/my-skill-repo"

    # Install one skill from a monorepo using sparse checkout
    .\skill-manager.ps1 install -Source gh -Package "redis/agent-skills" -SubPath "skills/redis-development" -SkillName "redis-development"

    # List installed skills
    .\skill-manager.ps1 list

    # Update all global skills
    .\skill-manager.ps1 update -Yes

    # Remove a skill
    .\skill-manager.ps1 remove -Package "react-best-practices" -Yes

    # Dry-run any operation
    .\skill-manager.ps1 install -Source gh -Package "owner/repo" -DryRun
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('search', 'install', 'list', 'update', 'remove', 'preview')]
    [string]$Mode,

    [ValidateSet('npx', 'gh', 'both')]
    [string]$Source = 'both',

    [string]$Query,
    [string]$Package,
    [string]$SkillName,
    [string]$SubPath,
    [string]$SkillsRoot = (Join-Path $HOME '.agents\skills'),
    [int]$Limit = 20,
    [switch]$Yes,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

# ─── Helpers ─────────────────────────────────────────────────────────────────

function Write-Step {
    param([string]$Message)
    Write-Host "`n=> $Message" -ForegroundColor Cyan
}

function Write-Done {
    param([string]$Message)
    Write-Host $Message -ForegroundColor Green
}

function Assert-Tool {
    param([string]$Name, [string]$HelpUrl = '')
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        $hint = if ($HelpUrl) { " Install from: $HelpUrl" } else { '' }
        throw "Required tool '$Name' not found.$hint"
    }
}

function Invoke-Step {
    param(
        [string]$Description,
        [scriptblock]$Action
    )
    if ($DryRun) {
        Write-Host "[DRY RUN] $Description" -ForegroundColor Yellow
    } else {
        Write-Host "  $Description" -ForegroundColor DarkGray
        & $Action
    }
}

function Backup-IfExists {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path) {
        $backup = "$Path.bak-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
        Invoke-Step "Backing up existing path: $Path -> $backup" {
            Move-Item -LiteralPath $Path -Destination $backup -Force
        }
    }
}

function Assert-Package {
    if (-not $Package) {
        throw "The -Package argument is required for Mode='$Mode'."
    }
}

# ─── Mode: SEARCH ─────────────────────────────────────────────────────────────

function Search-SkillsRegistry {
    Assert-Tool 'npx' 'https://nodejs.org'
    $label = if ($Query) { ': ' + $Query } else { ' (interactive)' }
    Write-Step ('Searching skills.sh registry' + $label)
    Write-Host "  Registry: https://skills.sh/" -ForegroundColor DarkGray
    Write-Host ""

    if ($Query) {
        npx skills find $Query
    } else {
        npx skills find
    }
}

function Search-GitHub {
    Assert-Tool 'gh' 'https://cli.github.com'
    $label = if ($Query) { ': ' + $Query } else { '' }
    Write-Step ('Searching GitHub for agent skill repos' + $label)

    $topics     = @('agent-skill', 'cursor-skill', 'claude-skill', 'coding-agent-skill')
    $allResults = [System.Collections.Generic.List[object]]::new()

    foreach ($topic in $topics) {
        try {
            $raw = gh search repos --topic $topic --sort stars --limit $Limit `
                --json fullName,stargazersCount,description,url,topics 2>$null
            if ($raw) {
                ($raw | ConvertFrom-Json) | ForEach-Object { $allResults.Add($_) }
            }
        } catch { }
    }

    if ($Query) {
        try {
            $raw = gh search repos $Query --sort stars --limit $Limit `
                --json fullName,stargazersCount,description,url,topics 2>$null
            if ($raw) {
                ($raw | ConvertFrom-Json) | ForEach-Object { $allResults.Add($_) }
            }
        } catch { }
    }

    $seen    = [System.Collections.Generic.HashSet[string]]::new()
    $deduped = $allResults |
        Where-Object { $seen.Add($_.fullName) } |
        Sort-Object stargazersCount -Descending |
        Select-Object -First $Limit

    if (-not $deduped) {
        Write-Host "  No GitHub results found." -ForegroundColor DarkGray
        return
    }

    $deduped | ForEach-Object {
        [pscustomobject]@{
            Repo        = $_.fullName
            Stars       = $_.stargazersCount
            Description = if ($_.description) { $_.description.Substring(0, [Math]::Min(60, $_.description.Length)) } else { '-' }
            Topics      = ($_.topics -join ', ')
        }
    } | Format-Table -AutoSize

    Write-Host "  Install: .\skill-manager.ps1 install -Source gh -Package <owner/repo>" -ForegroundColor DarkGray
}

# ─── Mode: PREVIEW ───────────────────────────────────────────────────────────

function Invoke-Preview {
    Assert-Package
    Assert-Tool 'npx' 'https://nodejs.org'
    Write-Step "Listing skills available in: $Package (not installing)"
    npx skills add $Package --list
}

# ─── Mode: INSTALL ───────────────────────────────────────────────────────────

function Install-FromNpx {
    Assert-Package
    Assert-Tool 'npx' 'https://nodejs.org'
    Write-Step "Installing via skills.sh: $Package"
    Write-Host "  Skills root (via junction): $SkillsRoot" -ForegroundColor DarkGray

    # Use -g (user-level global) so the CLI installs to ~/.agents/skills rather
    # than the project-level .agents/skills (which the CLI would choose when it
    # detects a skills-lock.json in the current working directory).
    # NOTE: `npx skills add -g` installs to the user skills root (~/.agents/skills),
    # NOT to %APPDATA%\npm\node_modules — those are different conventions.
    $stepDesc = 'npx skills add ' + $Package + ' -g' + $(if ($Yes) { ' -y' } else { '' })
    Invoke-Step $stepDesc {
        $cmd = [System.Collections.Generic.List[string]]@('skills', 'add', $Package, '-g')
        if ($Yes) { $cmd.Add('-y') }
        npx @cmd
    }

    if (-not $DryRun) {
        Write-Done "Installed. Available to all agents via $SkillsRoot"
    }
}

function Install-FromGitHub {
    Assert-Package
    Assert-Tool 'git' 'https://git-scm.com'

    $script:repoBranch = $null
    $repoUrl = if ($Package -match '^https?://') {
        $Package
    } elseif ($Package -match '^([^@]+)@(.+)$') {
        $script:repoBranch = $Matches[2]
        'https://github.com/' + $Matches[1]
    } else {
        'https://github.com/' + $Package
    }

    $derivedName = if ($SkillName) {
        $SkillName
    } elseif ($SubPath) {
        Split-Path $SubPath -Leaf
    } else {
        ($Package -split '[/@]')[1] -replace '\.git$', ''
    }

    $destPath = Join-Path $SkillsRoot $derivedName

    Write-Step "Installing GitHub skill '$derivedName'"
    $sourceLabel = $repoUrl + $(if ($SubPath) { ' [' + $SubPath + ']' } else { '' })
    Write-Host "  Source : $sourceLabel"
    Write-Host "  Dest   : $destPath" -ForegroundColor DarkGray

    Backup-IfExists -Path $destPath

    if ($SubPath) {
        $tempDir  = Join-Path $env:TEMP ('skill-install-' + (Get-Random))
        $cloneDesc = 'Sparse-checkout [' + $SubPath + '] from ' + $repoUrl + ' -> ' + $destPath
        Invoke-Step $cloneDesc {
            git clone --filter=blob:none --no-checkout --depth 1 $repoUrl $tempDir
            git -C $tempDir sparse-checkout init --cone
            git -C $tempDir sparse-checkout set $SubPath
            git -C $tempDir checkout

            $sourcePath = Join-Path $tempDir $SubPath
            if (-not (Test-Path -LiteralPath $sourcePath)) {
                Remove-Item $tempDir -Recurse -Force
                throw "SubPath '$SubPath' was not found in the repo after sparse checkout."
            }
            Move-Item -LiteralPath $sourcePath -Destination $destPath -Force
            Remove-Item $tempDir -Recurse -Force
        }
    } else {
        $branchSuffix = if ($script:repoBranch) { '@' + $script:repoBranch } else { '' }
        $cloneDesc    = 'Clone ' + $repoUrl + $branchSuffix + ' -> ' + $destPath
        Invoke-Step $cloneDesc {
            if ($script:repoBranch) {
                git clone --depth 1 --branch $script:repoBranch $repoUrl $destPath
            } else {
                git clone --depth 1 $repoUrl $destPath
            }
            $dotGit = Join-Path $destPath '.git'
            if (Test-Path -LiteralPath $dotGit) {
                Remove-Item $dotGit -Recurse -Force
            }
        }
    }

    if (-not $DryRun) {
        $skillMd = Get-ChildItem -Path $destPath -Filter 'SKILL.md' -Recurse -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($skillMd) {
            Write-Done "Installed: $destPath"
            Write-Done "SKILL.md : $($skillMd.FullName)"
        } else {
            Write-Warning "SKILL.md not found in $destPath - this may not be a valid skill."
        }
        Write-Done "Available to all agents via junctions -> $SkillsRoot"
    }
}

# ─── Mode: LIST ──────────────────────────────────────────────────────────────

function Invoke-List {
    Write-Step "Installed skills in $SkillsRoot"

    if (-not (Test-Path -LiteralPath $SkillsRoot)) {
        Write-Host "  Skills root not found: $SkillsRoot" -ForegroundColor Red
        Write-Host "  Run sync-agent-skills.ps1 first." -ForegroundColor DarkGray
        return
    }

    $skills = Get-ChildItem -Path $SkillsRoot -Directory |
        Where-Object { -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) }

    if (-not $skills) {
        Write-Host "  No skills installed yet." -ForegroundColor DarkGray
    } else {
        $skills | ForEach-Object {
            $dir     = $_
            $skillMd = Join-Path $dir.FullName 'SKILL.md'
            $hasFile = Test-Path -LiteralPath $skillMd

            $description = '-'
            if ($hasFile) {
                # Get-Content handles UTF-8 BOM and encoding variations that
                # Select-String -Path can misread. The capture group extracts the
                # value cleanly; \x27 is single-quote in the strip regex.
                $content = Get-Content -LiteralPath $skillMd -ErrorAction SilentlyContinue
                $match   = $content | Select-String -Pattern '^description:\s*(.*)' |
                    Select-Object -First 1
                if ($match) {
                    $description = $match.Matches.Groups[1].Value.Trim() -replace '["\x27]', ''
                }
            }

            [pscustomobject]@{
                Skill       = $dir.Name
                HasSkillMd  = if ($hasFile) { 'Yes' } else { 'No' }
                Description = if ($description.Length -gt 70) { $description.Substring(0, 70) + '...' } else { $description }
                Modified    = $dir.LastWriteTime.ToString('yyyy-MM-dd')
            }
        } | Format-Table -AutoSize
    }

    Write-Host ""
    Write-Host "  npx registry view:" -ForegroundColor DarkGray
    if (Get-Command npx -ErrorAction SilentlyContinue) {
        npx skills ls -g 2>$null
    }
}

# ─── Mode: UPDATE ────────────────────────────────────────────────────────────

function Invoke-Update {
    Assert-Tool 'npx' 'https://nodejs.org'
    $target = if ($Package) { ' skill: ' + $Package } else { ' all global skills' }
    Write-Step ('Updating' + $target)

    # Use -g (user-level global) for the same reason as Install-FromNpx.
    $pkgPart  = if ($Package) { ' ' + $Package } else { '' }
    $yesPart  = if ($Yes) { ' -y' } else { '' }
    $stepDesc = 'npx skills update' + $pkgPart + ' -g' + $yesPart
    Invoke-Step $stepDesc {
        $cmd = [System.Collections.Generic.List[string]]@('skills', 'update')
        if ($Package) { $cmd.Add($Package) }
        $cmd.Add('-g')
        if ($Yes) { $cmd.Add('-y') }
        npx @cmd
    }
}

# ─── Mode: REMOVE ────────────────────────────────────────────────────────────

function Invoke-Remove {
    Assert-Tool 'npx' 'https://nodejs.org'
    $target = if ($Package) { ' skill: ' + $Package } else { ' (interactive)' }
    Write-Step ('Removing' + $target)

    # Use -g (user-level global) for the same reason as Install-FromNpx.
    $pkgPart  = if ($Package) { ' ' + $Package } else { '' }
    $yesPart  = if ($Yes) { ' -y' } else { '' }
    $stepDesc = 'npx skills remove' + $pkgPart + ' -g' + $yesPart
    Invoke-Step $stepDesc {
        $cmd = [System.Collections.Generic.List[string]]@('skills', 'remove')
        if ($Package) { $cmd.Add($Package) }
        $cmd.Add('-g')
        if ($Yes) { $cmd.Add('-y') }
        npx @cmd
    }
}

# ─── Validation ──────────────────────────────────────────────────────────────

if ($Mode -eq 'install' -and $Source -eq 'both') {
    throw "For 'install' mode, specify -Source npx or -Source gh explicitly."
}

if ($DryRun) {
    Write-Host "[DRY RUN MODE - no changes will be made]`n" -ForegroundColor Yellow
}

# ─── Dispatch ────────────────────────────────────────────────────────────────

switch ($Mode) {
    'search'  {
        if ($Source -in 'npx', 'both') { Search-SkillsRegistry }
        if ($Source -in 'gh',  'both') { Search-GitHub }
    }
    'preview' { Invoke-Preview }
    'install' {
        if ($Source -eq 'npx') { Install-FromNpx }
        if ($Source -eq 'gh')  { Install-FromGitHub }
    }
    'list'    { Invoke-List }
    'update'  { Invoke-Update }
    'remove'  { Invoke-Remove }
}
