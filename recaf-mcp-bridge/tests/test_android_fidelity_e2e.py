from __future__ import annotations

import os
import re
import zipfile
from pathlib import Path

import anyio
import pytest

from application import BridgeToolService
from config import BridgeConfig


@pytest.mark.skipif(
    "RECAF_MCP_TEST_ANDROID_FIDELITY_DIR" not in os.environ,
    reason="independently encoded Android real-Worker fixtures not requested",
)
@pytest.mark.parametrize("output_format", ["apk", "dex"])
def test_android_fidelity_mapping_patch_and_reopen(tmp_path: Path, output_format: str) -> None:
    input_path = Path(os.environ["RECAF_MCP_TEST_ANDROID_FIDELITY_DIR"]) / f"fidelity.{output_format}"
    original = input_path.read_bytes()
    config = BridgeConfig(
        recaf_jar=Path(os.environ["RECAF_MCP_TEST_RECAF_JAR"]),
        plugin_jar=Path(os.environ["RECAF_MCP_TEST_PLUGIN_JAR"]),
        session_root=tmp_path / "sessions", max_sessions=1,
    )

    async def scenario() -> None:
        service = BridgeToolService(config)

        async def call(name: str, arguments: dict) -> dict:
            response = await service.call(name, arguments)
            assert not response.isError, response
            return response.structuredContent

        try:
            opened = await call("recaf_open", {"input_path": str(input_path)})
            database = opened["database"]
            unsupported = await service.call("transform_workspace", {"database": database})
            assert unsupported.isError
            assert "Android/DEX transformations are not supported" in unsupported.structuredContent["error"]
            method = {"database": database, "scope": "android", "owner": "sample/Fidelity",
                      "name": "mix", "descriptor": "(I)I"}
            before = await call("get_method_assembly", method)
            mapping = {"database": database, "scope": "android", "format": "Simple",
                       "mappings": "sample/Fidelity sample/Renamed\n"}
            preview = await call("apply_mappings", {**mapping, "dry_run": True})
            expected_changes = 3 if output_format == "apk" else 2
            assert preview["changed_classes"] == expected_changes
            unchanged = await call("get_method_assembly", method)
            assert unchanged["assembly_sha256"] == before["assembly_sha256"]
            renamed = await call("rename_symbol", {
                "database": database, "scope": "android", "kind": "class",
                "name": "sample/Fidelity", "new_name": "sample/Renamed",
            })
            assert renamed["changed_classes"] == expected_changes
            mapped = await call("apply_mappings", {
                **mapping, "mappings": "sample/Renamed sample/Mapped\n",
            })
            assert mapped["changed_classes"] == expected_changes
            method["owner"] = "sample/Mapped"
            body = await call("get_method_assembly", method)
            assembly, count = re.subn(r"(?<![\w])17(?![\w])", "18", body["assembly"], count=1)
            assert count == 1, body["assembly"]
            patch = {**method, "assembly": assembly, "expected_assembly_sha256": body["assembly_sha256"]}
            await call("patch_instructions", {**patch, "dry_run": True})
            await call("patch_instructions", patch)
            sources = await call("export_sources", {
                "database": database, "scope": "android", "output_path": str(tmp_path / "sources.zip"),
            })
            assert sources["complete"] is True, sources
            output = tmp_path / f"saved.{output_format}"
            await call("recaf_save", {"database": database, "output_path": str(output)})
            await call("recaf_close", {"database": database, "save": False})
            reopened = await call("recaf_open", {"input_path": str(output)})
            database = reopened["database"]
            body = await call("get_method_assembly", {**method, "database": database})
            assert re.search(r"(?<![\w])18(?![\w])", body["assembly"])
            for caller in (["sample/Caller", "sample/OtherCaller"] if output_format == "apk" else ["sample/Caller"]):
                assembly = await call("get_method_assembly", {
                    "database": database, "scope": "android", "owner": caller,
                    "name": "evaluate", "descriptor": "()I",
                })
                assert "sample/Mapped" in assembly["assembly"]
                assert "sample/Fidelity" not in assembly["assembly"]
                assert "sample/Renamed" not in assembly["assembly"]
            await call("recaf_close", {"database": database, "save": False})
            assert input_path.read_bytes() == original
        finally:
            await service.close()

    anyio.run(scenario)


@pytest.mark.skipif(
    "RECAF_MCP_TEST_ANDROID_FIDELITY_DIR" not in os.environ,
    reason="independently encoded Android real-Worker fixtures not requested",
)
@pytest.mark.parametrize("output_format", ["apk", "dex"])
def test_move_object_16_export_and_reopen(tmp_path: Path, output_format: str) -> None:
    input_path = Path(os.environ["RECAF_MCP_TEST_ANDROID_FIDELITY_DIR"]) / f"move-object-16.{output_format}"
    original = input_path.read_bytes()
    config = BridgeConfig(
        recaf_jar=Path(os.environ["RECAF_MCP_TEST_RECAF_JAR"]),
        plugin_jar=Path(os.environ["RECAF_MCP_TEST_PLUGIN_JAR"]),
        session_root=tmp_path / "sessions", max_sessions=1,
    )

    async def scenario() -> None:
        service = BridgeToolService(config)

        async def call(name: str, arguments: dict) -> dict:
            response = await service.call(name, arguments)
            assert not response.isError, response
            return response.structuredContent

        try:
            opened = await call("recaf_open", {"input_path": str(input_path)})
            method = {"database": opened["database"], "scope": "android", "owner": "sample/WideRegisters",
                      "name": "copy", "descriptor": "(Ljava/lang/Object;)Ljava/lang/Object;"}
            before = await call("get_method_assembly", method)
            # Exercise the modified-workspace exporter, not an input-file copy.
            await call("rename_symbol", {"database": opened["database"], "scope": "android", "kind": "class",
                                         "name": "sample/WideRegisters", "new_name": "sample/RenamedRegisters"})
            output = tmp_path / f"saved.{output_format}"
            await call("recaf_save", {"database": opened["database"], "output_path": str(output)})
            await call("recaf_close", {"database": opened["database"], "save": False})
            reopened = await call("recaf_open", {"input_path": str(output)})
            after = await call("get_method_assembly", {**method, "database": reopened["database"],
                                                       "owner": "sample/RenamedRegisters"})
            assert re.search(r"move-object\s+v300\s+p0", before["assembly"])
            assert after["assembly"] == before["assembly"]
            assert after["instruction_count"] == before["instruction_count"]
            await call("recaf_close", {"database": reopened["database"], "save": False})
            assert input_path.read_bytes() == original
        finally:
            await service.close()

    anyio.run(scenario)


@pytest.mark.skipif(
    "RECAF_MCP_TEST_ANDROID_FIDELITY_DIR" not in os.environ,
    reason="independently encoded Android real-Worker fixtures not requested",
)
def test_open_rejects_an_invalid_secondary_dex_and_releases_capacity(tmp_path: Path) -> None:
    fixture = Path(os.environ["RECAF_MCP_TEST_ANDROID_FIDELITY_DIR"]) / "fidelity.dex"
    broken_apk = tmp_path / "incomplete.apk"
    with zipfile.ZipFile(broken_apk, "w") as archive:
        archive.writestr("classes.dex", fixture.read_bytes())
        archive.writestr("classes2.dex", b"dex\n035\x00")
    config = BridgeConfig(
        recaf_jar=Path(os.environ["RECAF_MCP_TEST_RECAF_JAR"]),
        plugin_jar=Path(os.environ["RECAF_MCP_TEST_PLUGIN_JAR"]),
        session_root=tmp_path / "sessions", max_sessions=1,
    )

    async def scenario() -> None:
        service = BridgeToolService(config)
        try:
            opened = await service.call("recaf_open", {"input_path": str(broken_apk)})
            assert opened.isError
            assert "DEX import failed for classes2.dex" in opened.structuredContent["message"]
            assert "classes2.dex" in opened.structuredContent["message"]
            listed = await service.call("recaf_list", {})
            assert listed.structuredContent["occupied_slots"] == 0
            assert listed.structuredContent["pending_sessions"] == []
            assert not any(config.session_root.iterdir())
        finally:
            await service.close()

    anyio.run(scenario)
