# AiChatBuddy — MCP client fork

This fork adds generic MCP tools directly to AiChatBuddy. The Google AI Studio / Gemini provider and the existing character prompt remain in AiChatBuddy; no model gateway is required.

**Build target:** Java 21, Paper API 1.21.1, intended for the existing Youer 1.21.1 deployment.

- [日本語導入・設定・制限](docs/MCP.md)
- [BlueMap設定例（既存configへ統合）](examples/bluemap-mcp.yml)
- `/aichat mcp status`, `tools`, `refresh`, and read-only `call` diagnostics
- Multiple Streamable HTTP / stdio servers, automatic 2026/2025 protocol detection
- Allow/deny lists, read-only defaults, per-player permissions, bounded responses and timeouts
- Native Gemini JSON Schema and function-call signatures, reasoning text filtering

Build with `bash gradlew clean build`. The installable JAR is `build/libs/AiChatBuddy-1.6.0-mcp.jar`.

---

## Upstream

Based on [mrgucci1/AiChatBuddy](https://github.com/mrgucci1/AiChatBuddy), retaining the original plugin identity and existing chat/search/wiki/memory features. This fork targets the user's existing Youer deployment; it does not require migrating the Minecraft server to a different platform.

This repository does not add or claim a new license for upstream code. Preserve the upstream authorship and applicable permission terms.
