"""Run the opt-in Worker suite against an explicit Recaf generic JAR."""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import anyio

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "recaf-mcp-bridge"
sys.path.insert(0, str(BRIDGE / "src"))

from config import BridgeConfig
from discovery import SchemaWorkerDiscovery


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--recaf-jar", type=Path, required=True)
    parser.add_argument("--plugin-jar", type=Path, required=True)
    parser.add_argument("--expected-recaf-commit")
    args = parser.parse_args()
    recaf = args.recaf_jar.resolve(strict=True)
    plugin = args.plugin_jar.resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix="recaf-mcp-test-") as temporary:
        root = Path(temporary)
        target = root / "target.jar"
        shutil.copy2(plugin, target)
        config = BridgeConfig(
            recaf_jar=recaf,
            plugin_jar=plugin,
            session_root=root / "sessions",
            max_sessions=1,
            launch_timeout_sec=60,
        )
        catalog = anyio.run(SchemaWorkerDiscovery(config).discover)
        identity = catalog.identity
        if args.expected_recaf_commit and identity.recaf_commit != args.expected_recaf_commit:
            raise RuntimeError(
                f"Expected Recaf {args.expected_recaf_commit}, got {identity.recaf_commit}"
            )
        print(f"Testing plugin {identity.plugin_build} on Recaf {identity.recaf_commit}", flush=True)
        fixture_source = ROOT / "recaf-mcp-plugin/src/test/java/me/naptila/recafmcp/AndroidFixture.java"
        jvm_source = fixture_source.with_name("JvmTransformFixture.java")
        subprocess.run(["javac", "-cp", str(recaf), "-d", str(root), str(fixture_source), str(jvm_source)], check=True)
        android = root / "android"
        subprocess.run([config.java_command, "-cp", os.pathsep.join((str(root), str(recaf))),
                        "me.naptila.recafmcp.AndroidFixture", str(android)], check=True)
        jvm = root / "transform.jar"
        subprocess.run([config.java_command, "-cp", os.pathsep.join((str(root), str(recaf))),
                        "me.naptila.recafmcp.JvmTransformFixture", str(jvm)], check=True)
        fidelity = root / "android-fidelity"
        plugin_module = ROOT / "recaf-mcp-plugin"
        wrapper = plugin_module / ("gradlew.bat" if os.name == "nt" else "gradlew")
        generated = subprocess.run(
            [str(wrapper), "--offline", "--no-daemon", "generateAndroidFidelityFixture",
             f"-PandroidFixtureOutput={fidelity}"], cwd=plugin_module,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=False,
        )
        if generated.returncode:
            raise RuntimeError("Independent DEX fixture generation failed:\n" + generated.stdout[-8000:])
        environment = {
            **os.environ,
            "RECAF_MCP_TEST_RECAF_JAR": str(recaf),
            "RECAF_MCP_TEST_PLUGIN_JAR": str(plugin),
            "RECAF_MCP_TEST_INPUT": str(target),
            "RECAF_MCP_TEST_PLUGIN_BUILD": identity.plugin_build,
            "RECAF_MCP_TEST_SCHEMA_FINGERPRINT": identity.schema_fingerprint,
            "RECAF_MCP_TEST_GENERATED_ANDROID_DIR": str(android),
            "RECAF_MCP_TEST_GENERATED_JVM_JAR": str(jvm),
            "RECAF_MCP_TEST_ANDROID_FIDELITY_DIR": str(fidelity),
        }
        return subprocess.run(
            [sys.executable, "-m", "pytest", "-q",
             "tests/test_launcher_integration.py", "tests/test_recovery_e2e.py",
             "tests/test_bridge_e2e.py", "tests/test_android_e2e.py", "tests/test_transform_e2e.py",
             "tests/test_android_fidelity_e2e.py", "tests/test_android_scripts_e2e.py"],
            cwd=BRIDGE, env=environment, check=False,
        ).returncode


if __name__ == "__main__":
    raise SystemExit(main())
