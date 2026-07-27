"""Test import setup for the source-directory Bridge layout."""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path


BRIDGE_ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = BRIDGE_ROOT / "src"
sys.path.insert(0, str(SOURCE_ROOT))


def make_test_recaf_jar(root: Path) -> Path:
    """Create the smallest archive accepted as an executable Recaf JAR."""
    recaf_jar = root / "recaf.jar"
    recaf_jar.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(recaf_jar, "w") as archive:
        archive.writestr(
            "META-INF/MANIFEST.MF",
            "Manifest-Version: 1.0\r\n"
            "Main-Class: software.coley.recaf.Main\r\n\r\n",
        )
        archive.writestr("software/coley/recaf/Main.class", b"test")
    return recaf_jar.resolve()


def make_test_plugin_jar(root: Path, name: str = "recaf-mcp-plugin.jar") -> Path:
    """Create a minimal Recaf MCP plugin archive for configuration tests."""
    plugin_jar = root / name
    plugin_jar.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(plugin_jar, "w") as archive:
        archive.writestr(
            "META-INF/services/software.coley.recaf.plugin.Plugin",
            "me.naptila.recafmcp.RecafMCPPlugin",
        )
    return plugin_jar.resolve()
