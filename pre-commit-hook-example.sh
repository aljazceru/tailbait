#!/bin/bash

# BLE Device Tracker - Pre-commit Hook Example
#
# This is an example pre-commit hook that runs ktlint formatting
# before allowing a commit to proceed.
#
# To install:
#   cp pre-commit-hook-example.sh .git/hooks/pre-commit
#   chmod +x .git/hooks/pre-commit
#
# To bypass (use sparingly):
#   git commit --no-verify

echo "Running pre-commit checks..."

# Run ktlint format on staged Kotlin files
STAGED_KT_FILES=$(git diff --cached --name-only --diff-filter=ACMR | grep "\.kt$" | grep "^app/src/main")

if [ -n "$STAGED_KT_FILES" ]; then
    echo "Formatting staged Kotlin files..."

    # Run ktlint format
    if ./gradlew ktlintFormat --no-daemon; then
        echo "✓ Formatting completed"

        # Re-add formatted files
        echo "$STAGED_KT_FILES" | xargs git add

        echo "✓ Pre-commit checks passed"
        exit 0
    else
        echo "✗ ktlint formatting failed"
        echo "Please fix the issues and try again"
        exit 1
    fi
else
    echo "No Kotlin files to check"
    exit 0
fi
