# Send Keys DSL

tmux-buddy provides an EDN-based domain-specific language for sending keyboard input, mouse events, and timing sequences to tmux panes. This document describes the DSL syntax and usage.

## Why EDN?

The DSL uses EDN (Extensible Data Notation) because it offers several advantages for this use case:

- Clojure-native syntax that's easy to read and write
- Streamable parsing - actions can be processed one at a time from stdin without buffering the entire input
- Rich data types - strings for text, keywords for keys, vectors for parameterized actions
- Standard escape sequences for special characters

## Quick Start

The `send` command accepts EDN forms as arguments:

```bash
# Type "hello" and press Enter
tmuxb send mysession '"hello" :Enter'

# Press Ctrl+C
tmuxb send mysession ':C-c'

# Click at coordinates (50, 40)
tmuxb send mysession '[:Click 50 40]'

# Complex sequence: type, wait, then press down arrow 5 times
tmuxb send mysession '"search" :Enter [:Sleep 500] [:Down 5]'
```

## Actions

The DSL supports three types of actions:

1. Strings - literal text to type
2. Keywords - individual keys to press
3. Vectors - parameterized actions (clicks, delays, repetition, etc.)

Actions are executed in order from left to right.

## Typing Text

Strings are sent as literal keystrokes. This is the simplest way to type text into a tmux pane.

```clojure
"hello"           ; types "hello"
"hello world"     ; types "hello world" (spaces included)
"Hello, World!"   ; types "Hello, World!" (punctuation included)
```

Standard Clojure string escape sequences work as expected:

```clojure
"line1\nline2"    ; types "line1", then a newline, then "line2"
"col1\tcol2"      ; types "col1", then a tab, then "col2"
"say \"hi\""      ; types: say "hi"
"C:\\Users"       ; types: C:\Users
```

## Pressing Keys

Keywords represent individual key presses. Use them for special keys, function keys, and modifier combinations.

### Named Keys

Common keys have intuitive names. Some have shorter aliases for convenience:

| Keyword        | Key           | Alias     |
|----------------|---------------|-----------|
| `:Enter`       | Enter/Return  | `:Return` |
| `:Escape`      | Escape        | `:Esc`    |
| `:Tab`         | Tab           |           |
| `:Space`       | Space         |           |
| `:Backspace`   | Backspace     | `:BS`     |
| `:Delete`      | Delete        | `:Del`    |
| `:Insert`      | Insert        | `:Ins`    |
| `:Home`        | Home          |           |
| `:End`         | End           |           |
| `:PageUp`      | Page Up       | `:PgUp`   |
| `:PageDown`    | Page Down     | `:PgDn`   |
| `:Up`          | Arrow Up      |           |
| `:Down`        | Arrow Down    |           |
| `:Left`        | Arrow Left    |           |
| `:Right`       | Arrow Right   |           |
| `:F1` - `:F12` | Function keys |           |

### Single Character Keys

For individual character keys, use single-character keywords:

```clojure
:a :b :c          ; letter keys
:1 :2 :3          ; number keys
:/ :. :,          ; punctuation keys
```

While you could type these as strings (`"a"`), keywords are useful when combined with modifiers.

### Modifier Combinations

Combine modifier keys with other keys using prefixes in the keyword name:

| Prefix   | Modifier        |
|----------|-----------------|
| `C-`     | Ctrl            |
| `M-`     | Meta (Alt)      |
| `S-`     | Shift           |
| `Super-` | Super (Win/Cmd) |
| `Hyper-` | Hyper           |

Examples:

```clojure
:C-c              ; Ctrl+C (interrupt)
:C-x              ; Ctrl+X
:M-Tab            ; Alt+Tab
:S-Enter          ; Shift+Enter
:C-S-t            ; Ctrl+Shift+T (reopen tab in many apps)
:C-M-Delete       ; Ctrl+Alt+Delete
:Super-l          ; Super+L (lock screen on many systems)
```

Multiple modifiers can be combined by chaining prefixes: `:C-M-S-a` means Ctrl+Alt+Shift+A.

#### A Note on Super and Hyper

tmux's `send-keys` command only natively supports Ctrl, Meta, and Shift modifiers. When you use `Super-` or `Hyper-` prefixes, tmux-buddy automatically sends these via the Kitty keyboard protocol using hex mode. This means Super and Hyper will only work in terminals and applications that support the Kitty keyboard protocol.

## Vectors: Parameterized Actions

Vectors provide additional capabilities beyond simple key presses: repetition, delays, mouse events, and raw escape sequences.

### Repeating Keys

To press a key multiple times, use a vector with the key and a count:

```clojure
[:Enter 5]                ; press Enter 5 times
[:Down 10]                ; press Down arrow 10 times
[:C-v 3]                  ; press Ctrl+V 3 times
```

Add a delay between repetitions with the `:delay` option (milliseconds):

```clojure
[:Enter 5 :delay 100]     ; press Enter 5 times, 100ms pause between each
[:Down 10 :delay 50]      ; press Down 10 times, 50ms between each
```

### Repeating Text

Text strings can also be repeated:

```clojure
["hello" 3]               ; sends "hello" three times
["=" 40]                  ; sends 40 equals signs (useful for dividers)
["\n" 5]                  ; sends 5 newlines
["test" 3 :delay 100]     ; sends "test" 3 times with 100ms between each
```

### Pausing Execution

Use `[:Sleep ms]` to pause between actions:

```clojure
[:Sleep 100]              ; wait 100 milliseconds
[:Sleep 1000]             ; wait 1 second
[:Sleep 5000]             ; wait 5 seconds
```

This is essential when you need to wait for an application to respond before sending more input:

```clojure
;; Type a command, wait for it to complete, then type another
"make build" :Enter [:Sleep 2000] "make test" :Enter
```

## Mouse Events

The DSL supports mouse clicks, drags, and scrolling. All mouse coordinates are relative to the pane.

### Clicking

Basic clicks at screen coordinates:

```clojure
[:Click 50 40]            ; left click at x=50, y=40
[:RClick 50 40]           ; right click
[:MClick 50 40]           ; middle click
```

### Click Modifiers

Add modifier keywords after the coordinates to send modified clicks:

```clojure
[:Click 50 40 :C]         ; Ctrl+click
[:Click 50 40 :M]         ; Alt+click
[:Click 50 40 :S]         ; Shift+click
[:Click 50 40 :C :S]      ; Ctrl+Shift+click
[:RClick 50 40 :C]        ; Ctrl+right-click
```

Available modifiers for mouse events: `:C` (Ctrl), `:M` (Meta/Alt), `:S` (Shift). You can also use the longer forms: `:Ctrl`, `:Meta`, `:Alt`, `:Shift`.

### Drag Operations

For drag operations, use separate press and release actions:

```clojure
[:Click+ 10 10]           ; press mouse button at (10, 10)
[:Move 50 50]             ; move mouse to (50, 50) while dragging
[:Click- 50 50]           ; release mouse button at (50, 50)
```

A complete drag sequence:

```clojure
[:Click+ 10 10] [:Sleep 50] [:Move 100 100] [:Click- 100 100]
```

The sleep between press and move helps ensure the application registers the drag properly.

### Scrolling

Scroll at a specific position:

```clojure
[:ScrollUp 50 40]         ; scroll up once at (50, 40)
[:ScrollDown 50 40]       ; scroll down once at (50, 40)
[:ScrollUp 50 40 5]       ; scroll up 5 times
[:ScrollDown 50 40 3 :delay 50]  ; scroll down 3 times with 50ms between
```

## Advanced: Key Press and Release

Some applications (particularly games or specialized tools) distinguish between key press and key release events. The DSL supports this via the Kitty keyboard protocol.

```clojure
[:a :down]                ; press 'a' key (and hold)
[:a :up]                  ; release 'a' key
```

Aliases `:press` and `:release` also work:

```clojure
[:a :press]               ; same as [:a :down]
[:a :release]             ; same as [:a :up]
```

This enables scenarios like holding a modifier while pressing another key:

```clojure
;; Hold Ctrl, press and release 'a', then release Ctrl
[:Control :down] [:a :down] [:a :up] [:Control :up]
```

The standalone modifier keys available for press/release are: `:Control`, `:Shift`, `:Alt`, `:Meta`, `:Super`, `:Hyper`.

Note: Key press/release events use the Kitty keyboard protocol sent via hex mode. They only work in terminals and applications that support this protocol.

## Advanced: Raw Escape Sequences

For complete control, you can send arbitrary terminal escape sequences.

### Raw Strings

Send escape sequences using the `[:Raw "..."]` form. Use `\\e` or `\u001b` for the ESC byte:

```clojure
[:Raw "\\e[?25l"]         ; hide cursor
[:Raw "\\e[?25h"]         ; show cursor
[:Raw "\\e[2J"]           ; clear screen
[:Raw "\u001b[H"]         ; move cursor home (using unicode escape)
```

Supported escapes in raw strings:

| Escape   | Meaning              |
|----------|----------------------|
| `\\e`    | ESC byte (0x1b)      |
| `\\xNN`  | Arbitrary hex byte   |
| `\u001b` | ESC via unicode      |
| `\\n`    | Newline              |
| `\\t`    | Tab                  |
| `\\r`    | Carriage return      |
| `\\\\`   | Literal backslash    |
| `\\"`    | Literal double quote |

Note: Clojure doesn't support `\e` as an escape sequence, which is why you need the double backslash `\\e` or unicode `\u001b`.

### Raw Hex

Send raw bytes as space-separated hex values:

```clojure
[:RawHex "1b 5b 48"]      ; ESC [ H - cursor home
[:RawHex "1b 5b 32 4a"]   ; ESC [ 2 J - clear screen
```

## CLI Usage Examples

Here are some practical examples of using the send command:

```bash
# Simple text entry
tmuxb send mysession '"hello world" :Enter'

# Navigate a menu (down 3 times, then select)
tmuxb send mysession '[:Down 3 :delay 100] :Enter'

# Interrupt a running process
tmuxb send mysession ':C-c'

# Search in vim
tmuxb send mysession '"/pattern" :Enter'

# Save and quit vim
tmuxb send mysession ':Escape ":wq" :Enter'

# Click a button at known coordinates
tmuxb send mysession '[:Click 50 40]'

# Ctrl+click for multi-select
tmuxb send mysession '[:Click 50 40 :C]'

# Wait for something to load, then continue
tmuxb send mysession '"./slow-command" :Enter [:Sleep 5000] "next-command" :Enter'

# Type a horizontal rule
tmuxb send mysession '["=" 60] :Enter'
```

## Streaming Input

The DSL supports streaming execution, meaning you can pipe an infinite stream of actions without buffering the entire input:

```bash
# Generate actions dynamically and pipe to tmuxb
./generate-actions | tmuxb send mysession
```

Each EDN form is parsed and executed as soon as it's complete, making this suitable for real-time automation scenarios.

## Implementation Details

Understanding how the DSL is implemented can help when debugging:

- Regular keys and text are sent via `tmux send-keys`
- Mouse events use the SGR mouse protocol, sent via `tmux send-keys -H` (hex mode)
- Key press/release events use the Kitty keyboard protocol, also via hex mode
- Super and Hyper modifiers are sent via the Kitty keyboard protocol
- Delays are implemented as `Thread/sleep` between tmux commands

### Protocol References

For those interested in the underlying protocols:

- Kitty Keyboard Protocol: Key events are encoded as `ESC[codepoint;modifiers:eventu` where event is 1 for press, 3 for release
- SGR Mouse Protocol: Mouse events are encoded as `ESC[<button;x;yM` for press and `ESC[<button;x;ym` for release
