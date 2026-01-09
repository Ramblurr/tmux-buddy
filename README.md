# tmux-buddy

A CLI tool that enables humans or LLMs to interact with tmux sessions.

Useful for:

- driving editors like emacs/vim
- TUI testing and automation
- <your idea here>


## Requirements


- [Babashka](https://babashka.org/)

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

## Versioning

tmux-buddy follows [break versioning](https://www.taoensso.com/break-versioning):

- Version format: `<major>.<minor>.<non-breaking>`
- Breaking changes increment the minor version (e.g., 1.0.0 → 1.1.0)
- Non-breaking changes increment the patch version (e.g., 1.0.0 → 1.0.1)

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.

Before submitting a PR:

1. Ensure all tests pass: `bb test`
2. Add tests for any new functionality
3. Update documentation as needed

## License: European Union Public License 1.2

Copyright © 2026 Casey Link

Distributed under the [EUPL-1.2](https://spdx.org/licenses/EUPL-1.2.html).
