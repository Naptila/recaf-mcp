"""Validated Bridge process and Worker launch configuration."""

from __future__ import annotations

import os
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping


PLUGIN_SERVICE = "META-INF/services/software.coley.recaf.plugin.Plugin"
PLUGIN_MAIN_CLASS = "me.naptila.recafmcp.RecafMCPPlugin"
RESPONSE_PROFILES = frozenset({"compact", "legacy"})


def response_profile_from_env(environment: Mapping[str, str]) -> str:
    """Return the validated process-wide MCP response profile."""
    profile = environment.get("RECAF_RESPONSE_PROFILE", "compact").strip().lower()
    if profile not in RESPONSE_PROFILES:
        choices = ", ".join(sorted(RESPONSE_PROFILES))
        raise ValueError(f"RECAF_RESPONSE_PROFILE must be one of: {choices}")
    return profile


def _default_recaf_plugin_dir(environment: Mapping[str, str]) -> Path:
    """Match Recaf's platform-specific user plugin directory."""
    override = environment.get("RECAF_PLUGIN_DIR")
    if override:
        return Path(override).expanduser()

    recaf_home = environment.get("RECAF")
    if recaf_home:
        return Path(recaf_home).expanduser() / "plugins"

    home = Path(environment.get("HOME", str(Path.home()))).expanduser()
    if sys.platform == "win32":  # pragma: no cover - exercised on Windows hosts
        appdata = environment.get("APPDATA")
        if not appdata:
            raise ValueError(
                "APPDATA is required to locate Recaf plugins; set RECAF_PLUGIN_DIR"
            )
        base = Path(appdata)
    elif sys.platform == "darwin":  # pragma: no cover - exercised on macOS hosts
        base = home / "Library" / "Application Support"
    else:
        xdg_config = environment.get("XDG_CONFIG_HOME")
        base = Path(xdg_config).expanduser() if xdg_config else home / ".config"
    return base / "Recaf" / "plugins"


def _installed_plugin_jar(environment: Mapping[str, str]) -> Path:
    """Resolve one explicit or conventionally installed Recaf MCP plugin."""
    plugin_value = environment.get("RECAF_PLUGIN_JAR")
    if plugin_value:
        return Path(plugin_value).expanduser().resolve(strict=True)

    plugin_dir = _default_recaf_plugin_dir(environment).resolve(strict=False)
    matches = sorted(
        path.resolve(strict=True)
        for path in plugin_dir.glob("recaf-mcp-plugin*.jar")
        if path.is_file()
    )
    if len(matches) != 1:
        raise ValueError(
            f"{plugin_dir} must contain exactly one recaf-mcp-plugin*.jar; "
            "set RECAF_PLUGIN_JAR or RECAF_PLUGIN_DIR to override discovery"
        )
    return matches[0]


def _bounded_int(
    environment: Mapping[str, str],
    name: str,
    default: int,
    minimum: int,
    maximum: int,
) -> int:
    raw = environment.get(name, str(default))
    try:
        value = int(raw)
    except ValueError as error:
        raise ValueError(f"{name} must be an integer") from error
    if not minimum <= value <= maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


@dataclass(frozen=True, slots=True)
class BridgeConfig:
    """Configuration needed to launch isolated Recaf Worker processes."""

    recaf_jar: Path
    plugin_jar: Path
    session_root: Path
    recovery_dir: Path | None = None
    java_command: str = "java"
    max_sessions: int = 2
    worker_max_heap_mb: int = 1024
    default_idle_ttl_sec: int = 600
    response_profile: str = "compact"
    launch_timeout_sec: float = 60.0
    launch_attempts: int = 3
    readiness_poll_sec: float = 0.2

    @property
    def effective_plugin_jar(self) -> Path:
        """Return the validated plugin selected during configuration loading."""
        return self.plugin_jar

    @property
    def effective_recovery_dir(self) -> Path:
        """Return the explicit state directory or a test-local fallback."""
        if self.recovery_dir is not None:
            return self.recovery_dir
        return (self.session_root.parent / "recovery").resolve(strict=False)

    def validate(self) -> "BridgeConfig":
        """Return this configuration after validating launch-critical paths."""
        recaf_jar = self.recaf_jar.resolve(strict=True)
        if recaf_jar != self.recaf_jar:
            raise ValueError("RECAF_JAR must be canonical")
        if not recaf_jar.is_file() or recaf_jar.suffix.lower() != ".jar":
            raise ValueError("RECAF_JAR must name a regular JAR file")
        try:
            with zipfile.ZipFile(recaf_jar) as archive:
                manifest = archive.read("META-INF/MANIFEST.MF").decode(
                    "utf-8", errors="replace"
                )
                if "software/coley/recaf/Main.class" not in archive.namelist():
                    raise ValueError("RECAF_JAR contains no Recaf Main class")
        except (KeyError, zipfile.BadZipFile) as error:
            raise ValueError("RECAF_JAR is not an executable Recaf JAR") from error
        if not any(
            line.strip() == "Main-Class: software.coley.recaf.Main"
            for line in manifest.splitlines()
        ):
            raise ValueError("RECAF_JAR manifest has no Recaf Main-Class")

        plugin_jar = self.plugin_jar.resolve(strict=True)
        if not plugin_jar.is_file():
            raise ValueError("RECAF_PLUGIN_JAR must be a regular file")
        if plugin_jar.suffix.lower() != ".jar":
            raise ValueError("RECAF_PLUGIN_JAR must name a JAR")
        if plugin_jar != self.plugin_jar:
            raise ValueError("RECAF_PLUGIN_JAR must be canonical")
        try:
            with zipfile.ZipFile(plugin_jar) as archive:
                service = archive.read(PLUGIN_SERVICE).decode("utf-8").strip()
        except (KeyError, UnicodeDecodeError, zipfile.BadZipFile) as error:
            raise ValueError("RECAF_PLUGIN_JAR has no valid plugin service") from error
        if service != PLUGIN_MAIN_CLASS:
            raise ValueError("RECAF_PLUGIN_JAR contains the wrong plugin service")
        if not self.effective_recovery_dir.is_absolute():
            raise ValueError("recovery directory must be absolute")
        if not self.java_command:
            raise ValueError("RECAF_JAVA_COMMAND must not be empty")
        if not 1 <= self.max_sessions <= 32:
            raise ValueError("max_sessions must be between 1 and 32")
        if not 128 <= self.worker_max_heap_mb <= 65536:
            raise ValueError("worker_max_heap_mb must be between 128 and 65536")
        if not 0 <= self.default_idle_ttl_sec <= 604800:
            raise ValueError("default_idle_ttl_sec must be between 0 and 604800")
        if self.response_profile not in RESPONSE_PROFILES:
            choices = ", ".join(sorted(RESPONSE_PROFILES))
            raise ValueError(f"response_profile must be one of: {choices}")
        if not 1 <= self.launch_attempts <= 10:
            raise ValueError("launch_attempts must be between 1 and 10")
        if self.launch_timeout_sec <= 0 or self.readiness_poll_sec <= 0:
            raise ValueError("launch timing values must be positive")
        return self

    @classmethod
    def from_env(
        cls,
        environment: Mapping[str, str] | None = None,
    ) -> "BridgeConfig":
        """Load bounded settings and an installed Recaf from the environment."""
        env = os.environ if environment is None else environment
        recaf_value = env.get("RECAF_JAR")
        if not recaf_value:
            raise ValueError("RECAF_JAR is required")
        recaf_jar = Path(recaf_value).expanduser().resolve(strict=True)
        plugin_jar = _installed_plugin_jar(env)

        session_value = env.get("RECAF_SESSION_ROOT")
        if session_value:
            session_root = Path(session_value).expanduser().resolve(strict=False)
        else:
            session_root = (
                Path(tempfile.gettempdir())
                / f"recaf-mcp-{getattr(os, 'getuid', lambda: 0)()}"
                / "sessions"
            ).resolve(strict=False)

        recovery_value = env.get("RECAF_RECOVERY_DIR")
        if recovery_value:
            recovery_dir = Path(recovery_value).expanduser().resolve(strict=False)
        else:
            state_home = env.get("XDG_STATE_HOME")
            state_root = (
                Path(state_home).expanduser()
                if state_home
                else Path.home() / ".local" / "state"
            )
            recovery_dir = (state_root / "recaf-mcp" / "recovery").resolve(
                strict=False
            )

        config = cls(
            recaf_jar=recaf_jar,
            plugin_jar=plugin_jar,
            session_root=session_root,
            recovery_dir=recovery_dir,
            java_command=env.get("RECAF_JAVA_COMMAND", "java"),
            max_sessions=_bounded_int(
                env, "RECAF_MAX_SESSIONS", 2, minimum=1, maximum=32
            ),
            worker_max_heap_mb=_bounded_int(
                env, "RECAF_WORKER_MAX_HEAP_MB", 1024, minimum=128, maximum=65536
            ),
            default_idle_ttl_sec=_bounded_int(
                env, "RECAF_IDLE_TTL_SEC", 600, minimum=0, maximum=604800
            ),
            response_profile=response_profile_from_env(env),
            launch_timeout_sec=float(
                _bounded_int(
                    env, "RECAF_LAUNCH_TIMEOUT_SEC", 60, minimum=1, maximum=600
                )
            ),
        )
        return config.validate()
