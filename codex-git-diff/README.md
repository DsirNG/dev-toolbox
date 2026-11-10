# Git Diff for Codex

Requires Git, Node.js, and Windows PowerShell 5.1 or PowerShell 7 for installation. No npm install is needed.

From the toolbox root, preview a worktree with: `node codex-git-diff/server.mjs "D:/path/to/worktree"`. Open the printed URL in
the Codex built-in browser. Stop the terminal process to close the viewer.

To register the plugin in your personal marketplace, run
`powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\codex-git-diff\install.ps1`, restart Codex, then install **git-diff**
from that marketplace. Start a local chat in the worktree and ask the plugin to
open the current Git changes.

The viewer is read only. Staged changes compare HEAD with the index; unstaged
changes compare the index with the worktree. Untracked files compare against an
empty file. Binary and mode-only changes show Git's metadata. Renames show up as
a deletion and an addition. The viewer opens in the browser beside the chat.


Filter the file tree by path with **/**. Use **Alt+Up** and **Alt+Down**
to move between changes in the selected diff. Clear the filter to restore all files.
