---
name: git-diff
description: Open a read-only side-by-side Git change viewer for the current Codex worktree. Use when asked to open or inspect Git changes visually.
---
Use the current chat's local working directory (the task environment cwd, including an isolated worktree). Resolve it with git rev-parse --show-toplevel from that directory. Never default to the plugin installation directory, the toolbox repository, or a path from another chat. If the current directory is not in a Git worktree, report that directly.

Locate server.mjs in the plugin root (two directories above this skill directory). Run node with that absolute script path and the detected absolute Git worktree root, using the current chat's local executor. Keep the server running in its terminal session. Open the printed URL in the built-in browser when available and return the link. The page loads the worktree's staged, unstaged, and untracked changes automatically. Do not ask the user to select or import files. Start a viewer for the current worktree even if another repository has a viewer running. Never expose the server on a network interface.
