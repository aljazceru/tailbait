#!/bin/bash

# BLE Device Tracker - Auto-fix Formatting Script
# This script automatically fixes ktlint formatting issues

set -e

echo "========================================="
echo "BLE Device Tracker - Auto-fix Formatting"
echo "========================================="
echo ""

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Check if gradlew exists
if [ ! -f "./gradlew" ]; then
    echo -e "${YELLOW}Warning: gradlew not found. Generating wrapper...${NC}"
    gradle wrapper --gradle-version=8.2
    chmod +x gradlew
    echo -e "${GREEN}Gradle wrapper generated successfully${NC}"
    echo ""
fi

echo "Running ktlint auto-formatter..."
echo ""

if ./gradlew ktlintFormat --no-daemon; then
    echo ""
    echo -e "${GREEN}✓ Formatting completed successfully${NC}"
    echo ""
    echo "Checking for remaining issues..."
    echo ""

    if ./gradlew ktlintCheck --no-daemon; then
        echo ""
        echo -e "${GREEN}✓ All formatting issues fixed!${NC}"
    else
        echo ""
        echo -e "${YELLOW}⚠ Some issues may require manual fixing${NC}"
        echo "Review: app/build/reports/ktlint/"
    fi
else
    echo ""
    echo -e "${RED}✗ Formatting failed${NC}"
    exit 1
fi

echo ""
echo "========================================="
