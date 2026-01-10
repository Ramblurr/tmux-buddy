# tmux-buddy

> A CLI tool that enables humans or LLMs to interact with tmux sessions.

It overlaps some with the canonical `tmux` cli commands, but it also adds some features that are useful for LLMs or other users that cannot see.

Useful for:

- driving editors like emacs/vim
- TUI testing and automation
- `<your idea here>`

## Motivation

LLMs are now pretty good at writing code and using shell commands.
But they hit a wall with interactive stuff.
An LLM can write you a vim macro, sure.
But it can't actually use vim.
Ask it to use emacs' magit to interactively stage just part of a file and, well, it just can't.

The problem is LLMs have no way to see what's happening in a virtual terminal or press keys.
They're like someone trying to help you over the phone but you forgot to share your screen.

LLMs operate on text, the terminal is, like, all text..
I spend most of my day slapping on this keyboard to produce text in all kinds of terminal environments.

The answer is tmux of course, because it's almost always tmux.

tmux actually has the primitives for this already.
You really only need two tmux subcommands: [`capture-pane`][capture-pane] and [`send-keys`][send-keys].

- `tmux capture-pane -p` dumps whatever's on screen as text, use `-e`, and the escape sequences are included (technicolor!)
- `tmux send-keys` lets you type stuff into the pane.

So if you don't have sweaty eyeballs, but are pretty good with text, a theoretical way to interact with tmux (and everything inside tmux!) is:
you capture, look at what's there, send some keys, capture again to see what changed.
That's like 90% of the way to letting an LLM drive a terminal like you do.
Same loop a human uses, just with commands instead of eyeballs and fingers.

[send-keys]: https://github.com/tmux/tmux/wiki/Advanced-Use#sending-keys
[capture-pane]: https://github.com/tmux/tmux/wiki/Advanced-Use#capturing-pane-content

I can't take credit for the idea.
I heard of it from [Armin Ronacher](https://x.com/mitsuhiko/status/1991997262810218983), and ignored it.
But then I saw Mario Zechner have his coding agent [use LLDB](https://mariozechner.at/posts/2025-11-30-pi-coding-agent/#toc_17) interactively to debug a C program.
In the same post Mario links to [Terminus 2](https://github.com/laude-institute/terminal-bench/tree/main/terminal_bench/agents/terminus_2) an agent that has no tools EXCEPT tmux. 

You just write out some specific instructions, throw in a few tips about reading the screen, add some socket business for isolation, slap it in a skill file, and you're good to go.
And it [really works][skill-file].

[skill-file]: https://github.com/Ramblurr/nix-devenv/blob/ffcf70b448e3b956cdf308af2b25334f97685625/prompts/skills/tmux/SKILL.md

So now an LLM can drive emacs.
Or navigate your crusty Borland Turbo Vision tui interface from 1997.
Same loop a human uses: look at screen, send keys, repeat.

You can even `tmux attach` to the same session as your agent and follow along or steer them like a driving instructor with a second wheel and set of pedals.

There are a few niggles.

1. It is rather slow. Every iteration of that loop requires some inference, so you don't see the agent flying through the terminal like you can
2. It burns tokens. Every `capture-pane` dumps the screen contents into the context.
3. The cursor is invisible.

For now I'm just living with the first one. Though switching to a non top-tier model can mitigate it, depending on the difficulty of your task.

My short term goal is to have an llm help me in Emacs, and help drive some TUI applications in a testing situation.
In particular, I want to teach the llm to use [magit][magit] to be able to do complex interactive git staging operations.

[magit]: https://magit.vc/

The agent isn't very good at tracking where the cursor is.
Emacs and tmux both have a mechanism for printing the coordinates of the cursor, but LLMs cannot map that onto the screen dump of text they get.

tmux-buddy wraps the tmux cli with a few quality of life features for the terminal wielding LLM:

- It marks the cursor position with [Ogham][ogham] feather marks characters: `᚛` (left) and `᚜` (right). Ogham is early medieval alphabet that is very much dead. [^1]
- It tells you when screen content changed (`tmuxb capture claude-magit --if-changed`)
- It can transform ANSI styles into formats that are make it easier on human eyes. (`tmuxb capture --style tags`)
- JSON/EDN output when you need it (`tmuxb list --json`)

[^1]: If your terminal output actually contains Ogham feather marks, congratulations on being niche edge case. Open an issue and we can work something out.

## Requirements

- [Babashka](https://babashka.org/)

## Install

<details>
<summary><b>Option 1: Install via bbin (recommended)</b></summary>

```bash
bbin install io.github.ramblurr/tmux-buddy --as tmuxb
```

</details>

<details>
<summary><b>Option 2: Direct download (curl)</b></summary>

```bash
curl -fsSL https://raw.githubusercontent.com/ramblurr/tmux-buddy/master/tmuxb -o ~/.local/bin/tmuxb
chmod +x ~/.local/bin/tmuxb
```

</details>

<details>
<summary><b>Option 3: Install with Nix</b></summary>

Nix flakes:

Add to your system configuration:

```nix
{
  inputs.tmux-buddy.url = "https://flakehub.com/f/ramblurr/tmux-buddy/*";
  # In your system packages:
  environment.systemPackages = with ; [
    inputs.tmux-buddy.packages.${pkgs.system}.default
  ];
}
```

Nix non-flakes:

```nix
{ pkgs ? import <nixpkgs> {} }:

let
  tmux-buddy = pkgs.callPackage (pkgs.fetchFromGitHub {
    owner = "ramblurr";
    repo = "tmux-buddy";
    rev = "CHANGEME";
    hash = "sha256-CHANGEME";
  } + "/package.nix") {};
in
pkgs.mkShell {
  buildInputs = [ tmux-buddy ];
}
```

Then run `nix-shell` to enter a shell with tmuxb available.

</details>

<details>
<summary><b>Option 4: Manual Installation</b></summary>

```bash
git clone https://github.com/ramblurr/tmux-buddy.git
cd tmux-buddy

# Add to PATH
ln -s $(pwd)/tmuxb ~/.local/bin/tmuxb
```

</details>


## Usage

> [!TIP]
> LLMs and coding agents: see [doc/agent-usage-guide.md](doc/agent-usage-guide.md) for agent-specific guidance.

### Session Management

Create a new session:
```bash
# Basic session (creates .tmuxb_session file for convenience)
tmuxb new my-session

# With custom window name and command
tmuxb new my-session --window "editor" --cmd "vim"

# With custom dimensions
tmuxb new my-session --width 100 --height 30

# Use a custom socket for isolation
tmuxb new my-session --socket /tmp/my.sock
```

List all tmux sessions:
```bash
tmuxb list
tmuxb list --json
tmuxb list --edn
```

Kill a session:
```bash
tmuxb kill my-session
```

### Capturing Pane Content

Capture the current screen of a pane:
```bash
# Basic capture with cursor position marked
tmuxb capture my-session

# Capture specific pane
tmuxb capture my-session --pane 0

# Include scrollback history
tmuxb capture my-session --history

# Capture with different style formats
tmuxb capture my-session --style none    # Strip ANSI, show cursor (default)
tmuxb capture my-session --style lines   # Prefix lines based on style (>, !, *)
tmuxb capture my-session --style tags    # Convert ANSI to inline tags ([b], [r], etc.)
tmuxb capture my-session --style ansi    # Preserve ANSI escape codes

# Raw output without cursor markers
tmuxb capture my-session --raw

# Only output if content changed (useful for polling)
tmuxb capture my-session --if-changed
```

The cursor position is marked with [Ogham][ogham] feather marks: `᚛` (left) and `᚜` (right).
These are unlikely to appear in normal terminal output.

[ogham]: https://en.wikipedia.org/wiki/Ogham

### Sending Input

tmux-buddy uses an EDN-based DSL for sending input. See [doc/send-keys-dsl.md](doc/send-keys-dsl.md) for complete documentation.

```bash
# Type text and press Enter
tmuxb send my-session '"hello world" :Enter'

# Press special keys
tmuxb send my-session ':C-c'              # Ctrl+C
tmuxb send my-session ':Escape ":wq" :Enter'  # vim save and quit

# Press a key multiple times with delay
tmuxb send my-session '[:Down 5 :delay 100]'  # Down arrow 5 times, 100ms between

# Wait between actions
tmuxb send my-session '"make build" :Enter [:Sleep 2000] "make test" :Enter'

# Read actions from stdin (useful for complex sequences)
echo '"hello" :Enter' | tmuxb send my-session
```

By default, there's a 30ms delay between actions (simulating fast human typing). Adjust with `--delay`:

```bash
tmuxb send --delay 0 my-session '"fast"'     # No delay (as fast as possible)
tmuxb send --delay 100 my-session '"slow"'   # 100ms between actions
```

### Watching for Changes

Watch a pane and output only when content changes:
```bash
# Watch with 500ms interval (default)
tmuxb watch my-session

# Custom interval and timeout
tmuxb watch my-session --interval 1.0 --timeout 60

# Stop when specific text appears
tmuxb watch my-session --until "Build complete"
```

### Listing Windows and Panes

```bash
# List windows in a session
tmuxb windows my-session
tmuxb windows my-session --json

# List panes in a session
tmuxb panes my-session
tmuxb panes my-session --window 0
tmuxb panes my-session --edn
```

### Mouse Events

Send mouse clicks to specific coordinates:
```bash
# Left click at coordinates (x=10, y=5)
tmuxb mouse my-session 10 5

# Right click
tmuxb mouse my-session 10 5 --click right

# Double click
tmuxb mouse my-session 10 5 --double
```

For more complex mouse operations (drag, scroll, modifiers), use the send command:
```bash
tmuxb send my-session '[:Click 50 40 :C]'     # Ctrl+click
tmuxb send my-session '[:ScrollDown 50 40 3]' # Scroll down 3 times
```

### Session File

When you create a session with `tmuxb new`, it writes a `.tmuxb_session` file in the current directory.
This file stores the session name and socket path, so subsequent commands can omit the session argument:

```bash
tmuxb new my-session --socket /tmp/test.sock
tmuxb capture          # automatically uses my-session
tmuxb send '"hello"'   # automatically uses my-session
tmuxb kill             # automatically uses my-session
```

### Getting Help

```bash
# General help
tmuxb --help

# Command-specific help
tmuxb capture --help
tmuxb send --help
```

## Development

Use the nix devshell `nix develop`.

```
# Rebuild the uberscript
# fyi: The pre-commit hook rebuilds automatically when src/ changes
bb build

# Run all tests
bb test

# Run specific test namespace
bb test --nses tmuxb-test

# Run specific test
bb test --vars tmuxb-test/foo-bar-test
```

### Versioning

tmux-buddy follows [break versioning](https://www.taoensso.com/break-versioning):

- Version format: `<major>.<minor>.<non-breaking>`
- Breaking changes increment the minor version (e.g., 1.0.0 → 1.1.0)
- Non-breaking changes increment the patch version (e.g., 1.0.0 → 1.0.1)

### Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.

Before submitting a PR:

1. Ensure all tests pass: `bb test`
2. Add tests for any new functionality
3. Update documentation as needed

## License: European Union Public License 1.2

Copyright © 2026 Casey Link

Distributed under the [EUPL-1.2](https://spdx.org/licenses/EUPL-1.2.html).
