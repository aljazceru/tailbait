#!/bin/bash

# BLE Device Tracker - Code Quality Linter Script
# This script runs ktlint and detekt to ensure code quality

set -e

echo "========================================="
echo "BLE Device Tracker - Code Quality Check"
echo "========================================="
echo ""

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if gradlew exists
if [ ! -f "./gradlew" ]; then
    echo -e "${YELLOW}Warning: gradlew not found. Generating wrapper...${NC}"
    gradle wrapper --gradle-version=8.2
    chmod +x gradlew
    echo -e "${GREEN}Gradle wrapper generated successfully${NC}"
    echo ""
fi

# Function to run a command and check result
run_check() {
    local name=$1
    local command=$2

    echo "----------------------------------------"
    echo "Running: $name"
    echo "----------------------------------------"

    if eval "$command"; then
        echo -e "${GREEN}✓ $name passed${NC}"
        return 0
    else
        echo -e "${RED}✗ $name failed${NC}"
        return 1
    fi
}

# Track overall status
overall_status=0

# Run ktlint check
echo ""
if run_check "ktlint (Kotlin style check)" "./gradlew ktlintCheck --no-daemon"; then
    :
else
    overall_status=1
    echo -e "${YELLOW}Hint: Run './gradlew ktlintFormat' to auto-fix formatting issues${NC}"
fi

echo ""
echo ""

# Run detekt
if run_check "detekt (Static code analysis)" "./gradlew detekt --no-daemon"; then
    :
else
    overall_status=1
    echo -e "${YELLOW}Hint: Review detekt report at build/reports/detekt/detekt.html${NC}"
fi

echo ""
echo "========================================="
if [ $overall_status -eq 0 ]; then
    echo -e "${GREEN}✓ All code quality checks passed!${NC}"
else
    echo -e "${RED}✗ Some code quality checks failed${NC}"
fi
echo "========================================="

echo ""
echo "Reports generated:"
echo "  - ktlint: app/build/reports/ktlint/"
echo "  - detekt: build/reports/detekt/"
echo ""

exit $overall_status
