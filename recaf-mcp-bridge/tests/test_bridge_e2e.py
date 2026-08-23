from __future__ import annotations

import base64
import hashlib
import json
import os
import zipfile
from pathlib import Path

import anyio
import mcp.types as types
import pytest
from mcp.shared.memory import create_connected_server_and_client_session

from application import BridgeToolService
from config import BridgeConfig
from server import create_server


PATCHED_COORDINATOR_SOURCE = """\
package me.naptila.recafmcp.mcp;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public final class WorkerOperationCoordinator implements AutoCloseable {
    private final ReentrantLock operationLock = new ReentrantLock(true);
    private final AtomicInteger busyCount = new AtomicInteger();
    private volatile boolean closing;

    public static String patchMarker() {
        return "M6_PATCHED";
    }

    public <T> T execute(Callable<T> operation) throws Exception {
        if (closing) throw new WorkerClosingException();
        busyCount.incrementAndGet();
        try {
            operationLock.lockInterruptibly();
            try {
                if (closing) throw new WorkerClosingException();
                return operation.call();
            } finally {
                operationLock.unlock();
            }
        } finally {
            busyCount.decrementAndGet();
        }
    }

    public <T> T execute(String operationId,
                         long timeout,
                         TimeUnit unit,
                         Callable<T> operation) throws Exception {
        return execute(operation);
    }

    public boolean cancel(String operationId) {
        return false;
    }

    public void beginShutdown() throws InterruptedException {
        if (closing) throw new WorkerClosingException();
        busyCount.incrementAndGet();
        try {
            operationLock.lockInterruptibly();
            try {
                if (closing) throw new WorkerClosingException();
                closing = true;
            } finally {
                operationLock.unlock();
            }
        } finally {
            busyCount.decrementAndGet();
        }
    }

    public boolean isBusy() {
        return busyCount.get() > 0;
    }

    public boolean isClosing() {
        return closing;
    }

    @Override
    public void close() {
    }

    public static final class WorkerClosingException extends IllegalStateException {
        public WorkerClosingException() {
            super("worker is shutting down");
        }
    }

    public static final class OperationTimeoutException extends Exception {
        public OperationTimeoutException() {
            super("tool operation timed out");
        }
    }

    public static final class OperationCancelledException extends Exception {
        public OperationCancelledException() {
            super("tool operation was cancelled");
        }
    }
}
"""

SCRIPT_PATCH_SOURCE = r'''
if (workspace == null) throw new IllegalStateException("No workspace");
String owner = "me/naptila/recafmcp/mcp/tools/GetRecafScriptHelpTool";
String originalText =
        "Return version-matched Recaf Java scripting guidance and reusable templates.";
String patchedText = "MCP_SCRIPT_PATCH";
JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
JvmClassInfo info = bundle.get(owner);
if (info == null) throw new IllegalStateException("Target not found: " + owner);
ClassNode node = new ClassNode();
new ClassReader(info.getBytecode()).accept(node, 0);
int replacements = 0;
for (MethodNode method : node.methods) {
    if (!method.name.equals("description") || !method.desc.equals("()Ljava/lang/String;"))
        continue;
    for (AbstractInsnNode instruction : method.instructions) {
        if (instruction instanceof LdcInsnNode ldc && originalText.equals(ldc.cst)) {
            ldc.cst = patchedText;
            replacements++;
        }
    }
}
if (replacements != 1)
    throw new IllegalStateException("Expected one replacement, got " + replacements);
ClassWriter writer = new ClassWriter(0);
node.accept(writer);
JvmClassInfo replacement = new JvmClassInfoBuilder(writer.toByteArray()).build();
if (!replacement.getName().equals(owner))
    throw new IllegalStateException("Class identity changed");
bundle.put(replacement);
System.out.println("MCP_RESULT {\"changed\":true,\"owner\":\"" + owner + "\"}");
'''

REFERENCE_SCAN_SOURCE = r'''
if (workspace == null) throw new IllegalStateException("No workspace");
JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
long ixorInstructions = 0;
long ixorMethods = 0;
long shortArrayDecoderCandidates = 0;
for (JvmClassInfo info : bundle.valuesAsCopy()) {
    ClassNode node = new ClassNode();
    new ClassReader(info.getBytecode()).accept(
            node,
            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES
    );
    for (MethodNode method : node.methods) {
        boolean hasIxor = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.IXOR) {
                ixorInstructions++;
                hasIxor = true;
            }
        }
        if (hasIxor) {
            ixorMethods++;
            if (method.desc.equals("([SIII)Ljava/lang/String;"))
                shortArrayDecoderCandidates++;
        }
    }
}
System.out.println("MCP_RESULT {\"classes\":" + bundle.size()
        + ",\"ixor_instructions\":" + ixorInstructions
        + ",\"ixor_methods\":" + ixorMethods
        + ",\"short_array_decoder_candidates\":"
        + shortArrayDecoderCandidates + "}");
'''

SERVICE_SCRIPT_SOURCE = r'''
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import software.coley.recaf.services.workspace.WorkspaceManager;

@Dependent
public class McpServiceProbe {
    private final WorkspaceManager workspaceManager;

    @Inject
    public McpServiceProbe(WorkspaceManager workspaceManager) {
        this.workspaceManager = workspaceManager;
    }

    public void run() {
        int classes = workspaceManager.getCurrent()
                .getPrimaryResource()
                .getJvmClassBundle()
                .size();
        System.out.println("MCP_RESULT {\"class_count\":" + classes + "}");
    }
}
'''


@pytest.mark.skipif(
    "RECAF_MCP_TEST_RECAF_JAR" not in os.environ,
    reason="installed Recaf integration not requested",
)
def test_open_list_and_workspace_info_end_to_end(tmp_path: Path) -> None:
    recaf_jar = Path(os.environ["RECAF_MCP_TEST_RECAF_JAR"]).resolve(strict=True)
    plugin_jar = Path(os.environ["RECAF_MCP_TEST_PLUGIN_JAR"]).resolve(
        strict=True
    )
    input_path = Path(os.environ["RECAF_MCP_TEST_INPUT"]).resolve(strict=True)
    config = BridgeConfig(
        recaf_jar=recaf_jar,
        plugin_jar=plugin_jar,
        session_root=(tmp_path / "sessions").resolve(),
        max_sessions=1,
        response_profile="legacy",
        launch_timeout_sec=60,
    )
    service = BridgeToolService(config, watchdog_interval_sec=0.05)
    server = create_server(service)

    async def scenario() -> None:
        try:
            async with create_connected_server_and_client_session(server) as client:
                catalog = await client.list_tools()
                tools = {tool.name: tool for tool in catalog.tools}
                assert {
                    "workspace_info",
                    "list_classes",
                    "list_files",
                    "read_file",
                    "decompile_class",
                    "export_sources",
                    "search_strings",
                    "search_instructions",
                    "search_members",
                    "search_code",
                    "rename_symbol",
                    "apply_mappings",
                    "export_mappings",
                    "call_graph",
                    "transform_workspace",
                    "patch_class",
                    "get_method_assembly",
                    "patch_instructions",
                    "get_class_bytecode",
                    "get_recaf_script_help",
                    "run_recaf_script",
                } <= tools.keys()
                assert tools["workspace_info"].inputSchema["required"] == [
                    "database"
                ]
                assert tools["list_files"].inputSchema["required"] == [
                    "database"
                ]
                assert tools["read_file"].inputSchema["required"] == [
                    "database",
                    "path",
                ]
                assert tools["read_file"].inputSchema["properties"]["format"] == {
                    "type": "string",
                    "enum": ["auto", "text", "base64"],
                    "default": "auto",
                    "description": (
                        "auto: text or decoded Android XML when available; "
                        "base64: raw bytes."
                    ),
                }
                assert tools["decompile_class"].inputSchema["required"] == [
                    "database",
                    "class_name",
                ]
                assert tools["decompile_class"].inputSchema["properties"][
                    "limit"
                ]["maximum"] == 500_000
                expected_page_defaults = {
                    "list_classes": 20,
                    "list_files": 20,
                    "read_file": 32 * 1024,
                    "decompile_class": 32 * 1024,
                    "search_strings": 10,
                    "search_instructions": 10,
                    "search_members": 10,
                    "search_code": 10,
                    "export_mappings": 32 * 1024,
                    "call_graph": 10,
                    "get_class_bytecode": 32 * 1024,
                }
                for tool_name, expected_default in expected_page_defaults.items():
                    assert tools[tool_name].inputSchema["properties"]["limit"][
                        "default"
                    ] == expected_default
                conditional_schema = {
                    "type": "string",
                    "pattern": "^[0-9a-f]{64}$",
                    "description": "Omit the body if its full SHA-256 matches.",
                }
                for tool_name in (
                    "read_file",
                    "decompile_class",
                    "export_mappings",
                    "get_class_bytecode",
                    "get_method_assembly",
                ):
                    assert tools[tool_name].inputSchema["properties"][
                        "if_sha256"
                    ] == conditional_schema
                decompiler_schema = tools["decompile_class"].inputSchema[
                    "properties"
                ]["decompiler"]
                assert decompiler_schema["default"] == "CFR"
                assert {"CFR", "Fallback", "Procyon", "Vineflower"} == set(
                    decompiler_schema["enum"]
                )
                assert decompiler_schema["description"] == (
                    "Installed JVM decompiler; Android classes are converted first."
                )
                assert tools["search_code"].inputSchema["properties"][
                    "decompiler"
                ] == decompiler_schema
                assert tools["export_sources"].inputSchema[
                    "required"
                ] == ["database", "output_path"]
                assert tools["export_sources"].inputSchema[
                    "properties"
                ]["decompiler"] == decompiler_schema
                assert tools["export_sources"].inputSchema[
                    "properties"
                ]["scope"]["enum"] == ["jvm", "android", "all"]
                assert tools["export_sources"].inputSchema[
                    "properties"
                ]["overwrite"]["default"] is False
                assert tools["apply_mappings"].inputSchema["required"] == [
                    "database",
                    "format",
                    "mappings",
                ]
                mapping_format_schema = tools["apply_mappings"].inputSchema[
                    "properties"
                ]["format"]
                assert set(mapping_format_schema["enum"]) == {
                    "Enigma",
                    "Jadx (Legacy)",
                    "Proguard",
                    "Simple",
                    "SRG",
                    "Tiny-V1",
                    "Tiny-V2",
                }
                assert mapping_format_schema["description"] == (
                    "Installed Recaf mapping format."
                )
                assert tools["apply_mappings"].inputSchema["properties"][
                    "mappings"
                ]["maxLength"] == 750_000
                assert tools["apply_mappings"].inputSchema["properties"][
                    "dry_run"
                ]["default"] is False
                assert tools["apply_mappings"].inputSchema["properties"][
                    "scope"
                ]["enum"] == ["jvm", "android", "all"]
                assert "dex_name" not in tools["apply_mappings"].inputSchema[
                    "properties"
                ]
                assert tools["export_mappings"].inputSchema["required"] == [
                    "database",
                    "format",
                ]
                assert tools["export_mappings"].inputSchema["properties"][
                    "format"
                ] == mapping_format_schema
                assert tools["export_mappings"].inputSchema["properties"][
                    "limit"
                ]["maximum"] == 512 * 1024
                assert tools["get_class_bytecode"].inputSchema["properties"][
                    "limit"
                ]["maximum"] == 1024 * 1024
                for name in (
                    "decompile_class",
                    "get_class_bytecode",
                    "call_graph",
                    "get_method_assembly",
                    "rename_symbol",
                ):
                    properties = tools[name].inputSchema["properties"]
                    assert properties["scope"]["enum"] == ["jvm", "android"]
                    assert "dex_name" in properties
                for name in (
                    "list_classes",
                    "search_code",
                    "search_strings",
                    "search_instructions",
                    "search_members",
                ):
                    properties = tools[name].inputSchema["properties"]
                    assert properties["scope"]["enum"] == [
                        "jvm",
                        "android",
                        "all",
                    ]
                    assert "dex_name" in properties
                assert tools["patch_class"].inputSchema["properties"][
                    "target_version_policy"
                ]["default"] == "preserve"
                assert tools["patch_instructions"].inputSchema["required"] == [
                    "database",
                    "owner",
                    "name",
                    "descriptor",
                    "assembly",
                    "expected_assembly_sha256",
                ]
                assert tools["patch_instructions"].inputSchema["properties"][
                    "dry_run"
                ]["default"] is False
                assert tools["get_recaf_script_help"].inputSchema["properties"][
                    "topic"
                ]["enum"] == [
                    "overview",
                    "workspace",
                    "asm",
                    "services",
                    "deobfuscation",
                ]
                assert tools["run_recaf_script"].inputSchema["required"] == [
                    "database",
                    "script",
                ]
                assert tools["run_recaf_script"].inputSchema["properties"][
                    "compile_only"
                ]["default"] is True
                assert tools["run_recaf_script"].inputSchema["properties"][
                    "acknowledge_full_worker_access"
                ]["default"] is False
                assert tools["call_graph"].inputSchema["properties"][
                    "stop_at_external"
                ]["default"] is True
                assert tools["search_code"].inputSchema["properties"][
                    "allow_large_scan"
                ]["default"] is False
                assert "anyOf" not in tools["search_members"].inputSchema
                assert {
                    "owner",
                    "name",
                    "descriptor",
                    "regex",
                    "kind",
                    "results",
                    "filter",
                    "scope",
                    "dex_name",
                    "offset",
                    "limit",
                    "database",
                } == set(tools["search_members"].inputSchema["properties"])

                opened = await client.call_tool(
                    "recaf_open",
                    {
                        "input_path": str(input_path),
                        "autosave_path": str(tmp_path / "original-autosave.jar"),
                    },
                )
                assert opened.isError is False
                database = opened.structuredContent["database"]

                listed = await client.call_tool("recaf_list", {})
                public_session = listed.structuredContent["sessions"][0]
                assert public_session["database"] == database
                assert public_session["input_path"] == str(input_path)
                assert "token" not in public_session

                workspace = await client.call_tool(
                    "workspace_info",
                    {"database": database},
                )
                assert workspace.isError is False
                assert workspace.structuredContent["workspace_open"] is True
                assert workspace.structuredContent["primary_resource"][
                    "class_count"
                ] > 0
                original_class_count = workspace.structuredContent[
                    "primary_resource"
                ]["class_count"]

                listed_files = await client.call_tool(
                    "list_files",
                    {
                        "database": database,
                        "filter": "META-INF/services/software.coley.recaf.plugin.Plugin",
                    },
                )
                assert listed_files.isError is False
                assert listed_files.structuredContent["total"] == 1
                service_path = listed_files.structuredContent["paths"][0]
                assert service_path == (
                    "META-INF/services/software.coley.recaf.plugin.Plugin"
                )
                assert listed_files.structuredContent["files"][0]["is_text"] is True

                read_service = await client.call_tool(
                    "read_file",
                    {"database": database, "path": service_path},
                )
                assert read_service.isError is False
                assert read_service.structuredContent["format"] == "text"
                assert read_service.structuredContent["content_text"] == (
                    "me.naptila.recafmcp.RecafMCPPlugin"
                )
                assert any(
                    "File text" in item.text
                    and "me.naptila.recafmcp.RecafMCPPlugin" in item.text
                    for item in read_service.content
                    if isinstance(item, types.TextContent)
                )
                unchanged_file = await client.call_tool(
                    "read_file",
                    {
                        "database": database,
                        "path": service_path,
                        "if_sha256": read_service.structuredContent["sha256"],
                    },
                )
                assert unchanged_file.isError is False
                assert unchanged_file.structuredContent["unchanged"] is True
                assert "content_text" not in unchanged_file.structuredContent
                assert "content_base64" not in unchanged_file.structuredContent

                read_service_bytes = await client.call_tool(
                    "read_file",
                    {
                        "database": database,
                        "path": service_path,
                        "format": "base64",
                    },
                )
                assert read_service_bytes.isError is False
                assert base64.b64decode(
                    read_service_bytes.structuredContent["content_base64"]
                ) == b"me.naptila.recafmcp.RecafMCPPlugin"

                script_help = await client.call_tool(
                    "get_recaf_script_help",
                    {"database": database, "topic": "asm"},
                )
                assert script_help.isError is False
                assert "ClassNode" in script_help.structuredContent["guide"]
                assert "bundle.put" in script_help.structuredContent["guide"]
                assert any(
                    "# ASM inspection and rewriting" in item.text
                    for item in script_help.content
                    if isinstance(item, types.TextContent)
                )

                service_script = await client.call_tool(
                    "run_recaf_script",
                    {
                        "database": database,
                        "script": SERVICE_SCRIPT_SOURCE,
                        "compile_only": False,
                        "acknowledge_full_worker_access": True,
                    },
                )
                assert service_script.isError is False
                assert service_script.structuredContent["script_form"] == "full_class"
                assert service_script.structuredContent["script_result"] == {
                    "class_count": original_class_count
                }
                assert service_script.structuredContent["changed_class_count"] == 0

                script_preflight = await client.call_tool(
                    "run_recaf_script",
                    {"database": database, "script": SCRIPT_PATCH_SOURCE},
                )
                assert script_preflight.isError is False
                assert script_preflight.structuredContent["compiled"] is True
                assert script_preflight.structuredContent["executed"] is False
                assert script_preflight.structuredContent["changed_class_count"] == 0
                unpatched_script_target = await client.call_tool(
                    "decompile_class",
                    {
                        "database": database,
                        "class_name": (
                            "me/naptila/recafmcp/mcp/tools/"
                            "GetRecafScriptHelpTool"
                        ),
                    },
                )
                assert unpatched_script_target.isError is False
                assert (
                    "Return version-matched Recaf Java scripting guidance"
                    in unpatched_script_target.structuredContent["source"]
                )

                script_patch = await client.call_tool(
                    "run_recaf_script",
                    {
                        "database": database,
                        "script": SCRIPT_PATCH_SOURCE,
                        "compile_only": False,
                        "acknowledge_full_worker_access": True,
                    },
                )
                assert script_patch.isError is False
                assert script_patch.structuredContent["executed"] is True
                assert script_patch.structuredContent["script_result"] == {
                    "changed": True,
                    "owner": (
                        "me/naptila/recafmcp/mcp/tools/"
                        "GetRecafScriptHelpTool"
                    ),
                }
                assert script_patch.structuredContent["modified_classes"] == [
                    "me/naptila/recafmcp/mcp/tools/GetRecafScriptHelpTool"
                ]
                assert script_patch.structuredContent["atomic"] is False
                assert any(
                    "Captured script stdout" in item.text
                    and "MCP_RESULT" in item.text
                    for item in script_patch.content
                    if isinstance(item, types.TextContent)
                )
                patched_script_target = await client.call_tool(
                    "decompile_class",
                    {
                        "database": database,
                        "class_name": (
                            "me/naptila/recafmcp/mcp/tools/"
                            "GetRecafScriptHelpTool"
                        ),
                    },
                )
                assert patched_script_target.isError is False
                assert "MCP_SCRIPT_PATCH" in patched_script_target.structuredContent[
                    "source"
                ]

                listed_classes = await client.call_tool(
                    "list_classes",
                    {
                        "database": database,
                        "filter": "me/naptila/recafmcp",
                        "limit": 5,
                    },
                )
                assert listed_classes.isError is False
                assert listed_classes.structuredContent["returned"] == 5
                assert listed_classes.structuredContent["next_offset"] == 5
                assert all(
                    name.startswith("me/naptila/recafmcp")
                    for name in listed_classes.structuredContent["classes"]
                )

                progress_updates: list[
                    tuple[float, float | None, str | None]
                ] = []

                async def progress(
                    current: float,
                    total: float | None,
                    message: str | None,
                ) -> None:
                    progress_updates.append((current, total, message))

                decompiled = await client.call_tool(
                    "decompile_class",
                    {
                        "database": database,
                        "class_name": "me/naptila/recafmcp/RecafMCPPlugin",
                    },
                    progress_callback=progress,
                )
                assert decompiled.isError is False
                assert decompiled.structuredContent["result_type"] == "success"
                assert "class RecafMCPPlugin" in decompiled.structuredContent[
                    "source"
                ]
                assert len(decompiled.structuredContent["source_sha256"]) == 64
                assert any(
                    "```java" in item.text
                    and "class RecafMCPPlugin" in item.text
                    for item in decompiled.content
                    if isinstance(item, types.TextContent)
                )
                assert progress_updates == [
                    (0.0, 1.0, "Starting decompile_class"),
                    (1.0, 1.0, "Completed decompile_class"),
                ]
                unchanged_source = await client.call_tool(
                    "decompile_class",
                    {
                        "database": database,
                        "class_name": "me/naptila/recafmcp/RecafMCPPlugin",
                        "if_sha256": decompiled.structuredContent[
                            "source_sha256"
                        ],
                    },
                )
                assert unchanged_source.isError is False
                assert unchanged_source.structuredContent["unchanged"] is True
                assert "source" not in unchanged_source.structuredContent
                assert "offset" not in unchanged_source.structuredContent
                source_page = await client.call_tool(
                    "decompile_class",
                    {
                        "database": database,
                        "class_name": "me/naptila/recafmcp/RecafMCPPlugin",
                        "decompiler": "Vineflower",
                        "offset": 0,
                        "limit": 64,
                    },
                )
                assert source_page.isError is False
                assert source_page.structuredContent["decompiler"] == "Vineflower"
                assert source_page.structuredContent["returned_chars"] == 64
                assert source_page.structuredContent["next_offset"] == 64
                assert source_page.structuredContent["source_length"] > 64

                source_archive = tmp_path / "decompiled-plugin-sources.zip"
                source_export = await client.call_tool(
                    "export_sources",
                    {
                        "database": database,
                        "output_path": str(source_archive),
                        "decompiler": "Vineflower",
                    },
                )
                assert source_export.isError is False, source_export
                source_metadata = source_export.structuredContent
                assert source_metadata["path"] == str(source_archive.resolve())
                assert source_metadata["classes_selected"] == original_class_count
                assert source_metadata["classes_exported"] == original_class_count
                assert source_metadata["classes_failed"] == 0
                assert source_metadata["complete"] is True
                assert source_metadata["decompiler"] == "Vineflower"
                assert source_metadata["zip_entries"] == original_class_count + 1
                assert hashlib.sha256(source_archive.read_bytes()).hexdigest() == (
                    source_metadata["sha256"]
                )
                with zipfile.ZipFile(source_archive) as archive:
                    exported_source = archive.read(
                        "jvm/me/naptila/recafmcp/RecafMCPPlugin.java"
                    ).decode("utf-8")
                    source_manifest = json.loads(
                        archive.read("_recaf-mcp/manifest.json")
                    )
                assert "class RecafMCPPlugin" in exported_source
                assert source_manifest["classes_exported"] == original_class_count
                assert source_manifest["complete"] is True

                string_hits = await client.call_tool(
                    "search_strings",
                    {
                        "database": database,
                        "text": "RECAF_MCP_TOKEN",
                        "limit": 1,
                    },
                )
                assert string_hits.isError is False
                assert string_hits.structuredContent["total"] >= 1
                assert string_hits.structuredContent["hits"][0]["class_name"] == (
                    "me/naptila/recafmcp/RecafMCPPlugin"
                )
                assert any(
                    '"hits":[' in item.text and "RECAF_MCP_TOKEN" in item.text
                    for item in string_hits.content
                    if isinstance(item, types.TextContent)
                )

                invalid_regex = await client.call_tool(
                    "search_strings",
                    {
                        "database": database,
                        "text": "[",
                        "regex": True,
                    },
                )
                assert invalid_regex.isError is True
                assert "Unclosed character class" in invalid_regex.content[0].text

                instruction_hits = await client.call_tool(
                    "search_instructions",
                    {
                        "database": database,
                        "text": "RECAF_MCP_TOKEN",
                        "filter": "me/naptila/recafmcp/RecafMCPPlugin",
                        "limit": 1,
                    },
                )
                assert instruction_hits.isError is False
                assert instruction_hits.structuredContent["total"] >= 1
                assert instruction_hits.structuredContent["hits"][0][
                    "class_name"
                ] == "me/naptila/recafmcp/RecafMCPPlugin"
                assert "RECAF_MCP_TOKEN" in instruction_hits.structuredContent[
                    "hits"
                ][0]["instruction"]

                member_hits = await client.call_tool(
                    "search_members",
                    {
                        "database": database,
                        "owner": "me/naptila/recafmcp/RecafMCPPlugin",
                        "name": "resolveExpectedInputPath",
                        "descriptor": "()Ljava/nio/file/Path;",
                        "kind": "method",
                        "results": "both",
                        "filter": "me/naptila/recafmcp/RecafMCPPlugin",
                    },
                )
                assert member_hits.isError is False
                assert member_hits.structuredContent["classes_selected"] == 2
                assert member_hits.structuredContent["total"] == 2
                assert {
                    hit["result_type"]
                    for hit in member_hits.structuredContent["hits"]
                } == {"declaration", "reference"}
                assert all(
                    hit["member_kind"] == "method"
                    for hit in member_hits.structuredContent["hits"]
                )

                code_hits = await client.call_tool(
                    "search_code",
                    {
                        "database": database,
                        "text": "RECAF_MCP_TOKEN",
                        "filter": "me/naptila/recafmcp/RecafMCPPlugin",
                        "decompiler": "Vineflower",
                        "limit": 1,
                    },
                )
                assert code_hits.isError is False
                assert code_hits.structuredContent["filter"] == (
                    "me/naptila/recafmcp/RecafMCPPlugin"
                )
                assert code_hits.structuredContent["classes_selected"] == 2
                assert code_hits.structuredContent["classes_scanned"] == 2
                assert code_hits.structuredContent["implicit_scan_class_limit"] == 200
                assert code_hits.structuredContent["allow_large_scan"] is False
                assert code_hits.structuredContent["decompiler"] == "Vineflower"
                assert code_hits.structuredContent["total"] >= 1
                assert code_hits.structuredContent["hits"][0]["class_name"] == (
                    "me/naptila/recafmcp/RecafMCPPlugin"
                )
                assert "RECAF_MCP_TOKEN" in code_hits.structuredContent["hits"][0][
                    "line"
                ]

                assembly_target = {
                    "database": database,
                    "owner": "me/naptila/recafmcp/mcp/tools/PatchClassTool",
                    "name": "description",
                    "descriptor": "()Ljava/lang/String;",
                }
                method_assembly = await client.call_tool(
                    "get_method_assembly",
                    assembly_target,
                )
                assert method_assembly.isError is False
                assert method_assembly.structuredContent["assembly_format"] == (
                    "recaf_jasm_method"
                )
                original_assembly = method_assembly.structuredContent["assembly"]
                original_assembly_hash = method_assembly.structuredContent[
                    "assembly_sha256"
                ]
                assert len(original_assembly_hash) == 64
                assert any(
                    "```jasm" in item.text and original_assembly in item.text
                    for item in method_assembly.content
                    if isinstance(item, types.TextContent)
                )
                original_description = (
                    "Compile complete Java source and replace one primary JVM class "
                    "family."
                )
                assert original_description in original_assembly
                conditional_assembly = await client.call_tool(
                    "get_method_assembly",
                    {
                        **assembly_target,
                        "if_sha256": original_assembly_hash,
                    },
                )
                assert conditional_assembly.isError is False
                assert conditional_assembly.structuredContent["unchanged"] is True
                assert "assembly" not in conditional_assembly.structuredContent
                edited_assembly = original_assembly.replace(
                    original_description,
                    "MCP_INSTRUCTION_PATCH",
                )

                dry_run_patch = await client.call_tool(
                    "patch_instructions",
                    {
                        **assembly_target,
                        "assembly": edited_assembly,
                        "expected_assembly_sha256": original_assembly_hash,
                        "dry_run": True,
                    },
                )
                assert dry_run_patch.isError is False
                assert dry_run_patch.structuredContent["applied"] is False
                assert dry_run_patch.structuredContent["changed"] is True

                unchanged_assembly = await client.call_tool(
                    "get_method_assembly",
                    assembly_target,
                )
                assert unchanged_assembly.isError is False
                assert unchanged_assembly.structuredContent["assembly_sha256"] == (
                    original_assembly_hash
                )

                instruction_patch = await client.call_tool(
                    "patch_instructions",
                    {
                        **assembly_target,
                        "assembly": edited_assembly,
                        "expected_assembly_sha256": original_assembly_hash,
                    },
                )
                assert instruction_patch.isError is False
                assert instruction_patch.structuredContent["applied"] is True
                assert instruction_patch.structuredContent["changed"] is True
                assert instruction_patch.structuredContent[
                    "original_class_sha256"
                ] != instruction_patch.structuredContent["patched_class_sha256"]

                patched_assembly = await client.call_tool(
                    "get_method_assembly",
                    assembly_target,
                )
                assert patched_assembly.isError is False
                assert "MCP_INSTRUCTION_PATCH" in patched_assembly.structuredContent[
                    "assembly"
                ]
                assert patched_assembly.structuredContent["assembly_sha256"] != (
                    original_assembly_hash
                )

                graph = await client.call_tool(
                    "call_graph",
                    {
                        "database": database,
                        "owner": "me/naptila/recafmcp/RecafMCPPlugin",
                        "name": "onEnable",
                        "descriptor": "()V",
                        "direction": "calls",
                        "max_depth": 1,
                    },
                )
                assert graph.isError is False
                assert graph.structuredContent["total"] >= 1
                assert graph.structuredContent["root"]["name"] == "onEnable"
                assert graph.structuredContent["stop_at_external"] is True
                assert graph.structuredContent["external_vertices_stopped"] >= 0
                assert all(
                    edge["caller"]["owner"]
                    == "me/naptila/recafmcp/RecafMCPPlugin"
                    for edge in graph.structuredContent["edges"]
                )

                field_xrefs = await client.call_tool(
                    "search_members",
                    {
                        "database": database,
                        "owner": "me/naptila/recafmcp/RecafMCPPlugin",
                        "name": "workspaceManager",
                        "descriptor": (
                            "Lsoftware/coley/recaf/services/workspace/"
                            "WorkspaceManager;"
                        ),
                        "kind": "field",
                        "results": "references",
                    },
                )
                assert field_xrefs.isError is False
                assert field_xrefs.structuredContent["total"] >= 1
                assert all(
                    reference["class_name"]
                    == "me/naptila/recafmcp/RecafMCPPlugin"
                    for reference in field_xrefs.structuredContent["hits"]
                )

                transform_arguments = {
                    "database": database,
                    "transformers": ["frame_removal"],
                    "class_prefix": (
                        "me/naptila/recafmcp/mcp/util/ToolArguments"
                    ),
                }
                transform_preview = await client.call_tool(
                    "transform_workspace",
                    {**transform_arguments, "dry_run": True},
                )
                assert transform_preview.isError is False
                assert transform_preview.structuredContent["applied"] is False
                assert transform_preview.structuredContent[
                    "modified_class_count"
                ] == 1

                transformed = await client.call_tool(
                    "transform_workspace",
                    transform_arguments,
                )
                assert transformed.isError is False
                assert transformed.structuredContent["applied"] is True
                assert transformed.structuredContent["failure_count"] == 0
                assert transformed.structuredContent[
                    "modified_classes"
                ] == ["me/naptila/recafmcp/mcp/util/ToolArguments"]

                renamed = await client.call_tool(
                    "rename_symbol",
                    {
                        "database": database,
                        "kind": "method",
                        "owner": "me/naptila/recafmcp/mcp/util/ToolArguments",
                        "name": "bool",
                        "descriptor": (
                            "(Lcom/google/gson/JsonObject;"
                            "Ljava/lang/String;Z)Z"
                        ),
                        "new_name": "booleanValue",
                    },
                )
                assert renamed.isError is False
                assert renamed.structuredContent["changed_classes"] >= 2

                old_mapping_class = (
                    "me/naptila/recafmcp/mcp/tools/ListFilesTool"
                )
                new_mapping_class = old_mapping_class + "Mapped"
                simple_mapping = f"{old_mapping_class} {new_mapping_class}\n"
                mapping_preview = await client.call_tool(
                    "apply_mappings",
                    {
                        "database": database,
                        "format": "Simple",
                        "mappings": simple_mapping,
                        "dry_run": True,
                    },
                )
                assert mapping_preview.isError is False
                assert mapping_preview.structuredContent["applied"] is False
                assert mapping_preview.structuredContent["save_required"] is False
                assert mapping_preview.structuredContent["renamed_classes"] == 1
                assert mapping_preview.structuredContent["mapping_counts"] == {
                    "classes": 1,
                    "fields": 0,
                    "methods": 0,
                    "variables": 0,
                    "total": 1,
                }
                preview_old = await client.call_tool(
                    "decompile_class",
                    {"database": database, "class_name": old_mapping_class},
                )
                assert preview_old.isError is False

                mapped = await client.call_tool(
                    "apply_mappings",
                    {
                        "database": database,
                        "format": "Simple",
                        "mappings": simple_mapping,
                    },
                )
                assert mapped.isError is False
                assert mapped.structuredContent["format"] == "Simple"
                assert mapped.structuredContent["applied"] is True
                assert mapped.structuredContent["save_required"] is True
                assert mapped.structuredContent["renamed_classes"] == 1
                assert len(mapped.structuredContent["mapping_sha256"]) == 64
                mapped_class = await client.call_tool(
                    "decompile_class",
                    {"database": database, "class_name": new_mapping_class},
                )
                assert mapped_class.isError is False
                assert "class ListFilesToolMapped" in (
                    mapped_class.structuredContent["source"]
                )

                mapping_formats = {
                    "Enigma",
                    "Jadx (Legacy)",
                    "Proguard",
                    "Simple",
                    "SRG",
                    "Tiny-V1",
                    "Tiny-V2",
                }
                exported_documents: dict[str, str] = {}
                for mapping_format in sorted(mapping_formats):
                    export_arguments = {
                        "database": database,
                        "format": mapping_format,
                    }
                    if mapping_format == "Simple":
                        export_arguments["limit"] = 37
                    exported = await client.call_tool(
                        "export_mappings",
                        export_arguments,
                    )
                    assert exported.isError is False
                    metadata = exported.structuredContent
                    assert metadata["format"] == mapping_format
                    assert metadata["mapping_direction"] == "original_to_current"
                    assert metadata["source"] == "recaf_aggregate_mapping_manager"
                    assert metadata["mapping_counts"] == {
                        "classes": 1,
                        "fields": 0,
                        "methods": 1,
                        "variables": 0,
                        "total": 2,
                    }
                    assert metadata["missing_field_descriptors"] is False
                    assert metadata["save_required"] is False
                    if mapping_format == "Simple":
                        conditional_mapping = await client.call_tool(
                            "export_mappings",
                            {
                                "database": database,
                                "format": mapping_format,
                                "if_sha256": metadata["mapping_sha256"],
                            },
                        )
                        assert conditional_mapping.isError is False
                        assert conditional_mapping.structuredContent[
                            "unchanged"
                        ] is True
                        assert (
                            "content_text"
                            not in conditional_mapping.structuredContent
                        )
                    pages = [metadata["content_text"]]
                    next_offset = metadata["next_offset"]
                    while next_offset is not None:
                        page = await client.call_tool(
                            "export_mappings",
                            {
                                **export_arguments,
                                "offset": next_offset,
                            },
                        )
                        assert page.isError is False
                        pages.append(page.structuredContent["content_text"])
                        next_offset = page.structuredContent["next_offset"]
                    document = "".join(pages)
                    assert document
                    assert len(document) == metadata["size_characters"]
                    assert len(document.encode("utf-8")) == metadata["size_bytes"]
                    assert hashlib.sha256(document.encode("utf-8")).hexdigest() == (
                        metadata["mapping_sha256"]
                    )
                    exported_documents[mapping_format] = document

                simple_export = exported_documents["Simple"]
                assert simple_mapping.strip() in simple_export
                assert (
                    "me/naptila/recafmcp/mcp/util/ToolArguments.bool"
                    "(Lcom/google/gson/JsonObject;Ljava/lang/String;Z)Z "
                    "booleanValue"
                ) in simple_export

                patched = await client.call_tool(
                    "patch_class",
                    {
                        "database": database,
                        "class_name": (
                            "me/naptila/recafmcp/mcp/"
                            "WorkerOperationCoordinator"
                        ),
                        "source": PATCHED_COORDINATOR_SOURCE,
                    },
                )
                assert patched.isError is False
                assert patched.structuredContent["updated_class_count"] == 4
                assert patched.structuredContent["generated_companion_count"] == 3
                assert patched.structuredContent["target_version_policy"] == "preserve"
                assert patched.structuredContent["classfile_version_upgraded"] is False
                assert patched.structuredContent["input_classfile_version"] == (
                    patched.structuredContent["target_classfile_version"]
                )
                assert patched.structuredContent["original_sha256"] != (
                    patched.structuredContent["patched_sha256"]
                )

                roundtrip = tmp_path / "roundtrip.jar"
                roundtrip.write_bytes(b"occupied")
                failed_save = await client.call_tool(
                    "recaf_save",
                    {
                        "database": database,
                        "output_path": str(roundtrip),
                    },
                )
                assert failed_save.isError is True
                assert failed_save.structuredContent["error"] == "save_failed"
                still_open = await client.call_tool("recaf_list", {})
                assert len(still_open.structuredContent["sessions"]) == 1
                assert still_open.structuredContent["sessions"][0][
                    "save_error"
                ] is not None

                saved = await client.call_tool(
                    "recaf_save",
                    {
                        "database": database,
                        "output_path": str(roundtrip),
                        "overwrite": True,
                    },
                )
                assert saved.isError is False
                assert saved.structuredContent["path"] == str(roundtrip)
                assert saved.structuredContent["size_bytes"] > 0
                with zipfile.ZipFile(roundtrip) as archive:
                    assert (
                        "META-INF/services/software.coley.recaf.plugin.Plugin"
                        in archive.namelist()
                    )

                blocked_close = tmp_path / "blocked-close.jar"
                blocked_close.write_bytes(b"occupied")
                failed_close = await client.call_tool(
                    "recaf_close",
                    {
                        "database": database,
                        "output_path": str(blocked_close),
                    },
                )
                assert failed_close.isError is True
                assert failed_close.structuredContent["error"] == "save_failed"
                after_failed_close = await client.call_tool("recaf_list", {})
                assert after_failed_close.structuredContent["sessions"][0][
                    "state"
                ] == "ready"

                closed = await client.call_tool(
                    "recaf_close",
                    {"database": database},
                )
                assert closed.isError is False
                assert closed.structuredContent["closed"] is True
                assert closed.structuredContent["saved"] is True
                assert closed.structuredContent["graceful"] is True
                original_autosave = tmp_path / "original-autosave.jar"
                assert closed.structuredContent["path"] == str(original_autosave)
                assert zipfile.is_zipfile(original_autosave)
                after_close = await client.call_tool("recaf_list", {})
                assert after_close.structuredContent == {"sessions": []}

                reopened = await client.call_tool(
                    "recaf_open",
                    {"input_path": str(roundtrip)},
                )
                assert reopened.isError is False
                reopened_database = reopened.structuredContent["database"]
                reopened_info = await client.call_tool(
                    "workspace_info",
                    {"database": reopened_database},
                )
                assert reopened_info.isError is False
                assert reopened_info.structuredContent["primary_resource"][
                    "class_count"
                ] == original_class_count
                reopened_mapped_class = await client.call_tool(
                    "decompile_class",
                    {
                        "database": reopened_database,
                        "class_name": new_mapping_class,
                    },
                )
                assert reopened_mapped_class.isError is False
                assert "class ListFilesToolMapped" in (
                    reopened_mapped_class.structuredContent["source"]
                )
                renamed_definition = await client.call_tool(
                    "decompile_class",
                    {
                        "database": reopened_database,
                        "class_name": (
                            "me/naptila/recafmcp/mcp/util/ToolArguments"
                        ),
                    },
                )
                assert renamed_definition.isError is False
                assert "booleanValue(" in renamed_definition.structuredContent[
                    "source"
                ]
                renamed_reference = await client.call_tool(
                    "decompile_class",
                    {
                        "database": reopened_database,
                        "class_name": (
                            "me/naptila/recafmcp/mcp/tools/SearchToolSupport"
                        ),
                    },
                )
                assert renamed_reference.isError is False
                assert "ToolArguments.booleanValue(" in renamed_reference.structuredContent[
                    "source"
                ]
                patched_class = await client.call_tool(
                    "decompile_class",
                    {
                        "database": reopened_database,
                        "class_name": (
                            "me/naptila/recafmcp/mcp/"
                            "WorkerOperationCoordinator"
                        ),
                    },
                )
                assert patched_class.isError is False
                assert "patchMarker()" in patched_class.structuredContent["source"]
                assert "M6_PATCHED" in patched_class.structuredContent["source"]
                reopened_script_target = await client.call_tool(
                    "decompile_class",
                    {
                        "database": reopened_database,
                        "class_name": (
                            "me/naptila/recafmcp/mcp/tools/"
                            "GetRecafScriptHelpTool"
                        ),
                    },
                )
                assert reopened_script_target.isError is False
                assert "MCP_SCRIPT_PATCH" in reopened_script_target.structuredContent[
                    "source"
                ]
                reopened_assembly = await client.call_tool(
                    "get_method_assembly",
                    {
                        **assembly_target,
                        "database": reopened_database,
                    },
                )
                assert reopened_assembly.isError is False
                assert "MCP_INSTRUCTION_PATCH" in reopened_assembly.structuredContent[
                    "assembly"
                ]
                raw_bytecode = await client.call_tool(
                    "get_class_bytecode",
                    {
                        "database": reopened_database,
                        "class_name": (
                            "me/naptila/recafmcp/mcp/"
                            "WorkerOperationCoordinator"
                        ),
                        "limit": 64,
                    },
                )
                assert raw_bytecode.isError is False
                unchanged_bytecode = await client.call_tool(
                    "get_class_bytecode",
                    {
                        "database": reopened_database,
                        "class_name": (
                            "me/naptila/recafmcp/mcp/"
                            "WorkerOperationCoordinator"
                        ),
                        "if_sha256": raw_bytecode.structuredContent["sha256"],
                    },
                )
                assert unchanged_bytecode.isError is False
                assert unchanged_bytecode.structuredContent["unchanged"] is True
                assert (
                    "bytecode_base64"
                    not in unchanged_bytecode.structuredContent
                )
                chunks = [
                    base64.b64decode(
                        raw_bytecode.structuredContent["bytecode_base64"],
                        validate=True,
                    )
                ]
                next_offset = raw_bytecode.structuredContent["next_offset"]
                while next_offset is not None:
                    bytecode_page = await client.call_tool(
                        "get_class_bytecode",
                        {
                            "database": reopened_database,
                            "class_name": (
                                "me/naptila/recafmcp/mcp/"
                                "WorkerOperationCoordinator"
                            ),
                            "offset": next_offset,
                            "limit": 64,
                        },
                    )
                    assert bytecode_page.isError is False
                    chunks.append(
                        base64.b64decode(
                            bytecode_page.structuredContent["bytecode_base64"],
                            validate=True,
                        )
                    )
                    next_offset = bytecode_page.structuredContent["next_offset"]
                decoded = b"".join(chunks)
                assert decoded[:4] == b"\xca\xfe\xba\xbe"
                assert len(decoded) == raw_bytecode.structuredContent["size_bytes"]
                assert hashlib.sha256(decoded).hexdigest() == (
                    raw_bytecode.structuredContent["sha256"]
                )
                discarded = await client.call_tool(
                    "recaf_close",
                    {"database": reopened_database, "save": False},
                )
                assert discarded.isError is False
                assert discarded.structuredContent["saved"] is False

                idle_autosave = tmp_path / "idle-autosave.jar"
                idle_opened = await client.call_tool(
                    "recaf_open",
                    {
                        "input_path": str(roundtrip),
                        "autosave_path": str(idle_autosave),
                        "idle_ttl_sec": 0,
                    },
                )
                assert idle_opened.isError is False
                idle_database = idle_opened.structuredContent["database"]
                enabled = await client.call_tool(
                    "set_idle_ttl",
                    {"database": idle_database, "idle_ttl_sec": 1},
                )
                assert enabled.isError is False

                for _ in range(50):
                    idle_list = await client.call_tool("recaf_list", {})
                    if idle_list.structuredContent["sessions"] == []:
                        break
                    await anyio.sleep(0.1)
                assert idle_list.structuredContent["sessions"] == []
                assert zipfile.is_zipfile(idle_autosave)
        finally:
            await service.close()

        assert not config.session_root.exists() or not any(
            config.session_root.iterdir()
        )

    anyio.run(scenario)


@pytest.mark.skipif(
    "RECAF_MCP_TEST_REFERENCE_INPUT" not in os.environ,
    reason="large generic script reference input not requested",
)
def test_generic_script_scans_large_reference_jar(tmp_path: Path) -> None:
    recaf_jar = Path(os.environ["RECAF_MCP_TEST_RECAF_JAR"]).resolve(strict=True)
    plugin_jar = Path(os.environ["RECAF_MCP_TEST_PLUGIN_JAR"]).resolve(
        strict=True
    )
    input_path = Path(os.environ["RECAF_MCP_TEST_REFERENCE_INPUT"]).resolve(
        strict=True
    )
    config = BridgeConfig(
        recaf_jar=recaf_jar,
        plugin_jar=plugin_jar,
        session_root=(tmp_path / "sessions").resolve(),
        max_sessions=1,
        launch_timeout_sec=60,
    )
    service = BridgeToolService(config, watchdog_interval_sec=0.05)
    server = create_server(service)

    async def scenario() -> None:
        try:
            async with create_connected_server_and_client_session(server) as client:
                opened = await client.call_tool(
                    "recaf_open",
                    {"input_path": str(input_path)},
                )
                assert opened.isError is False
                database = opened.structuredContent["database"]

                preflight = await client.call_tool(
                    "run_recaf_script",
                    {"database": database, "script": REFERENCE_SCAN_SOURCE},
                )
                assert preflight.isError is False
                assert preflight.structuredContent["executed"] is False

                scanned = await client.call_tool(
                    "run_recaf_script",
                    {
                        "database": database,
                        "script": REFERENCE_SCAN_SOURCE,
                        "compile_only": False,
                        "acknowledge_full_worker_access": True,
                    },
                )
                assert scanned.isError is False
                summary = scanned.structuredContent["script_result"]
                assert summary["classes"] > 10_000
                assert summary["ixor_instructions"] > 0
                assert summary["ixor_methods"] > 0
                assert summary["short_array_decoder_candidates"] > 0
                assert scanned.structuredContent["changed_class_count"] == 0

                closed = await client.call_tool(
                    "recaf_close",
                    {"database": database, "save": False},
                )
                assert closed.isError is False
        finally:
            await service.close()

    anyio.run(scenario)


@pytest.mark.skipif(
    "RECAF_MCP_TEST_ANDROID_INPUT" not in os.environ,
    reason="Android APK reference input not requested",
)
def test_android_analysis_and_mapping_tools_against_reference_apk(
    tmp_path: Path,
) -> None:
    recaf_jar = Path(os.environ["RECAF_MCP_TEST_RECAF_JAR"]).resolve(strict=True)
    plugin_jar = Path(os.environ["RECAF_MCP_TEST_PLUGIN_JAR"]).resolve(
        strict=True
    )
    input_path = Path(os.environ["RECAF_MCP_TEST_ANDROID_INPUT"]).resolve(
        strict=True
    )
    config = BridgeConfig(
        recaf_jar=recaf_jar,
        plugin_jar=plugin_jar,
        session_root=(tmp_path / "sessions").resolve(),
        max_sessions=1,
        launch_timeout_sec=60,
    )
    service = BridgeToolService(config, watchdog_interval_sec=0.05)
    server = create_server(service)

    async def scenario() -> None:
        try:
            async with create_connected_server_and_client_session(server) as client:
                opened = await client.call_tool(
                    "recaf_open",
                    {"input_path": str(input_path)},
                )
                assert opened.isError is False
                assert opened.structuredContent["autosave_path"] is None
                assert opened.structuredContent[
                    "workspace_archive_export_supported"
                ] is False
                database = opened.structuredContent["database"]

                apk_files = await client.call_tool(
                    "list_files",
                    {
                        "database": database,
                        "filter": "AndroidManifest.xml",
                    },
                )
                assert apk_files.isError is False
                assert apk_files.structuredContent["total"] == 1
                assert apk_files.structuredContent["paths"] == [
                    "AndroidManifest.xml"
                ]
                manifest_file = apk_files.structuredContent["files"][0]
                assert manifest_file["kind"] == "android_binary_xml"
                assert manifest_file["is_text"] is False
                assert manifest_file["text_available"] is True
                manifest = await client.call_tool(
                    "read_file",
                    {
                        "database": database,
                        "path": "AndroidManifest.xml",
                    },
                )
                assert manifest.isError is False
                manifest_content = manifest.structuredContent
                assert manifest_content["format"] == "text"
                assert manifest_content["decoded"] is True
                assert manifest_content["text_source"] == (
                    "recaf_android_binary_xml_decoder"
                )
                assert manifest_content["encoding"] == "UTF-8"
                decoded_manifest = manifest_content["content_text"]
                assert decoded_manifest.startswith("<?xml")
                assert "<manifest" in decoded_manifest
                assert "package=" in decoded_manifest
                assert "<application" in decoded_manifest

                with zipfile.ZipFile(input_path) as apk:
                    raw_manifest = apk.read("AndroidManifest.xml")
                assert manifest_content["size_bytes"] == len(raw_manifest)
                assert manifest_content["sha256"] == hashlib.sha256(
                    raw_manifest
                ).hexdigest()

                raw = await client.call_tool(
                    "read_file",
                    {
                        "database": database,
                        "path": "AndroidManifest.xml",
                        "format": "base64",
                    },
                )
                assert raw.isError is False
                assert raw.structuredContent["format"] == "base64"
                assert raw.structuredContent["decoded"] is False
                assert base64.b64decode(
                    raw.structuredContent["content_base64"]
                ) == raw_manifest

                listed = await client.call_tool(
                    "list_classes",
                    {
                        "database": database,
                        "scope": "android",
                        "filter": "me/naptila/example/FlowTest",
                    },
                )
                assert listed.isError is False
                assert listed.structuredContent["total"] == 1
                entry = listed.structuredContent["class_entries"][0]
                assert entry["class_scope"] == "android"
                dex_name = entry["dex_name"]
                exact = {
                    "database": database,
                    "scope": "android",
                    "dex_name": dex_name,
                    "class_name": entry["class_name"],
                }

                decompiled = await client.call_tool(
                    "decompile_class",
                    {**exact, "decompiler": "CFR"},
                )
                assert decompiled.isError is False
                assert decompiled.structuredContent["decompilation_input"] == (
                    "recaf_android_to_jvm"
                )
                assert "FlowTest" in decompiled.structuredContent["source"]

                android_source_archive = tmp_path / "decompiled-apk-sources.zip"
                android_source_export = await client.call_tool(
                    "export_sources",
                    {
                        "database": database,
                        "output_path": str(android_source_archive),
                        "scope": "android",
                        "dex_name": dex_name,
                        "filter": entry["class_name"],
                        "decompiler": "CFR",
                    },
                )
                assert android_source_export.isError is False, android_source_export
                android_source_metadata = android_source_export.structuredContent
                assert android_source_metadata["scope"] == (
                    "primary_android_decompiled"
                )
                assert android_source_metadata["dex_name"] == dex_name
                assert android_source_metadata["classes_selected"] == 1
                assert android_source_metadata["classes_exported"] == 1
                assert android_source_metadata["complete"] is True
                android_source_path = (
                    f"android/{dex_name}/{entry['class_name']}.java"
                )
                with zipfile.ZipFile(android_source_archive) as archive:
                    android_source = archive.read(android_source_path).decode(
                        "utf-8"
                    )
                    android_source_manifest = json.loads(
                        archive.read("_recaf-mcp/manifest.json")
                    )
                assert "FlowTest" in android_source
                assert android_source_manifest["requested_scope"] == "android"
                assert android_source_manifest["classes_exported"] == 1

                bytecode = await client.call_tool("get_class_bytecode", exact)
                assert bytecode.isError is False
                assert bytecode.structuredContent["format"] == "dex"
                assert bytecode.structuredContent["representation"] == (
                    "reconstructed_single_class_dex"
                )
                payload = base64.b64decode(
                    bytecode.structuredContent["bytecode_base64"]
                )
                assert payload.startswith(b"dex\n039\0")

                source_hits = await client.call_tool(
                    "search_code",
                    {
                        "database": database,
                        "scope": "android",
                        "dex_name": dex_name,
                        "filter": entry["class_name"],
                        "text": "switch",
                        "decompiler": "CFR",
                    },
                )
                assert source_hits.isError is False
                assert source_hits.structuredContent["total"] > 0
                assert all(
                    hit["class_scope"] == "android"
                    and hit["dex_name"] == dex_name
                    for hit in source_hits.structuredContent["hits"]
                )

                string_hits = await client.call_tool(
                    "search_strings",
                    {
                        "database": database,
                        "scope": "android",
                        "text": "test",
                        "limit": 5,
                    },
                )
                assert string_hits.isError is False
                assert string_hits.structuredContent["total"] > 0
                assert all(
                    hit["class_scope"] == "android"
                    and hit["dex_name"].endswith(".dex")
                    for hit in string_hits.structuredContent["hits"]
                )

                member_hits = await client.call_tool(
                    "search_members",
                    {
                        "database": database,
                        "scope": "android",
                        "name": "main",
                        "kind": "method",
                        "results": "declarations",
                        "filter": "me/naptila/example",
                    },
                )
                assert member_hits.isError is False
                assert member_hits.structuredContent["total"] > 0
                assert all(
                    hit["class_scope"] == "android"
                    and hit["dex_name"].endswith(".dex")
                    for hit in member_hits.structuredContent["hits"]
                )

                instruction_hits = await client.call_tool(
                    "search_instructions",
                    {
                        "database": database,
                        "scope": "android",
                        "dex_name": dex_name,
                        "filter": entry["class_name"],
                        "text": "switch",
                    },
                )
                assert instruction_hits.isError is False
                assert instruction_hits.structuredContent["classes_selected"] == 1
                assert instruction_hits.structuredContent["total"] > 0
                assert all(
                    hit["class_scope"] == "android"
                    and hit["dex_name"] == dex_name
                    and hit["dex_offset"] >= 0
                    for hit in instruction_hits.structuredContent["hits"]
                )

                method = {
                    "database": database,
                    "scope": "android",
                    "dex_name": dex_name,
                    "owner": entry["class_name"],
                    "name": "sparseSwitch",
                    "descriptor": "(I)I",
                }
                method_assembly = await client.call_tool(
                    "get_method_assembly",
                    method,
                )
                assert method_assembly.isError is False
                assert method_assembly.structuredContent["assembly_format"] == (
                    "android_dex_method_disassembly"
                )
                assert method_assembly.structuredContent["editable"] is False
                assert method_assembly.structuredContent["patch_supported"] is False
                assert method_assembly.structuredContent["instruction_count"] > 0

                graph = await client.call_tool(
                    "call_graph",
                    {
                        **{
                            key: value
                            for key, value in method.items()
                            if key != "dex_name"
                        },
                        "name": "main",
                        "descriptor": "([Ljava/lang/String;)V",
                        "direction": "calls",
                    },
                )
                assert graph.isError is False
                assert graph.structuredContent["graph_source"] == (
                    "dex_invoke_instructions"
                )
                assert graph.structuredContent["classes_selected"] == 16_433
                assert graph.structuredContent["total"] > 0
                assert graph.structuredContent["root"]["class_scope"] == "android"
                assert all(
                    edge["caller"]["class_scope"] == "android"
                    for edge in graph.structuredContent["edges"]
                )

                renamed_method = "sparseSwitchMapped"
                renamed = await client.call_tool(
                    "rename_symbol",
                    {
                        **method,
                        "kind": "method",
                        "new_name": renamed_method,
                    },
                )
                assert renamed.isError is False, renamed
                rename_metadata = renamed.structuredContent
                assert rename_metadata["class_scope"] == "android"
                assert rename_metadata["scope"] == "primary_android"
                assert rename_metadata["dex_name"] == dex_name
                assert rename_metadata["android_changed_classes"] >= 1
                assert rename_metadata["jvm_changed_classes"] == 0
                assert rename_metadata["workspace_mutated_in_memory"] is True
                assert rename_metadata["in_memory_only"] is True
                assert (
                    rename_metadata["workspace_archive_export_supported"]
                    is False
                )
                assert rename_metadata["save_required"] is False

                renamed_assembly = await client.call_tool(
                    "get_method_assembly",
                    {
                        **method,
                        "name": renamed_method,
                    },
                )
                assert renamed_assembly.isError is False
                assert renamed_assembly.structuredContent["name"] == renamed_method

                old_class = entry["class_name"]
                new_class = old_class + "Mapped"
                simple_mapping = f"{old_class} {new_class}\n"
                mapping_preview = await client.call_tool(
                    "apply_mappings",
                    {
                        "database": database,
                        "format": "Simple",
                        "mappings": simple_mapping,
                        "scope": "android",
                        "dry_run": True,
                    },
                )
                assert mapping_preview.isError is False, mapping_preview
                assert mapping_preview.structuredContent["applied"] is False
                assert mapping_preview.structuredContent[
                    "selected_scope_changed_classes"
                ] >= 1
                assert mapping_preview.structuredContent[
                    "android_changed_classes"
                ] >= 1
                assert mapping_preview.structuredContent["save_required"] is False
                assert mapping_preview.structuredContent[
                    "workspace_mutated_in_memory"
                ] is False
                old_after_preview = await client.call_tool(
                    "decompile_class",
                    exact,
                )
                assert old_after_preview.isError is False

                mapped = await client.call_tool(
                    "apply_mappings",
                    {
                        "database": database,
                        "format": "Simple",
                        "mappings": simple_mapping,
                        "scope": "android",
                    },
                )
                assert mapped.isError is False, mapped
                mapping_metadata = mapped.structuredContent
                assert mapping_metadata["applied"] is True
                assert mapping_metadata["requested_scope"] == "android"
                assert mapping_metadata["scope"] == "primary_android"
                assert mapping_metadata["selected_scope_changed_classes"] >= 1
                assert mapping_metadata["android_changed_classes"] >= 1
                assert mapping_metadata["workspace_mutated_in_memory"] is True
                assert mapping_metadata["in_memory_only"] is True
                assert mapping_metadata["save_required"] is False
                assert (
                    mapping_metadata["workspace_archive_export_supported"]
                    is False
                )
                assert any(
                    change["old_name"] == old_class
                    and change["new_name"] == new_class
                    and change["class_scope"] == "android"
                    and change["dex_name"] == dex_name
                    for change in mapping_metadata["class_changes"]
                )

                mapped_class = await client.call_tool(
                    "decompile_class",
                    {
                        "database": database,
                        "scope": "android",
                        "dex_name": dex_name,
                        "class_name": new_class,
                    },
                )
                assert mapped_class.isError is False, mapped_class
                assert renamed_method in mapped_class.structuredContent["source"]

                mapping_formats = {
                    "Enigma",
                    "Jadx (Legacy)",
                    "Proguard",
                    "Simple",
                    "SRG",
                    "Tiny-V1",
                    "Tiny-V2",
                }
                exported_documents: dict[str, str] = {}
                for mapping_format in sorted(mapping_formats):
                    exported = await client.call_tool(
                        "export_mappings",
                        {
                            "database": database,
                            "format": mapping_format,
                        },
                    )
                    assert exported.isError is False, exported
                    export_metadata = exported.structuredContent
                    assert export_metadata["scope"] == (
                        "aggregate_workspace_mappings"
                    )
                    assert export_metadata["mapping_text_exported"] is True
                    assert export_metadata["workspace_archive_exported"] is False
                    assert export_metadata["save_required"] is False
                    assert export_metadata["mapping_counts"] == {
                        "classes": 1,
                        "fields": 0,
                        "methods": 1,
                        "variables": 0,
                        "total": 2,
                    }
                    document = export_metadata["content_text"]
                    assert document
                    assert hashlib.sha256(document.encode("utf-8")).hexdigest() == (
                        export_metadata["mapping_sha256"]
                    )
                    exported_documents[mapping_format] = document

                simple_export = exported_documents["Simple"]
                assert simple_mapping.strip() in simple_export
                assert (
                    f"{old_class}.sparseSwitch(I)I {renamed_method}"
                    in simple_export
                )

                closed = await client.call_tool(
                    "recaf_close",
                    {"database": database},
                )
                assert closed.isError is False
                assert closed.structuredContent["saved"] is False
        finally:
            await service.close()

    anyio.run(scenario)
