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