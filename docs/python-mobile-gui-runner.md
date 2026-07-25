# Python Script Spec For Mobile GUI Runner

Use this when generating Python scripts intended to run inside the MIT Android app's Python GUI runner.

## Goal

Write Python scripts which can be converted into a simple Android form automatically.

The app currently supports:

- `argparse`-based scripts as the preferred mode
- `input()`-based scripts as a fallback only
- text fields
- numeric fields
- choice fields
- boolean flags
- file picker fields
- stdout/stderr output display

## Preferred Script Style

Always prefer `argparse`.

Good:

```python
import argparse

def build_parser():
    parser = argparse.ArgumentParser(description="Resize an image")
    parser.add_argument("--width", type=int, required=True, help="Target width")
    parser.add_argument("--height", type=int, required=True, help="Target height")
    parser.add_argument("--mode", choices=["fit", "fill"], default="fit", help="Resize mode")
    parser.add_argument("--keep-alpha", action="store_true", help="Preserve alpha channel")
    parser.add_argument("input_file", help="Input image file path")
    return parser

def main():
    args = build_parser().parse_args()
    print(args)

if __name__ == "__main__":
    main()
```

Avoid:

- complex interactive terminal flows
- curses / rich terminal UIs
- endless `while True` prompt loops
- scripts which depend on a real shell session
- scripts which require copy-pasting filesystem paths manually

## Rules

### 1. Use `argparse`

- Define inputs with `parser.add_argument(...)`
- Put execution in `main()`
- Guard with `if __name__ == "__main__":`

### 2. Name file inputs clearly

Use argument names or help text containing words like:

- `file`
- `input_file`
- `output_file`
- `image_file`
- `pdf_file`
- `csv_file`
- `json_file`
- `path`

This helps the app map the field to a file picker.

Examples:

```python
parser.add_argument("input_file", help="Input file")
parser.add_argument("--template-file", help="Template file path")
```

### 3. Use `choices=` for fixed options

If the user should choose from a known set, use `choices`.

Example:

```python
parser.add_argument("--format", choices=["txt", "json", "csv"], default="json")
```

### 4. Use `action="store_true"` for toggles

Example:

```python
parser.add_argument("--verbose", action="store_true", help="Enable verbose output")
```

### 5. Use `type=int` or `type=float` for numbers

Examples:

```python
parser.add_argument("--count", type=int, default=10)
parser.add_argument("--threshold", type=float, default=0.5)
```

### 6. Print plain output

- Use normal `print(...)`
- Keep output readable as plain text
- Do not rely on ANSI colors or cursor control sequences

Good:

```python
print("Processing complete")
print(f"Saved to: {output_path}")
```

Avoid:

- colored terminal escape sequences
- progress bars requiring terminal redraw
- interactive prompts after execution starts

### 7. Validate clearly

If input is invalid, raise a normal error or print a clear message.

Good:

```python
if args.width <= 0:
    raise ValueError("Width must be greater than 0")
```

### 8. Keep dependencies minimal

Prefer Python standard library unless the script explicitly requires something else.

## Fallback `input()` Mode

Only use this if `argparse` is not practical.

If using `input()`:

- prompts must be explicit
- file prompts should contain words like `file`, `path`, `image`, `pdf`, `csv`, `json`
- choice prompts should look like `yes/no`, `y/n`, or `fast/safe`

Example:

```python
name = input("Enter name: ")
mode = input("Choose mode (fast/safe): ")
input_file = input("Select input file path: ")
```

Still, `argparse` is strongly preferred.

## Best Practices For Agent-Generated Scripts

- create one clear `main()` entry point
- keep arguments stable and descriptive
- use help text on every important argument
- prefer positional args only for required core inputs
- prefer optional args for toggles and settings
- keep script output short and informative
- write files only when clearly requested
- print the output file path when a file is created

## Recommended Template

```python
import argparse
from pathlib import Path


def build_parser():
    parser = argparse.ArgumentParser(description="Describe the script clearly")
    parser.add_argument("input_file", help="Input file path")
    parser.add_argument("--mode", choices=["fast", "safe"], default="safe", help="Processing mode")
    parser.add_argument("--verbose", action="store_true", help="Enable verbose logging")
    return parser


def main():
    args = build_parser().parse_args()
    input_path = Path(args.input_file)

    if not input_path.exists():
        raise FileNotFoundError(f"Input file not found: {input_path}")

    print(f"Running in mode: {args.mode}")
    print(f"Input file: {input_path}")
    print("Done")


if __name__ == "__main__":
    main()
```

## Tell Agents This

When asking an agent to generate a script, give it this instruction:

`Generate this as a mobile-GUI-compatible Python script for the MIT app. Prefer argparse, use clear help text, use choices for options, use file/path argument names for picker fields, and avoid raw interactive terminal flows.`
