#!/bin/bash
# Setup GitHub CLI for Claude Code on the web
# Only runs in remote cloud environments — skipped locally.
if [ "$CLAUDE_CODE_REMOTE" != "true" ]; then
  exit 0
fi
# Skip if gh is already installed and authenticated
if command -v gh &>/dev/null && gh auth status &>/dev/null 2>&1; then
  exit 0
fi
# Install gh CLI if not present
if ! command -v gh &>/dev/null; then
  apt-get update -qq && apt-get install -y -qq gh 2>/dev/null || {
    echo "Warning: Failed to install gh CLI" >&2
    exit 0  # Non-fatal — don't block the session
  }
fi
# Authenticate using GITHUB_TOKEN from the cloud environment config.
# The token is set in the Claude Code web environment settings (not in this repo).
if [ -n "$GITHUB_TOKEN" ]; then
  echo "$GITHUB_TOKEN" | gh auth login --with-token 2>/dev/null || {
    echo "Warning: gh auth login failed" >&2
    exit 0
  }
  gh auth setup-git 2>/dev/null
else
  echo "Note: GITHUB_TOKEN not set — gh CLI will not be authenticated." >&2
  echo "Set GITHUB_TOKEN in your Claude Code cloud environment settings." >&2
fi
exit 0
