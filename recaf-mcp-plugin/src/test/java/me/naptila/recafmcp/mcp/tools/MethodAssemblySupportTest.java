package me.naptila.recafmcp.mcp.tools;

import me.darknet.assembler.error.Diagnostic;
import me.darknet.assembler.error.DiagnosticCode;
import me.darknet.assembler.error.DiagnosticPhase;
import me.darknet.assembler.error.Outcome;
import me.darknet.assembler.util.Location;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.assembler.AndroidAssemblerPipelineConfig;
import software.coley.recaf.services.assembler.AssemblerPipelineGeneralConfig;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.assembler.JvmAssemblerPipelineConfig;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.inheritance.InheritanceGraphServiceConfig;
import software.coley.recaf.services.mapping.MappingListeners;
import software.coley.recaf.services.mapping.MappingListenersConfig;
import software.coley.recaf.services.workspace.BasicWorkspaceManager;
import software.coley.recaf.services.workspace.WorkspaceManagerConfig;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MethodAssemblySupportTest {
    @Test
    void preservesErrorsWarningsAndLocationsFromPartialOutcomes() {
        Outcome<String> outcome = Outcome.partial("partial", List.of(
                Diagnostic.error(DiagnosticPhase.SYNTAX, DiagnosticCode.UNEXPECTED_TOKEN,
                        "bad token", new Location(2, 3, 4, "<mcp>")),
                Diagnostic.warning(DiagnosticPhase.OUTPUT_VERIFICATION,
                        DiagnosticCode.VERIFICATION_WARNING, "warning", null)
        ));

        var diagnostics = MethodAssemblySupport.RecafMethodAssembler.diagnostics("parse", outcome);

        assertEquals(List.of(
                new MethodAssemblySupport.Diagnostic("error", "parse", 2, 3, 4, "bad token"),
                new MethodAssemblySupport.Diagnostic("warning", "parse", -1, -1, -1, "warning")
        ), diagnostics);
        assertFalse(new MethodAssemblySupport.OperationResult<>(outcome.value(), diagnostics).success());
    }

    @Test
    void boundsDiagnosticMessagesAndKeepsWarningOnlySuccess() {
        Outcome<String> outcome = Outcome.success("assembly", List.of(
                Diagnostic.warning(DiagnosticPhase.OUTPUT_VERIFICATION,
                        DiagnosticCode.VERIFICATION_WARNING, "w".repeat(2_000), null)
        ));
        var diagnostics = MethodAssemblySupport.RecafMethodAssembler.diagnostics("assemble", outcome);
        assertEquals(MethodAssemblySupport.MAX_DIAGNOSTIC_MESSAGE, diagnostics.getFirst().message().length());
        assertTrue(new MethodAssemblySupport.OperationResult<>(outcome.value(), diagnostics).success());
        assertTrue(MethodAssemblySupport.RecafMethodAssembler.diagnostics(
                "disassemble", Outcome.success("assembly")).isEmpty());
    }

    @Test
    void realPipelineDisassemblesAndReassemblesAnExactMethod() throws IOException {
        JvmClassInfo info = fixtureClass();
        Workspace workspace = workspace(info);
        try {
            var assembler = assembler();
            var target = MethodAssemblySupport.resolve(workspace, info.getName(), "fixture", "()I");
            var disassembled = assembler.disassemble(workspace, target);
            assertTrue(disassembled.success(), disassembled.diagnostics().toString());
            assertTrue(disassembled.value().contains("bipush 7"), disassembled.value());

            var assembled = assembler.assemble(workspace, target, disassembled.value().replace("bipush 7", "bipush 8"));
            assertTrue(assembled.success(), assembled.diagnostics().toString());
            assertNull(MethodAssemblySupport.validateReplacement(target, assembled.value()));
            assertArrayEquals(info.getBytecode(), workspace.getPrimaryResource().getJvmClassBundle()
                    .get(info.getName()).getBytecode());
            workspace.getPrimaryResource().getJvmClassBundle().put(assembled.value());
            var changedTarget = MethodAssemblySupport.resolve(workspace, info.getName(), "fixture", "()I");
            assertTrue(assembler.disassemble(workspace, changedTarget).value().contains("bipush 8"));
        } finally {
            workspace.close();
        }
    }

    @Test
    void realPipelineRejectsInvalidSourceWithoutChangingTheClass() throws IOException {
        JvmClassInfo info = fixtureClass();
        Workspace workspace = workspace(info);
        try {
            var assembler = assembler();
            var target = MethodAssemblySupport.resolve(workspace, info.getName(), "fixture", "()I");
            var disassembled = assembler.disassemble(workspace, target);
            assertTrue(disassembled.success(), disassembled.diagnostics().toString());
            for (String invalid : List.of("\"unterminated", disassembled.value().replace("bipush 7", "bad_opcode"))) {
                var result = assembler.assemble(workspace, target, invalid);
                assertFalse(result.success());
                assertNull(result.value());
                assertTrue(result.diagnostics().stream().anyMatch(d -> d.level().equals("error")));
                assertArrayEquals(info.getBytecode(), workspace.getPrimaryResource().getJvmClassBundle()
                        .get(info.getName()).getBytecode());
            }
        } finally {
            workspace.close();
        }
    }

    private static MethodAssemblySupport.RecafMethodAssembler assembler() {
        var manager = new BasicWorkspaceManager(new WorkspaceManagerConfig());
        var graph = new InheritanceGraphService(manager,
                new MappingListeners(new MappingListenersConfig()), new InheritanceGraphServiceConfig());
        return new MethodAssemblySupport.RecafMethodAssembler(new AssemblerPipelineManager(
                manager, graph, new AssemblerPipelineGeneralConfig(),
                new AndroidAssemblerPipelineConfig(), new JvmAssemblerPipelineConfig()));
    }

    private static Workspace workspace(JvmClassInfo info) {
        var bundle = new BasicJvmClassBundle();
        bundle.put(info);
        return new BasicWorkspace(new WorkspaceResourceBuilder().withJvmClassBundle(bundle).build(), List.of(), false);
    }

    private static JvmClassInfo fixtureClass() throws IOException {
        try (InputStream input = MethodAssemblySupportTest.class.getResourceAsStream("MethodAssemblySupportTest.class")) {
            assertNotNull(input);
            return new JvmClassInfoBuilder(input.readAllBytes()).build();
        }
    }

    private static int fixture() {
        return 7;
    }
}
