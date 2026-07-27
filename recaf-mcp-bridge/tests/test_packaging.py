from __future__ import annotations

import tomllib

from conftest import BRIDGE_ROOT


def test_wheel_declares_every_runtime_module() -> None:
    configuration = tomllib.loads((BRIDGE_ROOT / "pyproject.toml").read_text())
    declared = set(configuration["tool"]["setuptools"]["py-modules"])
    runtime_modules = {
        path.stem
        for path in (BRIDGE_ROOT / "src").glob("*.py")
        if path.name != "__init__.py"
    }

    assert declared == runtime_modules
    assert configuration["project"]["scripts"]["recaf-mcp-bridge"] == "main:main"
