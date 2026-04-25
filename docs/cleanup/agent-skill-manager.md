# Agent Skill Manager (Windows PowerShell)

Unified wrapper for discovering, installing, updating, and removing agent skills globally
so that one install makes a skill available to every coding agent on the machine.

- Source of truth: `~\.agents\skills`
- All agent `<agent>\skills` folders are junctions to this directory (set up by `sync-agent-skills.ps1`)
- Two install sources supported: **skills.sh registry** (`npx skills`) and **GitHub** (`git`)
- Supports `-DryRun` for safe preview of any operation

## Prerequisites

| Tool | Required for | Install |
|------|-------------|---------|
| Node.js / npx | `search`, `install -Source npx`, `list`, `update`, `remove`, `preview` | https://nodejs.org |
| Git | `install -Source gh` | https://git-scm.com |
| GitHub CLI (`gh`) | `search -Source gh` | https://cli.github.com |

## Script (`skill-manager.ps1`)

```powershell
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
```

## Modes & Sources

| Mode | `-Source npx` | `-Source gh` | `-Source both` |
|------|--------------|-------------|----------------|
| `search` | `npx skills find` (skills.sh registry) | `gh search repos` (4 GitHub topics) | Both (default) |
| `preview` | `npx skills add --list` (no install) | — | — |
| `install` | `npx skills add -g` | `git clone` / sparse-checkout into `$SkillsRoot` | Not allowed — must pick one |
| `list` | `npx skills ls -g` + local dir scan | — | — |
| `update` | `npx skills update -g` | — | — |
| `remove` | `npx skills remove -g` | — | — |

## Recommended Usage

```powershell
# 1. Browse the registry interactively
.\skill-manager.ps1 search

# 2. Search by keyword (registry only)
.\skill-manager.ps1 search -Query "typescript testing"

# 3. Search GitHub for tagged skill repos
.\skill-manager.ps1 search -Source gh -Query "react"

# 4. Preview what skills are in a package before installing
.\skill-manager.ps1 preview -Package "vercel-labs/agent-skills"

# 5. Install a specific skill from the registry (global, all agents, no prompt)
.\skill-manager.ps1 install -Source npx -Package "vercel-labs/agent-skills@vercel-react-best-practices" -Yes

# 6. Install an entire skill repo from GitHub
.\skill-manager.ps1 install -Source gh -Package "owner/my-skill-repo"

# 7. Install one skill from a GitHub monorepo (sparse checkout — no full clone)
.\skill-manager.ps1 install -Source gh -Package "redis/agent-skills" -SubPath "skills/redis-development" -SkillName "redis-development"

# 8. List all installed global skills with descriptions
.\skill-manager.ps1 list

# 9. Update all global skills
.\skill-manager.ps1 update -Yes

# 10. Update a single skill
.\skill-manager.ps1 update -Package "vercel-react-best-practices" -Yes

# 11. Remove a skill
.\skill-manager.ps1 remove -Package "web-design-guidelines" -Yes

# 12. Dry-run any operation to preview without executing
.\skill-manager.ps1 install -Source gh -Package "owner/repo" -DryRun
```

## Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `Mode` | string (required) | — | `search`, `install`, `list`, `update`, `remove`, `preview` |
| `Source` | string | `both` | `npx`, `gh`, or `both` (search only) |
| `Query` | string | — | Keyword for `search` mode |
| `Package` | string | — | `owner/repo`, `owner/repo@skill`, or HTTPS URL |
| `SkillName` | string | — | Override destination folder name (gh installs) |
| `SubPath` | string | — | Subdirectory within a GitHub repo to install as the skill |
| `SkillsRoot` | string | `~\.agents\skills` | Override skills root directory |
| `Limit` | int | `20` | Max results for `search` |
| `Yes` | switch | off | Skip all confirmation prompts |
| `DryRun` | switch | off | Print all steps without executing |

## Notes

### Single source of truth

All agent `skills` folders under `$HOME` are Windows Junctions pointing to `~\.agents\skills`
(set up by `sync-agent-skills.ps1`). Installing a skill once — by either source — makes it
immediately visible to every configured agent without any copying or symlinking.

### Why `-g` and not `--agent cursor`

`npx skills add -g` installs to the user-level skills root (`~\.agents\skills`), which is the
correct target. This is distinct from `npm install -g` which routes to `%APPDATA%\npm\node_modules`.

Using `--agent cursor` without `-g` installs at **project scope**: the `npx skills` CLI detects
`skills-lock.json` in the current working directory and installs into the project's `.agents\skills`
folder instead of the home-directory root.

### GitHub sparse checkout

When a skill lives in a subdirectory of a larger repo (e.g. `redis/agent-skills` contains
`skills/redis-development`), use `-SubPath` to fetch only that folder via `git sparse-checkout`.
No full repo clone is performed — only the objects needed for that path are downloaded.

### Backups

For `install -Source gh`, if a directory already exists at the destination it is moved to a
timestamped `.bak-<timestamp>` path before the new install proceeds. Registry (`npx`) installs
handle their own update/overwrite logic.

### Validating SKILL.md

After a `gh` install, the script checks for `SKILL.md` within the installed directory and warns
if it is absent. Skills without a `SKILL.md` may not be discoverable by agent runtimes.

### Description parsing

`Invoke-List` reads `SKILL.md` via `Get-Content` (not `Select-String -Path`) to handle
UTF-8 BOM and encoding variations. The `description:` field value is extracted using a regex
capture group and surrounding YAML quotes are stripped.
