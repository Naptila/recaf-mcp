from __future__ import annotations

import os
import re
from pathlib import Path

import anyio
import pytest

from application import BridgeToolService
from config import BridgeConfig


SCRIPT = '''\
import me.darknet.dex.tree.definitions.instructions.ConstInstruction;
import me.darknet.dex.tree.definitions.instructions.NopInstruction;
import me.darknet.dex.tree.definitions.code.Code;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;

int classes = 0;
int methods = 0;
for (var bundle : workspace.getPrimaryResource().getAndroidClassBundles().values()) {
    for (var info : bundle.valuesAsCopy()) {
        boolean changed = false;
        for (var method : info.getBackingDefinition().getMethods().values()) {
            if (method.getCode() == null) continue;
            var code = method.getCode();
            var instructions = new java.util.ArrayList<>(code.getInstructions());
            boolean methodChanged = false;
            if (info.getName().equals("sample/Fidelity") && method.getName().equals("mix")) {
                for (int index = 0; index < instructions.size(); index++) {
                    if (instructions.get(index) instanceof ConstInstruction c && c.value() == 17) {
                        instructions.set(index, new ConstInstruction(c.opcode(), c.register(), 18));
                        methods++;
                        methodChanged = true;
                    }
                }
            } else if (method.getName().equals("evaluate")
                    && (info.getName().equals("sample/Caller") || info.getName().equals("sample/OtherCaller"))) {
                instructions.add(0, new NopInstruction());
                methods++;
                methodChanged = true;
            }
            if (methodChanged) {
                var updated = new Code(code.getIn(), code.getOut(), code.getRegisters());
                updated.addInstructions(instructions);
                code.tryCatch().forEach(updated::addTryCatch);
                if (code.getDebugInfo() != null) updated.setDebugInfo(code.getDebugInfo());
                method.setCode(updated);
                changed = true;
            }
        }
        if (changed) {
            bundle.put(new AndroidClassInfoBuilder(info.getBackingDefinition()).build());
            classes++;
        }
    }
}
var result = new com.google.gson.JsonObject();
result.addProperty("classes", classes);
result.addProperty("methods", methods);
System.out.println("MCP_RESULT " + result);
'''


@pytest.mark.skipif(
    "RECAF_MCP_TEST_ANDROID_FIDELITY_DIR" not in os.environ,
    reason="independently encoded Android real-Worker fixtures not requested",
)
@pytest.mark.parametrize("output_format", ["apk", "dex"])
def test_script_batches_android_methods_across_dex_and_saves(tmp_path: Path, output_format: str) -> None:
    input_path = Path(os.environ["RECAF_MCP_TEST_ANDROID_FIDELITY_DIR"]) / f"fidelity.{output_format}"
    original = input_path.read_bytes()
    expected_names = ["sample/Caller", "sample/Fidelity"]
    if output_format == "apk":
        expected_names.append("sample/OtherCaller")
    config = BridgeConfig(
        recaf_jar=Path(os.environ["RECAF_MCP_TEST_RECAF_JAR"]),
        plugin_jar=Path(os.environ["RECAF_MCP_TEST_PLUGIN_JAR"]),
        session_root=tmp_path / "sessions", max_sessions=1,
    )

    async def scenario() -> None:
        service = BridgeToolService(config)

        async def call(name: str, arguments: dict) -> dict:
            response = await service.call(name, arguments)
            assert not response.isError, response.structuredContent
            return response.structuredContent

        try:
            database = (await call("recaf_open", {"input_path": str(input_path)}))["database"]
            method = {"database": database, "scope": "android", "owner": "sample/Fidelity",
                      "name": "mix", "descriptor": "(I)I"}
            before = await call("get_method_assembly", method)
            arguments = {"database": database, "script": SCRIPT}
            compiled = await call("run_recaf_script", arguments)
            assert compiled["compiled"] and not compiled["executed"]
            assert compiled["changed_class_count"] == 0
            after_compile = await call("get_method_assembly", method)
            assert before["assembly_sha256"] == after_compile["assembly_sha256"]
            executed = await call("run_recaf_script", {
                **arguments, "compile_only": False, "acknowledge_full_worker_access": True,
                "offset": 1, "limit": 1,
            })
            assert executed["compiled"] and executed["executed"]
            assert executed["script_result"] == {"classes": len(expected_names), "methods": len(expected_names)}
            assert executed["change_observation_scope"] == "primary_jvm_and_android_bundle_events"
            assert executed["changed_class_count"] == executed["modified_class_count"] == len(expected_names)
            assert executed["modified_classes"] == expected_names
            assert executed["workspace_changed"] is False
            assert executed["changed_classes"] == ["sample/Fidelity"]
            assert executed["returned"] == 1
            saved = tmp_path / f"batch.{output_format}"
            await call("recaf_save", {"database": database, "output_path": str(saved)})
            await call("recaf_close", {"database": database, "save": False})
            database = (await call("recaf_open", {"input_path": str(saved)}))["database"]
            patched = await call("get_method_assembly", {**method, "database": database})
            assert "18" in patched["assembly"]
            for caller in [name for name in expected_names if name != "sample/Fidelity"]:
                body = await call("get_method_assembly", {
                    "database": database, "scope": "android", "owner": caller,
                    "name": "evaluate", "descriptor": "()I",
                })
                assert "nop" in body["assembly"]
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
def test_bundled_android_examples_and_wrapped_runtime_diagnostics(tmp_path: Path, output_format: str) -> None:
    input_path = Path(os.environ["RECAF_MCP_TEST_ANDROID_FIDELITY_DIR"]) / f"fidelity.{output_format}"
    config = BridgeConfig(
        recaf_jar=Path(os.environ["RECAF_MCP_TEST_RECAF_JAR"]),
        plugin_jar=Path(os.environ["RECAF_MCP_TEST_PLUGIN_JAR"]),
        session_root=tmp_path / "sessions", max_sessions=1,
    )

    async def scenario() -> None:
        service = BridgeToolService(config)

        async def call(name: str, arguments: dict) -> dict:
            response = await service.call(name, arguments)
            assert not response.isError, response.structuredContent
            return response.structuredContent

        try:
            database = (await call("recaf_open", {"input_path": str(input_path)}))["database"]
            method = {"database": database, "scope": "android", "owner": "sample/Fidelity",
                      "name": "mix", "descriptor": "(I)I"}
            before = await call("get_method_assembly", method)
            guide = (await call("get_recaf_script_help", {
                "database": database, "topic": "workspace",
            }))["guide"]

            def example(heading: str) -> str:
                section = guide.split(f"### {heading}\n", 1)[1]
                match = re.search(r"```java\n(.*?)\n```", section, re.DOTALL)
                assert match is not None
                return match.group(1)

            patch = example("Replace one integer constant").replace(
                'String dexName = "classes.dex";', f'String dexName = "{before["dex_name"]}";',
            )
            compiled = await call("run_recaf_script", {"database": database, "script": patch})
            assert compiled["compiled"] and not compiled["executed"]
            assert compiled["changed_class_count"] == 0
            assert (await call("get_method_assembly", method))["assembly_sha256"] == before["assembly_sha256"]
            execution = {"database": database, "compile_only": False, "acknowledge_full_worker_access": True}
            patched = await call("run_recaf_script", {**execution, "script": patch})
            assert patched["script_result"] == {"replacements": 1}
            assert patched["modified_classes"] == ["sample/Fidelity"]
            assert patched["workspace_changed"] is False

            layout = await call("run_recaf_script", {**execution, "script": example("Invoke-range arguments")})
            assert layout["script_result"] == {"first": 2, "last": 5}
            assert layout["changed_class_count"] == 0

            failure = await service.call("run_recaf_script", {
                **execution,
                "script": 'throw new IllegalStateException("DEX script wrapper", '
                          'new java.lang.reflect.InvocationTargetException('
                          'new IllegalArgumentException("invalid DEX operand")));',
            })
            assert failure.isError
            details = failure.structuredContent
            assert details["compiled"] and details["executed"]
            assert details["runtime_root_cause_type"] == "java.lang.IllegalArgumentException"
            assert details["runtime_root_cause_message"] == "invalid DEX operand"
            assert details["runtime_error_chain_truncated"] is False
            chain = details["runtime_error_chain"]
            assert chain[0]["type"] == details["runtime_error_type"]
            assert any(cause["type"] == "java.lang.reflect.InvocationTargetException" for cause in chain)
            assert any(".java:" in frame for frame in chain[-1]["stack_trace"])
            assert details["changed_class_count"] == 0

            bad_accessor = await service.call("run_recaf_script", {
                **execution,
                "script": 'try { me.darknet.dex.tree.definitions.instructions.MoveExceptionInstruction.class'
                          '.getMethod("to"); } catch (ReflectiveOperationException cause) {'
                          'throw new IllegalStateException("NP string transform failed", cause); }',
            })
            assert bad_accessor.isError
            details = bad_accessor.structuredContent
            assert details["runtime_error_message"] == "NP string transform failed"
            assert details["runtime_root_cause_type"] == "java.lang.NoSuchMethodException"
            assert "MoveExceptionInstruction.to()" in details["runtime_root_cause_message"]
            assert details["runtime_error_chain_truncated"] is False
            assert details["changed_class_count"] == 0

            saved = tmp_path / f"example.{output_format}"
            await call("recaf_save", {"database": database, "output_path": str(saved)})
            await call("recaf_close", {"database": database, "save": False})
            database = (await call("recaf_open", {"input_path": str(saved)}))["database"]
            reopened = await call("get_method_assembly", {**method, "database": database})
            assert "18" in reopened["assembly"]
            await call("recaf_close", {"database": database, "save": False})
        finally:
            await service.close()

    anyio.run(scenario)
