from __future__ import annotations

import os
import zipfile
from pathlib import Path

import anyio
import pytest

from application import BridgeToolService
from config import BridgeConfig


@pytest.mark.skipif(
    "RECAF_MCP_TEST_GENERATED_ANDROID_DIR" not in os.environ,
    reason="generated Android real-Worker fixtures not requested",
)
@pytest.mark.parametrize("output_format", ["apk", "dex"])
def test_android_mapping_save_close_and_reopen(tmp_path: Path, output_format: str) -> None:
    input_path = Path(os.environ["RECAF_MCP_TEST_GENERATED_ANDROID_DIR"]) / f"input.{output_format}"
    original = input_path.read_bytes()
    config = BridgeConfig(
        recaf_jar=Path(os.environ["RECAF_MCP_TEST_RECAF_JAR"]),
        plugin_jar=Path(os.environ["RECAF_MCP_TEST_PLUGIN_JAR"]),
        session_root=tmp_path / "sessions",
        launch_timeout_sec=60,
        max_sessions=1,
    )

    async def scenario() -> None:
        service = BridgeToolService(config)
        try:
            tools = {tool.name: tool for tool in await service.list_tools()}
            patch_schema = tools["patch_instructions"].inputSchema["properties"]
            assert patch_schema["scope"]["enum"] == ["jvm", "android"]
            assert patch_schema["dex_name"]["type"] == "string"
            opened = await service.call("recaf_open", {"input_path": str(input_path)})
            assert not opened.isError, opened
            meta = opened.structuredContent
            assert meta["output_format"] == output_format
            assert meta["workspace_archive_export_supported"] is True
            assert Path(meta["autosave_path"]).suffix == f".{output_format}"
            database = meta["database"]
            method = {
                "database": database, "scope": "android", "owner": "sample/Greeter",
                "name": "greet", "descriptor": "()V",
            }
            assembly = await service.call("get_method_assembly", method)
            assert not assembly.isError, assembly
            body = assembly.structuredContent
            assert body["assembly_format"] == "recaf_jasm_dalvik_method"
            assert body["editable"] is True
            structure = await service.call("search_structure", {
                "database": database, "scope": "android", "dex_name": body["dex_name"],
                "query": {"name": "sample/Greeter", "methods": [
                    {"name": "greet", "descriptor": "()V", "access_required": 9, "opcodes": [26, 14]},
                ]},
            })
            assert not structure.isError, structure
            assert structure.structuredContent["total"] == 1
            assert structure.structuredContent["hits"][0]["dex_name"] == body["dex_name"]
            method["dex_name"] = body["dex_name"]
            patch = {
                **method, "assembly": body["assembly"].replace('"before"', '"after"'),
                "expected_assembly_sha256": body["assembly_sha256"],
            }
            checked = await service.call("patch_instructions", {**patch, "dry_run": True})
            assert not checked.isError, checked
            assert checked.structuredContent["changed"] is True
            assert checked.structuredContent["applied"] is False
            unchanged = await service.call("get_method_assembly", method)
            assert unchanged.structuredContent["assembly_sha256"] == body["assembly_sha256"]
            applied = await service.call("patch_instructions", patch)
            assert not applied.isError, applied
            assert applied.structuredContent["applied"] is True
            stale = await service.call("patch_instructions", patch)
            assert stale.isError
            renamed = await service.call("rename_symbol", {
                "database": database, "kind": "class", "scope": "android",
                "name": "sample/Greeter", "new_name": "sample/Renamed",
            })
            assert not renamed.isError, renamed
            assert renamed.structuredContent["save_required"] is True
            assert renamed.structuredContent["in_memory_only"] is False
            wrong = tmp_path / "wrong.jar"
            rejected = await service.call("recaf_save", {"database": database, "output_path": str(wrong)})
            assert rejected.isError
            assert not wrong.exists()
            destination = tmp_path / f"output.{output_format}"
            saved = await service.call("recaf_save", {"database": database, "output_path": str(destination)})
            assert not saved.isError, saved
            assert saved.structuredContent["format"] == output_format
            assert saved.structuredContent["apk_signature_status"] == (
                "unsigned" if output_format == "apk" else "not_applicable"
            )
            closed = await service.call("recaf_close", {"database": database})
            assert not closed.isError, closed
            assert closed.structuredContent["saved"] is True
            assert Path(meta["autosave_path"]).exists()
            reopened = await service.call("recaf_open", {"input_path": str(destination)})
            assert not reopened.isError, reopened
            database = reopened.structuredContent["database"]
            # A bare DEX bundle is named after its input file, which changed on export.
            method.pop("dex_name")
            reloaded = await service.call("get_method_assembly", {
                **method, "database": database, "owner": "sample/Renamed",
            })
            assert not reloaded.isError, reloaded
            assert '"after"' in reloaded.structuredContent["assembly"]
            method["dex_name"] = reloaded.structuredContent["dex_name"]
            kept = await service.call("get_method_assembly", {
                **method, "database": database, "owner": "sample/Renamed", "name": "keep",
            })
            assert not kept.isError, kept
            assert '"keep"' in kept.structuredContent["assembly"]
            bytecode = await service.call("get_class_bytecode", {
                "database": database, "scope": "android", "class_name": "sample/Renamed",
            })
            assert not bytecode.isError, bytecode
            if output_format == "apk":
                second = await service.call("get_class_bytecode", {
                    "database": database, "scope": "android", "class_name": "sample/Second",
                })
                assert not second.isError, second
                with zipfile.ZipFile(destination) as archive:
                    assert {"classes.dex", "classes2.dex"} <= set(archive.namelist())
                    assert archive.read("assets/message.txt") == b"keep me"
                    assert "META-INF/OLD.SF" not in archive.namelist()
            else:
                assert destination.read_bytes().startswith(b"dex\n")
            discarded = await service.call("recaf_close", {"database": database, "save": False})
            assert not discarded.isError, discarded
            assert input_path.read_bytes() == original
        finally:
            await service.close()

    anyio.run(scenario)
