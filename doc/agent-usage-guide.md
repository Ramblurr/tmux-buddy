# Agent Usage Guide for tmuxb

A practical guide for LLM agents using tmuxb to interact with tmux sessions. This guide emphasizes defensive practices that account for shared terminal sessions and timing uncertainties.

## Session Files: Simplifying Repeated Commands

tmuxb supports a `.tmuxb_session` file that stores the session name and socket path.
When present, you can omit these from every command.

### Creating a Session with Auto-Generated File

```bash
# Simplest form - creates session on default tmux server
./tmuxb new myproject

# The file contains:
# {:session "myproject"}
```

If you need an isolated tmux server (separate from your normal tmux), use `-S`:

```bash
# Creates session on a dedicated socket
./tmuxb new -S myproject.sock myproject

# The file contains:
# {:session "myproject", :socket "/run/user/1000/tmuxb/myproject.sock"}
```

### Using the Session File

Once `.tmuxb_session` exists, commands automatically use it:

```bash
# These all work without specifying session or socket:
./tmuxb capture
./tmuxb send -- '"echo hello" :Enter'
./tmuxb list
```

You can still override with explicit arguments:

```bash
./tmuxb capture other-session                # uses other-session instead
./tmuxb capture -S /other/sock               # uses different socket
./tmuxb capture -S /other/sock other-session # uses different socket and other-session
```

### File Discovery

tmuxb walks up from the current directory looking for `.tmuxb_session`, similar to how git finds `.git`. This means the file can be in a project root and work from subdirectories.

### Session Lifecycle

```bash
# Create session (writes .tmuxb_session)
./tmuxb new -S dev.sock myproject

# Work with session (no args needed)
./tmuxb capture
./tmuxb send -- '"make build" :Enter'

# Kill session (auto-deletes .tmuxb_session if it matches)
./tmuxb kill myproject
```

### Flags for `new`

- `-S, --socket` - Socket path (bare names go to $XDG_RUNTIME_DIR/tmuxb/)
- `--no-session-file` - Don't create .tmuxb_session
- `-f, --force` - Overwrite existing .tmuxb_session

## The Cardinal Rule: Always Capture First

Before sending any commands, always capture the current terminal state. The terminal may have changed since you last looked at it.

```bash
# ALWAYS do this before sending commands
./tmuxb capture

# Or with explicit session if no .tmuxb_session file:
./tmuxb capture SESSION
```

Why? Because:

1. A human user might be watching and interacting with the same session
2. Another agent might be sharing the session
3. A previous command might have produced unexpected output
4. An application might have crashed or exited
5. Time has passed and you have no idea what happened

If more than a few seconds have passed since your last capture, capture again. If you're about to send a complex sequence, capture first. When in doubt, capture.
If in doubt, capture!

## Verify Before Proceeding

When you start an application (vim, emacs, python, etc.), verify it actually started before sending commands to it.

Bad pattern:
```bash
# DON'T DO THIS - you have no idea if vim actually started
./tmuxb send demo -- '"vim" :Enter [:Sleep 500] "i" "Hello"'
```

Good pattern:
```bash
# Start vim
./tmuxb send demo -- '"vim" :Enter'

# Wait and verify vim is running
./tmuxb capture demo
# Look for vim's interface in the output

# Only then send more commands
./tmuxb send demo -- '"i" "Hello"'
```

This applies to any state transition:
- Starting an editor (vim, emacs, nano)
- Entering a REPL (python, node, clojure)
- Running a command that changes the prompt
- Opening a menu or dialog
- Switching modes within an application

## Breaking Up Long Sequences

Don't send extremely long command sequences without verification checkpoints. If something fails early in the sequence, you'll send garbage to whatever state the terminal ends up in.

Bad pattern:
```bash
# 30+ seconds of commands with no verification
./tmuxb send demo -- \
  '"vim" :Enter [:Sleep 500] "i"' \
  '"line 1" :Enter [:Sleep 1000]' \
  '"line 2" :Enter [:Sleep 1000]' \
  # ... 20 more lines ...
  ':Escape ":wq" :Enter'
```

Better pattern:
```bash
# Start vim
./tmuxb send demo -- '"vim" :Enter'
sleep 1

# Verify vim started
./tmuxb capture demo
# Check output shows vim interface

# Now send the content
./tmuxb send demo -- '"i" "line 1" :Enter "line 2" :Enter'

# Verify periodically for long sequences
./tmuxb capture demo

# Continue...
```

## Handling Shell Quoting

Use `--` to separate tmuxb options from EDN arguments:

```bash
./tmuxb send demo -- :C-x
./tmuxb send demo -- '"hello" :Enter'
```

For strings with special characters, use appropriate quoting:

```bash
# Exclamation marks need $'...' quoting (bash history expansion)
./tmuxb send demo -- $'"Hello!" :Enter'

# Or escape with backslash
./tmuxb send demo -- '"Hello World\!" :Enter'
```

## Timing Considerations

For repeated actions with delays:
```bash
# Press down 5 times with 300ms between each
./tmuxb send demo -- '[:Down 5 :delay 300]'
```

## Working with Vim

Vim has modes. Always be aware of which mode you're in.

```bash
# Capture first
./tmuxb capture demo

# If in normal mode, enter insert mode before typing
./tmuxb send demo -- '"i" "your text here"'

# Return to normal mode
./tmuxb send demo -- :Escape

# Vim commands start with : in normal mode
./tmuxb send demo -- '":w" :Enter'  # save
./tmuxb send demo -- $'":q!" :Enter'  # quit without saving (note $'' for !)
```

Verify mode transitions:
```bash
# After escaping to normal mode, capture to verify
./tmuxb capture demo
# Look for -- INSERT -- or similar mode indicator absence
```

## Working with Emacs

Emacs uses modifier keys extensively:

```bash
# C-x C-s to save
./tmuxb send demo -- :C-x :C-s

# M-x for command prompt
./tmuxb send demo -- :M-x

# Cancel with C-g
./tmuxb send demo -- :C-g
```

## Example: Safe Vim Editing Session

Here's a complete example showing defensive practices. With a `.tmuxb_session` file, you can omit the session name:

```bash
# 1. Capture initial state
./tmuxb capture
# Verify we're at a shell prompt

# 2. Start vim
./tmuxb send -- '"vim test.txt" :Enter'

# 3. Wait and verify vim started
./tmuxb capture
# Look for vim interface, check we're not still at shell

# 4. Enter insert mode and type
./tmuxb send -- '"i" "Hello World" :Enter "Line 2"'

# 5. Verify content was entered
./tmuxb capture
# Check the text appears in the buffer

# 6. Save and quit
./tmuxb send -- ':Escape ":wq" :Enter'

# 7. Verify we're back at shell
./tmuxb capture
# Confirm shell prompt is visible
```

Without a session file, specify session explicitly:

```bash
./tmuxb capture demo
./tmuxb send demo -- '"vim test.txt" :Enter'
# etc.
```

## Common Mistakes

1. Not capturing before sending commands
2. Assuming an application started without verifying
3. Sending a long sequence without checkpoints
4. Forgetting `--` before keywords like `:C-x`
5. Not accounting for bash history expansion (`!`)
6. Assuming the terminal state hasn't changed after time passes
7. Not verifying mode changes in modal editors

## Summary

- Use `.tmuxb_session` file to avoid repeating session/socket args
- Capture before every send operation
- Verify state transitions (app started, mode changed, etc.)
- Break long sequences into verifiable chunks
- Use `--` before EDN keywords
- Handle shell quoting carefully (single quotes around EDN with double-quoted strings)
- When in doubt, capture again
