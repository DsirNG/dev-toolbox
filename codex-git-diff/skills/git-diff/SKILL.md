---
name: git-diff
description: Open a read-only side-by-side Git change viewer for the current local worktree.
---
Find this plugin's server.mjs two directories above this skill directory. From the user's active worktree, run node with the absolute server script path and the absolute worktree path as its first argument. Keep the process running in a terminal session. Open the printed URL in the built-in browser when available and return the link to the user. Never expose the server on a network interface.
