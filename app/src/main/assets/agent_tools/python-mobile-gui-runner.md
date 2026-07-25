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

### 7. Validate clearly

If input is invalid, raise a normal error or print a clear message.

### 8. Keep dependencies minimal

Prefer Python standard library unless the script explicitly requires something else.

## Fallback `input()` Mode

Only use this if `argparse` is not practical.

If using `input()`:

- prompts must be explicit
- file prompts should contain words like `file`, `path`, `image`, `pdf`, `csv`, `json`
- choice prompts should look like `yes/no`, `y/n`, or `fast/safe`

Still, `argparse` is strongly preferred.

## Tell Agents This

`Generate this as a mobile-GUI-compatible Python script for the MIT app. Prefer argparse, use clear help text, use choices for options, use file/path argument names for picker fields, and avoid raw interactive terminal flows.`
