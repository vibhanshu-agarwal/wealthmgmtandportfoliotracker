@echo off
cd /d D:\Projects\Development\Java\Spring\wealthmgmtandportfoliotracker
git status
git add build.gradle
git commit -m "Fix vulnerable jackson-core dependency to v2.18.2

- Upgraded jackson-core, jackson-databind, and jackson-annotations to version 2.18.2
- Addresses CVE vulnerabilities in jackson-core through explicit dependency management
- Applied at root project level to ensure all services use the patched version

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
git push
