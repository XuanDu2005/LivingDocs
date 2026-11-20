"""Tests for the AST analysers shipped with the AI service."""

from __future__ import annotations

import pytest

from app.ast import analyser_for, supported_languages
from app.ast.models import CodeEntityKind


def test_supported_languages_includes_common_ones() -> None:
    languages = supported_languages()
    for lang in ("python", "java", "typescript", "javascript"):
        assert lang in languages


def test_analyser_for_unknown_returns_none() -> None:
    assert analyser_for("brainfuck") is None


def test_python_analyser_extracts_classes_and_functions() -> None:
    analyser = analyser_for("python")
    assert analyser is not None
    source = '''
"""Demo module."""

import os
from typing import List


class Calculator:
    """Adds two numbers."""

    def __init__(self, base: int) -> None:
        self.base = base

    def add(self, x: int, y: int = 1) -> int:
        return self.base + x + y


def standalone(value: int) -> str:
    return str(value)
'''
    parsed = analyser("calc.py", source)
    kinds = {e.simple_name: e.kind for e in parsed.entities}
    assert kinds["Calculator"] == CodeEntityKind.CLASS
    assert kinds["add"] == CodeEntityKind.METHOD
    assert kinds["standalone"] == CodeEntityKind.FUNCTION
    assert parsed.imports  # os / typing
    assert parsed.module_docstring is not None
    assert "Demo" in parsed.module_docstring


def test_java_analyser_extracts_class_and_method() -> None:
    analyser = analyser_for("java")
    assert analyser is not None
    source = '''
package com.example;

public class Greeter {
    public String greet(String name) {
        return "Hello, " + name;
    }
}
'''
    parsed = analyser("Greeter.java", source)
    names = [e.simple_name for e in parsed.entities]
    assert "Greeter" in names
    assert "greet" in names


def test_typescript_analyser_extracts_class() -> None:
    analyser = analyser_for("typescript")
    assert analyser is not None
    source = '''
export class Counter {
  private count: number = 0;

  increment(): number {
    this.count += 1;
    return this.count;
  }
}
'''
    parsed = analyser("counter.ts", source)
    names = [e.simple_name for e in parsed.entities]
    assert "Counter" in names
    assert "increment" in names


def test_typescript_analyser_extracts_generic_function() -> None:
    """A generic parameter list must not hide the declaration.

    ``function useDebounce<T>(value: T)`` used to match nothing, so the
    whole file contributed zero entities. The AI was then asked to
    document code it had never been shown and answered that the source
    was missing.
    """
    analyser = analyser_for("typescript")
    assert analyser is not None
    source = '''
export function useDebounce<T>(value: T, delayMs = 300): T {
  return value;
}
'''
    parsed = analyser("useDebounce.ts", source)
    names = [e.simple_name for e in parsed.entities]
    assert "useDebounce" in names
    entity = next(e for e in parsed.entities if e.simple_name == "useDebounce")
    assert entity.start_line == 2


def test_typescript_analyser_extracts_generic_class() -> None:
    analyser = analyser_for("typescript")
    assert analyser is not None
    source = '''
export class Box<T> {
  constructor(private value: T) {}
}
'''
    parsed = analyser("box.ts", source)
    names = [e.simple_name for e in parsed.entities]
    assert "Box" in names


def test_typescript_analyser_extracts_generic_method() -> None:
    analyser = analyser_for("typescript")
    assert analyser is not None
    source = '''
export class ApiClient {
  async get<T>(path: string): Promise<T> {
    return null as unknown as T;
  }
}
'''
    parsed = analyser("client.ts", source)
    names = [e.simple_name for e in parsed.entities]
    assert "ApiClient" in names
    assert "get" in names


def test_typescript_analyser_extracts_arrow_function() -> None:
    """Arrow functions assigned to a binding are entities too.

    The backend's Java extractor already recognised this shape, so the AI
    service must as well — otherwise the two sides disagree about what an
    entity is and the indexed file yields no source for the model.
    """
    analyser = analyser_for("typescript")
    assert analyser is not None
    source = '''
export const double = (n: number): number => n * 2;
'''
    parsed = analyser("double.ts", source)
    names = [e.simple_name for e in parsed.entities]
    assert "double" in names


def test_typescript_analyser_ignores_plain_const_binding() -> None:
    """A `const` that is not a function is not an entity."""
    analyser = analyser_for("typescript")
    assert analyser is not None
    source = '''
export const timer = setTimeout(() => {}, 100);
export const count = 0;
'''
    parsed = analyser("timer.ts", source)
    names = [e.simple_name for e in parsed.entities]
    assert "timer" not in names
    assert "count" not in names


def test_typescript_analyser_records_correct_line_numbers() -> None:
    """The recorded line must be the declaration's own line.

    The patterns start with ``^\\s*``; in MULTILINE mode ``\\s`` also
    matches the newline that ended the previous line, so anchoring on the
    start of the whole match put every entity one line too early.
    """
    analyser = analyser_for("typescript")
    assert analyser is not None
    source = '''/**
 * A stat card.
 */
export interface StatItem {
  label: string;
}

export function StatCard({ label }: StatItem) {
  return label;
}
'''
    parsed = analyser("StatCard.tsx", source)
    by_name = {e.simple_name: e.start_line for e in parsed.entities}
    assert by_name["StatItem"] == 4
    assert by_name["StatCard"] == 8