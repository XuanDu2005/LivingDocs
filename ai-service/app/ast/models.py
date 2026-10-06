"""Dataclasses shared by AST analysers."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import List, Optional


class CodeEntityKind(str, Enum):
    """Coarse classification of a code element."""

    MODULE = "MODULE"
    CLASS = "CLASS"
    INTERFACE = "INTERFACE"
    FUNCTION = "FUNCTION"
    METHOD = "METHOD"
    VARIABLE = "VARIABLE"
    CONSTANT = "CONSTANT"
    ENDPOINT = "ENDPOINT"  # HTTP route / RPC handler


@dataclass
class FunctionParameter:
    name: str
    type_hint: Optional[str] = None
    default: Optional[str] = None


@dataclass
class CodeEntity:
    """A single code element extracted from a source file."""

    qualified_name: str
    simple_name: str
    kind: CodeEntityKind
    file_path: str
    language: str
    start_line: int
    end_line: int
    signature: str = ""
    docstring: Optional[str] = None
    decorators: List[str] = field(default_factory=list)
    modifiers: List[str] = field(default_factory=list)
    parameters: List[FunctionParameter] = field(default_factory=list)
    return_type: Optional[str] = None
    parent_qualified_name: Optional[str] = None
    metadata: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        return {
            "qualified_name": self.qualified_name,
            "simple_name": self.simple_name,
            "kind": self.kind.value,
            "file_path": self.file_path,
            "language": self.language,
            "start_line": self.start_line,
            "end_line": self.end_line,
            "signature": self.signature,
            "docstring": self.docstring,
            "decorators": list(self.decorators),
            "modifiers": list(self.modifiers),
            "parameters": [
                {
                    "name": p.name,
                    "type_hint": p.type_hint,
                    "default": p.default,
                }
                for p in self.parameters
            ],
            "return_type": self.return_type,
            "parent_qualified_name": self.parent_qualified_name,
            "metadata": dict(self.metadata),
        }


@dataclass
class ParsedFile:
    """All entities found in a single source file."""

    file_path: str
    language: str
    entities: List[CodeEntity] = field(default_factory=list)
    imports: List[str] = field(default_factory=list)
    module_docstring: Optional[str] = None

    def to_dict(self) -> dict:
        return {
            "file_path": self.file_path,
            "language": self.language,
            "imports": list(self.imports),
            "module_docstring": self.module_docstring,
            "entities": [e.to_dict() for e in self.entities],
        }