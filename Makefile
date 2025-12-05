# Enter-Comm Makefile
# Simplifies common development commands

.PHONY: help build clean test lint format check install run debug release hooks tag-release

# Default target
help:
	@echo "Enter-Comm Development Commands"
	@echo ""
	@echo "Build:"
	@echo "  make build        - Build debug APK"
	@echo "  make release      - Build release APK"
	@echo "  make clean        - Clean build artifacts"
	@echo ""
	@echo "Testing:"
	@echo "  make test         - Run unit tests"
	@echo "  make test-report  - Run tests with HTML report"
	@echo ""
	@echo "Code Quality:"
	@echo "  make lint         - Run Android Lint"
	@echo "  make detekt       - Run Detekt static analysis"
	@echo "  make format       - Auto-format code with Spotless"
	@echo "  make format-check - Check code formatting"
	@echo "  make check        - Run all checks (format, detekt, lint, test)"
	@echo ""
	@echo "Device:"
	@echo "  make install      - Install debug APK on device"
	@echo "  make run          - Install and launch app"
	@echo "  make logs         - Show app logs (filtered)"
	@echo "  make logs-all     - Show all app logs"
	@echo ""
	@echo "Release:"
	@echo "  make tag-release VERSION=v1.0.0  - Create and push release tag"
	@echo "  make changelog                    - Preview changelog"
	@echo ""
	@echo "Setup:"
	@echo "  make hooks        - Install git pre-commit hooks"
	@echo "  make deps         - Download dependencies"

# Build commands
build:
	./gradlew assembleDebug

release:
	./gradlew assembleRelease

clean:
	./gradlew clean

# Testing
test:
	./gradlew test

test-report:
	./gradlew test --info
	@echo "Test report: app/build/reports/tests/testDebugUnitTest/index.html"

# Code quality
lint:
	./gradlew lintDebug

detekt:
	./gradlew detekt

format:
	./gradlew spotlessApply

format-check:
	./gradlew spotlessCheck

check: format-check detekt lint test
	@echo "All checks passed!"

# Device commands
install:
	./gradlew installDebug

run:
	./gradlew installDebug
	adb shell am start -n com.entercomm.bikeintercom/.MainActivity

logs:
	adb logcat -c && adb logcat "EnterComm:*" "*:S"

logs-all:
	adb logcat -c && adb logcat | grep -E "EnterComm|bikeintercom"

# Setup
hooks:
	./scripts/install-hooks.sh

deps:
	./gradlew dependencies

# CI simulation (runs what GitHub Actions would run)
ci: clean format-check detekt test lint build
	@echo "CI simulation complete!"

# Release commands
tag-release:
ifndef VERSION
	$(error VERSION is required. Usage: make tag-release VERSION=v1.0.0)
endif
	@echo "Creating release tag $(VERSION)..."
	@git diff --quiet || (echo "Error: Working directory has uncommitted changes" && exit 1)
	git tag -a $(VERSION) -m "Release $(VERSION)"
	git push origin $(VERSION)
	@echo "Tag $(VERSION) pushed. GitHub Actions will create the release."

changelog:
	@echo "## Changelog Preview"
	@echo ""
	@PREVIOUS_TAG=$$(git describe --tags --abbrev=0 2>/dev/null || echo ""); \
	if [ -n "$$PREVIOUS_TAG" ]; then \
		echo "Changes since $$PREVIOUS_TAG:"; \
		echo ""; \
		git log --oneline $$PREVIOUS_TAG..HEAD --format="- %s"; \
	else \
		echo "No previous tags found. Showing recent commits:"; \
		git log --oneline -20 --format="- %s"; \
	fi
