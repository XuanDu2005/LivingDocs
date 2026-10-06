"""Built-in code analysers.

The MVP ships regex / indentation-driven parsers for Python, Java, TypeScript
and JavaScript. They cover the "extract class / function / endpoint signatures"
need without pulling heavy language servers. Real AST libraries
(``tree-sitter``, ``javalang``, ``esprima``) can be slotted in later
without changing the public API.
"""

from __future__ import annotations

import re
from typing import List

from .models import CodeEntity, CodeEntityKind, FunctionParameter, ParsedFile
from .registry import register_analyser

# ----------------------------------------------------------------------------
# Python
# ----------------------------------------------------------------------------


@register_analyser("python")
def analyse_python(file_path: str, source: str) -> ParsedFile:
    parsed = ParsedFile(file_path=file_path, language="python")
    lines = source.splitlines()

    # Detect module docstring (first statement if it is a literal string).
    i = 0
    while i < len(lines) and not lines[i].strip():
        i += 1
    if i < len(lines) and re.match(r"\s*[ru]?['\"]{3}", lines[i] or ""):
        end_quote = "'''" if "'''" in lines[i] else '"""'
        start = i
        if idx := lines[i].find(idx_quote := idx_quote_match(lines[i])):
            i += 1
        else:
            i += 1
            while i < len(lines) and idx_quote not in lines[i]:
                i += 1
            i += 1
        parsed.module_docstring = "\n".join(lines[start:i]).strip()

    stack: list[tuple[int, str | None]] = []  # (indent, parent_qname)

    for idx, raw in enumerate(lines, start=1):
        line = raw.rstrip()
        if not line.strip() or line.lstrip().startswith("#"):
            continue

        # Imports
        if m := re.match(r"^\s*(?:from\s+([\w.]+)\s+)?import\s+([^\n#]+)", line):
            target = m.group(2).strip().split(" as ")[0]
            module = m.group(1) or target.split(".")[0]
            parsed.imports.append(f"{module}:{target}")

        indent = len(line) - len(line.lstrip())
        while stack and stack[-1][0] >= indent:
            stack.pop()

        parent_qname = stack[-1][1] if stack else None

        stripped = line[indent:]
        if stripped.startswith(("def ", "async def ")):
            entity = _parse_python_function(file_path, lines, idx, indent)
            if entity:
                if parent_qname:
                    entity.parent_qualified_name = parent_qname
                    entity.qualified_name = f"{parent_qname}.{entity.simple_name}"
                parsed.entities.append(entity)
                stack.append((indent, entity.qualified_name))
        elif stripped.startswith("class "):
            entity = _parse_python_class(file_path, lines, idx, indent)
            if entity:
                if parent_qname:
                    entity.parent_qualified_name = parent_qname
                    entity.qualified_name = f"{parent_qname}.{entity.simple_name}"
                parsed.entities.append(entity)
                stack.append((indent, entity.qualified_name))
        elif stripped.startswith("@"):
            # decorator only — attached to the next entity's decorators list
            pass

    return parsed


def _parse_python_class(file_path: str, lines: List[str], idx: int, indent: int) -> CodeEntity | None:
    header = lines[idx - 1].strip()
    m = re.match(r"class\s+(\w+)\s*(?:\(([^)]*)\))?\s*:", header)
    if not m:
        return None
    name = m.group(1)
    bases = m.group(2)
    end_line = _find_block_end(lines, idx)
    docstring = _extract_python_docstring(lines, idx, end_line)
    decorators = _collect_decorators(lines, idx, indent)
    return CodeEntity(
        qualified_name=name,
        simple_name=name,
        kind=CodeEntityKind.CLASS,
        file_path=file_path,
        language="python",
        start_line=idx,
        end_line=end_line,
        signature=f"class {name}({bases or ''})".rstrip("()"),
        docstring=docstring,
        decorators=decorators,
        modifiers=["class"],
        metadata={"bases": [b.strip() for b in (bases or "").split(",") if b.strip()]},
    )


def _parse_python_function(file_path: str, lines: List[str], idx: int, indent: int) -> CodeEntity | None:
    header = lines[idx - 1].strip()
    m = re.match(r"(?:async\s+)?def\s+(\w+)\s*\(([^)]*)\)\s*(?:->\s*([^:]+))?\s*:", header)
    if not m:
        return None
    name = m.group(1)
    params_raw = m.group(2)
    return_type = (m.group(3) or "").strip() or None
    end_line = _find_block_end(lines, idx)
    docstring = _extract_python_docstring(lines, idx, end_line)
    decorators = _collect_decorators(lines, idx, indent)
    is_method = "." in name  # simple heuristic, refined later
    params = _parse_python_params(params_raw)
    kind = CodeEntityKind.METHOD if is_method else CodeEntityKind.FUNCTION
    return CodeEntity(
        qualified_name=name,
        simple_name=name,
        kind=kind,
        file_path=file_path,
        language="python",
        start_line=idx,
        end_line=end_line,
        signature=header,
        docstring=docstring,
        decorators=decorators,
        modifiers=[w for w in ("async", "staticmethod", "classmethod") if w in header],
        parameters=params,
        return_type=return_type,
        metadata={"is_async": header.startswith("async def")},
    )


def _parse_python_params(raw: str) -> List[FunctionParameter]:
    out: List[FunctionParameter] = []
    for chunk in raw.split(","):
        chunk = chunk.strip()
        if not chunk:
            continue
        if chunk in ("self", "cls"):
            continue
        if "=" in chunk:
            name_part, default = chunk.split("=", 1)
        else:
            name_part, default = chunk, None
        name_part = name_part.strip().lstrip("*")
        if ":" in name_part:
            name, hint = name_part.split(":", 1)
        else:
            name, hint = name_part, None
        out.append(FunctionParameter(name=name.strip(), type_hint=(hint or "").strip() or None,
                                     default=(default or "").strip() or None))
    return out


def _collect_decorators(lines: List[str], idx: int, indent: int) -> List[str]:
    decorators: List[str] = []
    j = idx - 2
    while j >= 0:
        line = lines[j].rstrip()
        if not line.strip():
            j -= 1
            continue
        cur_indent = len(line) - len(line.lstrip())
        if cur_indent != indent or not line.lstrip().startswith("@"):
            break
        decorators.insert(0, line.lstrip())
        j -= 1
    return decorators


def _find_block_end(lines: List[str], start_idx: int) -> int:
    if start_idx > len(lines):
        return start_idx
    head_indent = len(lines[start_idx - 1]) - len(lines[start_idx - 1].lstrip())
    end = start_idx
    for j in range(start_idx, len(lines)):
        cur = lines[j]
        if cur.strip() == "":
            end = j + 1
            continue
        cur_indent = len(cur) - len(cur.lstrip())
        if cur_indent <= head_indent and j > start_idx - 1:
            return end
        end = j + 1
    return end


def _extract_python_docstring(lines: List[str], start_idx: int, end_idx: int) -> str | None:
    if start_idx >= len(lines):
        return None
    j = start_idx
    while j < end_idx and not lines[j].strip():
        j += 1
    if j >= end_idx:
        return None
    line = lines[j].strip()
    m = re.match(r"[ru]?(['\"]{3})(.*)\1$", line)
    if not m:
        return None
    inner = [m.group(2)]
    if line.count(m.group(1)) == 2:
        return inner[0]
    j += 1
    while j < end_idx and m.group(1) not in lines[j]:
        inner.append(lines[j])
        j += 1
    if j < end_idx:
        end_line = lines[j].split(m.group(1))[0]
        inner.append(end_line)
    return "\n".join(inner).strip()


def idx_quote_match(line: str) -> int:
    """Helper used to locate the closing quote on a single line."""
    for q in ('"""', "'''"):
        idx = line.find(q)
        if idx >= 0:
            return idx + len(q)
    return -1


# ----------------------------------------------------------------------------
# Java
# ----------------------------------------------------------------------------


@register_analyser("java")
def analyse_java(file_path: str, source: str) -> ParsedFile:
    parsed = ParsedFile(file_path=file_path, language="java")
    lines = source.splitlines()

    package_qname = ""
    for raw in lines:
        m = re.match(r"\s*package\s+([\w.]+);", raw)
        if m:
            package_qname = m.group(1)
            break
        m2 = re.match(r"\s*import\s+([\w.*]+);", raw)
        if m2:
            parsed.imports.append(m2.group(1))

    class_re = re.compile(r"(?P<mods>(?:public|private|protected|static|final|abstract)\s+)*"
                          r"class\s+(?P<name>\w+)(?:\s*<[^>]+>)?\s*(?:\sextends\s+\w+(?:<[^>]+>)?)?"
                          r"(?:\simplements\s+[\w<>,\s]+)?\s*\{")
    method_re = re.compile(r"(?P<mods>(?:public|private|protected|static|final|abstract|synchronized)\s+)*"
                           r"(?P<ret>[\w<>,\[\]\s]+?)\s+(?P<name>\w+)\s*\((?P<params>[^)]*)\)"
                           r"\s*(?:throws\s+[\w.,\s]+)?\s*\{")

    stack: list[tuple[int, str | None]] = []
    for idx, raw in enumerate(lines, start=1):
        line = raw.rstrip()
        if not line.strip():
            continue
        indent = _java_indent(line)
        while stack and stack[-1][0] >= indent:
            stack.pop()
        parent = stack[-1][1] if stack else package_qname or None

        if m := class_re.search(line):
            name = m.group("name")
            end_line = _find_brace_end(lines, idx)
            entity = CodeEntity(
                qualified_name=f"{parent}.{name}" if parent else name,
                simple_name=name,
                kind=CodeEntityKind.CLASS,
                file_path=file_path,
                language="java",
                start_line=idx,
                end_line=end_line,
                signature=line.strip(),
                modifiers=[w for w in m.group("mods").split() if w],
                metadata={"package": package_qname},
            )
            if parent:
                entity.parent_qualified_name = parent
            parsed.entities.append(entity)
            stack.append((indent, entity.qualified_name))
            continue

        if m := method_re.search(line):
            name = m.group("name")
            return_type = m.group("ret").strip()
            params_raw = m.group("params")
            end_line = _find_brace_end(lines, idx)
            entity = CodeEntity(
                qualified_name=f"{parent}.{name}" if parent else name,
                simple_name=name,
                kind=CodeEntityKind.METHOD,
                file_path=file_path,
                language="java",
                start_line=idx,
                end_line=end_line,
                signature=line.strip(),
                modifiers=[w for w in m.group("mods").split() if w],
                return_type=return_type,
                parameters=_parse_java_params(params_raw),
                parent_qualified_name=parent,
            )
            parsed.entities.append(entity)

    return parsed


def _java_indent(line: str) -> int:
    """Approximate brace-based indentation level."""
    return line.count("{") - line.count("}")


def _parse_java_params(raw: str) -> List[FunctionParameter]:
    out: List[FunctionParameter] = []
    for chunk in raw.split(","):
        chunk = chunk.strip()
        if not chunk:
            continue
        parts = chunk.split()
        if len(parts) >= 2:
            hint = parts[-2]
            name = parts[-1]
        else:
            hint, name = None, parts[0]
        out.append(FunctionParameter(name=name, type_hint=hint, default=None))
    return out


def _find_brace_end(lines: List[str], start_idx: int) -> int:
    depth = 0
    started = False
    for j in range(start_idx - 1, len(lines)):
        for ch in lines[j]:
            if ch == "{":
                depth += 1
                started = True
            elif ch == "}":
                depth -= 1
        if started and depth <= 0:
            return j + 1
    return len(lines)


# ----------------------------------------------------------------------------
# TypeScript / JavaScript (regex based — proper parser would be tree-sitter)
# ----------------------------------------------------------------------------


@register_analyser("typescript")
@register_analyser("javascript")
def analyse_ts_js(file_path: str, source: str) -> ParsedFile:
    language = "typescript" if file_path.endswith((".ts", ".tsx")) else "javascript"
    parsed = ParsedFile(file_path=file_path, language=language)
    lines = source.splitlines()

    import_re = re.compile(r"^\s*import\s+(?:.+?\s+from\s+)?['\"]([^'\"]+)['\"]")
    class_re = re.compile(r"(?:export\s+)?(?:abstract\s+)?class\s+(\w+)(?:\s+extends\s+\w+)?(?:\s+implements\s+[\w,\s]+)?\s*\{")
    fn_re = re.compile(
        r"(?:export\s+)?(?:async\s+)?function\s+(?P<name>\w+)\s*\((?P<params>[^)]*)\)"
        r"(?:\s*:\s*(?P<ret>[^{=]+))?\s*\{"
    )
    method_re = re.compile(
        r"^\s+(?P<mods>public|private|protected|static|readonly|async)?\s*"
        r"(?P<name>\w+)\s*\((?P<params>[^)]*)\)\s*:\s*(?P<ret>[^;{]+)\s*\{"
    )
    decorator_re = re.compile(r"^\s*@([\w.]+)\s*$")

    for idx, raw in enumerate(lines, start=1):
        line = raw.rstrip()
        if not line.strip():
            continue

        if m := import_re.match(line):
            parsed.imports.append(m.group(1))
            continue

        if m := class_re.search(line):
            name = m.group(1)
            end_line = _find_brace_end(lines, idx)
            parsed.entities.append(CodeEntity(
                qualified_name=name,
                simple_name=name,
                kind=CodeEntityKind.CLASS,
                file_path=file_path,
                language=language,
                start_line=idx,
                end_line=end_line,
                signature=line.strip(),
                decorators=[d.group(1) for d in (decorator_re.match(l) for l in lines[max(0, idx-3):idx-1]) if d],
            ))
            continue

        if m := fn_re.search(line):
            params = _parse_ts_params(m.group("params"))
            parsed.entities.append(CodeEntity(
                qualified_name=m.group("name"),
                simple_name=m.group("name"),
                kind=CodeEntityKind.FUNCTION,
                file_path=file_path,
                language=language,
                start_line=idx,
                end_line=_find_brace_end(lines, idx),
                signature=line.strip(),
                parameters=params,
                return_type=(m.group("ret") or "").strip() or None,
            ))
            continue

        if m := method_re.match(line):
            params = _parse_ts_params(m.group("params"))
            parsed.entities.append(CodeEntity(
                qualified_name=m.group("name"),
                simple_name=m.group("name"),
                kind=CodeEntityKind.METHOD,
                file_path=file_path,
                language=language,
                start_line=idx,
                end_line=_find_brace_end(lines, idx),
                signature=line.strip(),
                parameters=params,
                return_type=m.group("ret").strip() or None,
                modifiers=[m.group("mods")] if m.group("mods") else [],
            ))

    return parsed


def _parse_ts_params(raw: str) -> List[FunctionParameter]:
    out: List[FunctionParameter] = []
    depth = 0
    current = ""
    parts: List[str] = []
    for ch in raw:
        if ch in "([{<":
            depth += 1
        elif ch in ")]}>":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(current.strip())
            current = ""
        else:
            current += ch
    if current.strip():
        parts.append(current.strip())
    for chunk in parts:
        if not chunk or chunk in ("self", "this"):
            continue
        if "=" in chunk:
            name_part, default = chunk.split("=", 1)
        else:
            name_part, default = chunk, None
        if ":" in name_part:
            name, hint = name_part.split(":", 1)
        else:
            name, hint = name_part, None
        out.append(FunctionParameter(name=name.strip(), type_hint=(hint or "").strip() or None,
                                     default=(default or "").strip() or None))
    return out