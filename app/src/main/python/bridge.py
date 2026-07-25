import asyncio
import ast
import builtins
import contextlib
import io
import os
import json
import re
import runpy
import sys
import traceback
import git_manager
import importlib.util

STORAGE_PATH = None
_client = None
_loop = None

def set_storage_path(path: str):
    global STORAGE_PATH
    STORAGE_PATH = path
    os.environ['NOTEBOOKLM_HOME'] = os.path.dirname(path)

def _get_loop():
    global _loop
    if _loop is None or _loop.is_closed():
        try:
            _loop = asyncio.get_event_loop()
        except RuntimeError:
            _loop = asyncio.new_event_loop()
            asyncio.set_event_loop(_loop)
    return _loop

def _run(coro):
    try:
        return _get_loop().run_until_complete(coro)
    except Exception as e:
        print(f"PYTHON ERROR: {str(e)}")
        traceback.print_exc()
        raise e

async def _get_client():
    global _client
    if _client is None:
        try:
            from notebooklm import NotebookLMClient
            print(f"Initializing client with storage: {STORAGE_PATH}")
            client = await NotebookLMClient.from_storage(STORAGE_PATH)
            await client.__aenter__()
            _client = client
            print("Client initialized successfully")
        except Exception as e:
            print(f"Failed to initialize client: {str(e)}")
            traceback.print_exc()
            raise e
    return _client

def reset_client():
    global _client
    if _client:
        print("Resetting client")
        try:
            # We need to run the aexit in the loop
            loop = _get_loop()
            loop.run_until_complete(_client.__aexit__(None, None, None))
        except Exception as e:
            print(f"Error during client reset: {str(e)}")
        finally:
            _client = None

# --- Automation Runner ---

def run_automation_script_v2(script_path):
    try:
        spec = importlib.util.spec_from_file_location("dynamic_script", script_path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)

        if hasattr(module, 'run'):
            module.run()
            return "ok"
        else:
            return "Error: script has no run() function"
    except Exception as e:
        traceback.print_exc()
        return str(e)


def _extract_prompt_text(node):
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    if isinstance(node, ast.JoinedStr):
        parts = []
        for value in node.values:
            if isinstance(value, ast.Constant) and isinstance(value.value, str):
                parts.append(value.value)
        return "".join(parts)
    return ""


class _InputCollector(ast.NodeVisitor):
    def __init__(self):
        self.calls = []

    def visit_Call(self, node):
        if isinstance(node.func, ast.Name) and node.func.id == "input":
            prompt = _extract_prompt_text(node.args[0]) if node.args else ""
            self.calls.append((getattr(node, "lineno", 0), prompt))
        self.generic_visit(node)


def _detect_choice_values(prompt: str):
    for pattern in [
        r"\[([A-Za-z0-9_\- ]+(?:\s*/\s*[A-Za-z0-9_\- ]+)+)\]",
        r"\(([A-Za-z0-9_\- ]+(?:\s*/\s*[A-Za-z0-9_\- ]+)+)\)",
        r"\b([A-Za-z0-9_\-]+(?:\s*/\s*[A-Za-z0-9_\-]+){1,5})\b",
    ]:
        match = re.search(pattern, prompt)
        if not match:
            continue
        values = [part.strip() for part in match.group(1).split("/") if part.strip()]
        if 2 <= len(values) <= 6 and all(len(value) <= 24 for value in values):
            return values

    lowered = prompt.lower()
    if "yes/no" in lowered:
        return ["yes", "no"]
    if "y/n" in lowered:
        return ["y", "n"]
    return None


def _build_input_spec(prompt: str, index: int):
    cleaned = " ".join(prompt.split()).strip(" :\t\r\n")
    label = cleaned or f"Input {index}"

    if re.search(r"\b(file|path|folder|directory|image|pdf|document|csv|json|txt)\b", cleaned, re.IGNORECASE):
        return {
            "id": f"input_{index}",
            "kind": "file",
            "label": label,
            "choices": [],
        }

    choices = _detect_choice_values(cleaned)
    if choices:
        return {
            "id": f"input_{index}",
            "kind": "choice",
            "label": label,
            "choices": choices,
        }

    return {
        "id": f"input_{index}",
        "kind": "text",
        "label": label,
        "choices": [],
    }


def _safe_literal(node):
    try:
        return ast.literal_eval(node)
    except Exception:
        if isinstance(node, ast.Constant):
            return node.value
        if isinstance(node, ast.Name):
            if node.id in ("True", "False", "None"):
                return eval(node.id)
            return node.id
        if isinstance(node, ast.Attribute):
            parts = []
            current = node
            while isinstance(current, ast.Attribute):
                parts.append(current.attr)
                current = current.value
            if isinstance(current, ast.Name):
                parts.append(current.id)
                return ".".join(reversed(parts))
        return None


def _resolve_type_name(value):
    if isinstance(value, str):
        return value
    if callable(value):
        return getattr(value, "__name__", str(value))
    return None


def _looks_like_file_argument(label: str, help_text: str):
    text = f"{label} {help_text}".lower()
    return bool(re.search(r"\b(file|filepath|filename|document|image|pdf|csv|json|txt|input file|output file)\b", text))


class _ArgparseCollector(ast.NodeVisitor):
    def __init__(self):
        self.argparse_aliases = {"argparse"}
        self.parser_ctor_names = {"ArgumentParser"}
        self.parser_like_names = set()
        self.arguments = []

    def visit_Import(self, node):
        for alias in node.names:
            if alias.name == "argparse":
                self.argparse_aliases.add(alias.asname or alias.name)
        self.generic_visit(node)

    def visit_ImportFrom(self, node):
        if node.module != "argparse":
            self.generic_visit(node)
            return
        for alias in node.names:
            if alias.name in ("ArgumentParser",):
                self.parser_ctor_names.add(alias.asname or alias.name)
        self.generic_visit(node)

    def visit_Assign(self, node):
        if isinstance(node.value, ast.Call):
            if self._is_parser_ctor(node.value) or self._is_parser_group(node.value):
                for target in node.targets:
                    if isinstance(target, ast.Name):
                        self.parser_like_names.add(target.id)
        self.generic_visit(node)

    def visit_AnnAssign(self, node):
        if isinstance(node.target, ast.Name) and isinstance(node.value, ast.Call):
            if self._is_parser_ctor(node.value) or self._is_parser_group(node.value):
                self.parser_like_names.add(node.target.id)
        self.generic_visit(node)

    def visit_Call(self, node):
        if self._is_add_argument(node):
            spec = self._parse_add_argument(node)
            if spec is not None:
                self.arguments.append(spec)
        self.generic_visit(node)

    def _is_parser_ctor(self, call):
        func = call.func
        if isinstance(func, ast.Name):
            return func.id in self.parser_ctor_names
        if isinstance(func, ast.Attribute) and isinstance(func.value, ast.Name):
            return func.value.id in self.argparse_aliases and func.attr == "ArgumentParser"
        return False

    def _is_parser_group(self, call):
        func = call.func
        return (
            isinstance(func, ast.Attribute)
            and isinstance(func.value, ast.Name)
            and func.value.id in self.parser_like_names
            and func.attr in ("add_argument_group", "add_mutually_exclusive_group")
        )

    def _is_add_argument(self, call):
        func = call.func
        return (
            isinstance(func, ast.Attribute)
            and isinstance(func.value, ast.Name)
            and func.value.id in self.parser_like_names
            and func.attr == "add_argument"
        )

    def _parse_add_argument(self, call):
        option_strings = []
        for arg in call.args:
            value = _safe_literal(arg)
            if isinstance(value, str):
                option_strings.append(value)

        if not option_strings:
            return None

        keywords = {kw.arg: _safe_literal(kw.value) for kw in call.keywords if kw.arg}
        is_optional = any(value.startswith("-") for value in option_strings)
        primary_name = None
        if is_optional:
            long_options = [value for value in option_strings if value.startswith("--")]
            primary_name = (long_options or option_strings)[0]
            dest = keywords.get("dest") or primary_name.lstrip("-").replace("-", "_")
        else:
            primary_name = option_strings[0]
            dest = keywords.get("dest") or primary_name

        action = keywords.get("action")
        nargs = keywords.get("nargs")
        help_text = str(keywords.get("help") or "")
        default = keywords.get("default")
        choices = keywords.get("choices")
        type_name = _resolve_type_name(keywords.get("type"))

        if action in ("store_true", "store_false"):
            kind = "bool"
        elif isinstance(choices, (list, tuple)) and choices:
            kind = "choice"
        elif _looks_like_file_argument(dest, help_text):
            kind = "file"
        elif type_name in ("int", "float"):
            kind = "number"
        else:
            kind = "text"

        required = bool(keywords.get("required", False)) or (not is_optional and nargs not in ("?", "*"))
        multiple = nargs in ("*", "+") or (isinstance(nargs, int) and nargs > 1)

        normalized_choices = []
        if isinstance(choices, (list, tuple)):
            normalized_choices = [str(choice) for choice in choices]

        return {
            "id": dest,
            "mode": "argparse",
            "kind": kind,
            "label": dest.replace("_", " ").strip().capitalize() or dest,
            "choices": normalized_choices,
            "required": required,
            "default": None if default is None else str(default),
            "help": help_text,
            "arg_names": option_strings,
            "is_optional": is_optional,
            "action": action or "store",
            "multiple": multiple,
            "type_name": type_name or "",
        }


def inspect_script_interface(script_path: str) -> str:
    try:
        with open(script_path, "r", encoding="utf-8") as handle:
            source = handle.read()

        tree = ast.parse(source, filename=script_path)

        argparse_collector = _ArgparseCollector()
        argparse_collector.visit(tree)
        if argparse_collector.arguments:
            return json.dumps({
                "ok": True,
                "mode": "argparse",
                "inputs": argparse_collector.arguments,
            })

        collector = _InputCollector()
        collector.visit(tree)
        inputs = [
            dict(_build_input_spec(prompt, index + 1), mode="input")
            for index, (_, prompt) in enumerate(sorted(collector.calls, key=lambda item: item[0]))
        ]
        return json.dumps({"ok": True, "mode": "input", "inputs": inputs})
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e), "mode": "input", "inputs": []})


def inspect_script_inputs(script_path: str) -> str:
    result = json.loads(inspect_script_interface(script_path))
    if result.get("mode") != "input":
        result["inputs"] = []
    return json.dumps(result)


def run_script_with_inputs(script_path: str, inputs_json: str = "[]") -> str:
    original_input = builtins.input
    original_argv = list(sys.argv)
    original_cwd = os.getcwd()
    stdout_buffer = io.StringIO()
    stderr_buffer = io.StringIO()

    try:
        raw_inputs = json.loads(inputs_json or "[]")
        if not isinstance(raw_inputs, list):
            raw_inputs = []
        provided_inputs = ["" if value is None else str(value) for value in raw_inputs]
        input_iter = iter(provided_inputs)

        def fake_input(prompt=""):
            stdout_buffer.write(str(prompt))
            try:
                return next(input_iter)
            except StopIteration as exc:
                raise EOFError(f"No GUI input provided for prompt: {prompt}") from exc

        builtins.input = fake_input
        script_dir = os.path.dirname(script_path) or original_cwd
        os.chdir(script_dir)
        sys.argv = [script_path]

        with contextlib.redirect_stdout(stdout_buffer), contextlib.redirect_stderr(stderr_buffer):
            runpy.run_path(script_path, run_name="__main__")

        return json.dumps({
            "ok": True,
            "stdout": stdout_buffer.getvalue(),
            "stderr": stderr_buffer.getvalue(),
        })
    except Exception:
        stderr_buffer.write(traceback.format_exc())
        return json.dumps({
            "ok": False,
            "stdout": stdout_buffer.getvalue(),
            "stderr": stderr_buffer.getvalue(),
        })
    finally:
        builtins.input = original_input
        sys.argv = original_argv
        os.chdir(original_cwd)


def run_script_with_args(script_path: str, args_json: str = "[]") -> str:
    original_argv = list(sys.argv)
    original_cwd = os.getcwd()
    stdout_buffer = io.StringIO()
    stderr_buffer = io.StringIO()

    try:
        argv_items = json.loads(args_json or "[]")
        if not isinstance(argv_items, list):
            argv_items = []
        sys.argv = [script_path] + [str(item) for item in argv_items]
        script_dir = os.path.dirname(script_path) or original_cwd
        os.chdir(script_dir)

        with contextlib.redirect_stdout(stdout_buffer), contextlib.redirect_stderr(stderr_buffer):
            try:
                runpy.run_path(script_path, run_name="__main__")
                ok = True
            except SystemExit as exc:
                code = exc.code if isinstance(exc.code, int) else 0
                ok = code == 0
                if exc.code not in (None, 0):
                    stderr_buffer.write(f"Exited with code {exc.code}\n")

        return json.dumps({
            "ok": ok,
            "stdout": stdout_buffer.getvalue(),
            "stderr": stderr_buffer.getvalue(),
        })
    except Exception:
        stderr_buffer.write(traceback.format_exc())
        return json.dumps({
            "ok": False,
            "stdout": stdout_buffer.getvalue(),
            "stderr": stderr_buffer.getvalue(),
        })
    finally:
        sys.argv = original_argv
        os.chdir(original_cwd)

# --- NotebookLM API ---

def test_auth() -> str:
    async def _():
        client = await _get_client()
        notebooks = await client.notebooks.list()
        return f"ok:{len(notebooks)}"
    return _run(_())

def get_notebooks() -> str:
    async def _():
        client = await _get_client()
        print("Fetching notebooks...")
        notebooks = await client.notebooks.list()
        return json.dumps([{"id": nb.id, "title": nb.title} for nb in notebooks])
    return _run(_())

def create_notebook(name: str) -> str:
    async def _():
        client = await _get_client()
        print(f"Creating notebook: {name}")
        nb = await client.notebooks.create(name)
        return nb.id
    return _run(_())

def delete_notebook(notebook_id: str) -> str:
    async def _():
        client = await _get_client()
        print(f"Deleting notebook: {notebook_id}")
        await client.notebooks.delete(notebook_id)
        return "ok"
    return _run(_())

def upload_file(notebook_id: str, file_path: str) -> str:
    async def _():
        client = await _get_client()
        print(f"Uploading file: {file_path}")
        await client.sources.add_file(notebook_id, file_path, wait=True)
        return "ok"
    return _run(_())

def add_url(notebook_id: str, url: str) -> str:
    async def _():
        client = await _get_client()
        print(f"Adding URL: {url}")
        await client.sources.add_url(notebook_id, url, wait=True)
        return "ok"
    return _run(_())

def list_sources(notebook_id: str) -> str:
    async def _():
        client = await _get_client()
        print(f"Listing sources for: {notebook_id}")
        sources = await client.sources.list(notebook_id)
        return json.dumps([{"id": s.id, "title": s.title} for s in sources])
    return _run(_())

def delete_source(notebook_id: str, source_id: str) -> str:
    async def _():
        client = await _get_client()
        print(f"Deleting source {source_id} from {notebook_id}")
        await client.sources.delete(notebook_id, source_id)
        return "ok"
    return _run(_())

def ask(notebook_id: str, prompt: str) -> str:
    async def _():
        client = await _get_client()
        print(f"Asking: {prompt}")
        result = await client.chat.ask(notebook_id, prompt)
        return result.answer
    return _run(_())

def sync_repo(root_path: str, course_name: str, token: str, first_name: str) -> str:
    try:
        return git_manager.sync_course_repo(root_path, course_name, token, first_name)
    except Exception as e:
        traceback.print_exc()
        return f"Sync Error: {str(e)}"

def push_repo(root_path: str, course_name: str, token: str, first_name: str) -> str:
    try:
        return git_manager.push_repo(root_path, course_name, token, first_name)
    except Exception as e:
        traceback.print_exc()
        return f"Push Error: {str(e)}"

def pull_repo(root_path: str, course_name: str, token: str, first_name: str) -> str:
    try:
        return git_manager.pull_repo(root_path, course_name, token, first_name)
    except Exception as e:
        traceback.print_exc()
        return f"Pull Error: {str(e)}"
