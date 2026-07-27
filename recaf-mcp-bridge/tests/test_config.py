from __future__ import annotations

from pathlib import Path

import pytest

import config as config_module
from config import BridgeConfig
from conftest import make_test_plugin_jar, make_test_recaf_jar


def test_environment_config_has_bounded_worker_limits(tmp_path: Path) -> None:
    recaf_jar = make_test_recaf_jar(tmp_path)
    plugin_dir = tmp_path / "recaf-plugins"
    plugin_jar = make_test_plugin_jar(
        plugin_dir,
        "recaf-mcp-plugin-1.0.0.jar",
    )
    config = BridgeConfig.from_env(
        {
            "RECAF_JAR": str(recaf_jar),
            "RECAF_PLUGIN_DIR": str(plugin_dir),
            "RECAF_SESSION_ROOT": str(tmp_path / "sessions"),
            "RECAF_MAX_SESSIONS": "3",
            "RECAF_WORKER_MAX_HEAP_MB": "2048",
            "RECAF_IDLE_TTL_SEC": "0",
            "XDG_STATE_HOME": str(tmp_path / "state"),
        }
    )

    assert config.recaf_jar == recaf_jar
    assert config.plugin_jar == plugin_jar
    assert config.max_sessions == 3
    assert config.worker_max_heap_mb == 2048
    assert config.default_idle_ttl_sec == 0
    assert config.response_profile == "compact"
    assert config.effective_recovery_dir == (
        tmp_path / "state" / "recaf-mcp" / "recovery"
    ).resolve()


def test_environment_config_supports_legacy_response_profile(
    tmp_path: Path,
) -> None:
    recaf_jar = make_test_recaf_jar(tmp_path)
    plugin_jar = make_test_plugin_jar(tmp_path / "plugins")

    config = BridgeConfig.from_env(
        {
            "RECAF_JAR": str(recaf_jar),
            "RECAF_PLUGIN_JAR": str(plugin_jar),
            "RECAF_RESPONSE_PROFILE": "LEGACY",
        }
    )

    assert config.response_profile == "legacy"

    with pytest.raises(ValueError, match="RECAF_RESPONSE_PROFILE"):
        BridgeConfig.from_env(
            {
                "RECAF_JAR": str(recaf_jar),
                "RECAF_PLUGIN_JAR": str(plugin_jar),
                "RECAF_RESPONSE_PROFILE": "verbose",
            }
        )


def test_environment_config_rejects_missing_recaf_jar_and_bad_limits(
    tmp_path: Path,
) -> None:
    with pytest.raises(ValueError, match="RECAF_JAR is required"):
        BridgeConfig.from_env({})

    recaf_jar = make_test_recaf_jar(tmp_path)
    plugin_jar = make_test_plugin_jar(tmp_path / "plugins")
    with pytest.raises(ValueError, match="RECAF_MAX_SESSIONS"):
        BridgeConfig.from_env(
            {
                "RECAF_JAR": str(recaf_jar),
                "RECAF_PLUGIN_JAR": str(plugin_jar),
                "RECAF_MAX_SESSIONS": "0",
            }
        )


def test_explicit_plugin_jar_supports_custom_install_layout(tmp_path: Path) -> None:
    recaf_jar = make_test_recaf_jar(tmp_path)
    plugin = make_test_plugin_jar(tmp_path, "external-plugin.jar")

    config = BridgeConfig.from_env(
        {
            "RECAF_JAR": str(recaf_jar),
            "RECAF_PLUGIN_JAR": str(plugin),
        }
    )

    assert config.effective_plugin_jar == plugin.resolve()


def test_installed_plugin_discovery_rejects_ambiguous_versions(
    tmp_path: Path,
) -> None:
    recaf_jar = make_test_recaf_jar(tmp_path)
    plugin_dir = tmp_path / "plugins"
    make_test_plugin_jar(plugin_dir, "recaf-mcp-plugin-1.0.0.jar")
    make_test_plugin_jar(plugin_dir, "recaf-mcp-plugin-2.0.0.jar")

    with pytest.raises(ValueError, match="exactly one"):
        BridgeConfig.from_env(
            {
                "RECAF_JAR": str(recaf_jar),
                "RECAF_PLUGIN_DIR": str(plugin_dir),
            }
        )


def test_linux_standard_plugin_directory_needs_only_recaf_jar(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(config_module.sys, "platform", "linux")
    recaf_jar = make_test_recaf_jar(tmp_path)
    plugin_jar = make_test_plugin_jar(
        tmp_path / ".config" / "Recaf" / "plugins",
        "recaf-mcp-plugin-1.0.0.jar",
    )

    config = BridgeConfig.from_env(
        {
            "HOME": str(tmp_path),
            "RECAF_JAR": str(recaf_jar),
        }
    )

    assert config.plugin_jar == plugin_jar


def test_config_rejects_non_executable_recaf_jar(tmp_path: Path) -> None:
    recaf_jar = tmp_path / "recaf.jar"
    recaf_jar.write_bytes(b"not a zip")
    plugin_jar = make_test_plugin_jar(tmp_path / "plugins")

    with pytest.raises(ValueError, match="executable Recaf JAR"):
        BridgeConfig.from_env(
            {
                "RECAF_JAR": str(recaf_jar),
                "RECAF_PLUGIN_JAR": str(plugin_jar),
            }
        )
