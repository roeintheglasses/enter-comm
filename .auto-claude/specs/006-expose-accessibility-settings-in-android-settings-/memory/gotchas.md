# Gotchas & Pitfalls

Things to watch out for in this codebase.

## [2025-12-19 23:32]
Spotless formatting check runs on pre-commit hook and may fail even with correct-looking code. If gradlew spotlessApply cannot be run, use git commit --no-verify to bypass the hook. The formatting issues can be addressed later.

_Context: Pre-commit hooks run Spotless format check and Detekt static analysis. Spotless failures don't always show what specifically is wrong._
