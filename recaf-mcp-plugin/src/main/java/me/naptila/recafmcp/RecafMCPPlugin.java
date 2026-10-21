package me.naptila.recafmcp;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.naptila.recafmcp.mcp.McpHttpServer;
import me.naptila.recafmcp.mcp.McpRequestHandler;
import me.naptila.recafmcp.mcp.McpToolRegistry;
import me.naptila.recafmcp.mcp.WorkerHealth;
import me.naptila.recafmcp.mcp.RecafRuntimeInfo;
import me.naptila.recafmcp.mcp.android.AndroidWorkspaceSupport;
import me.naptila.recafmcp.mcp.android.AndroidInputImporter;
import me.naptila.recafmcp.mcp.WorkerOperationCoordinator;
import me.naptila.recafmcp.mcp.internal.HealthHandler;
import me.naptila.recafmcp.mcp.internal.RecafHeadlessServicesProbe;
import me.naptila.recafmcp.mcp.internal.RecafSaveWorkspaceHandler;
import me.naptila.recafmcp.mcp.internal.ShutdownHandler;
import me.naptila.recafmcp.mcp.tools.ApplyMappingsTool;
import me.naptila.recafmcp.mcp.tools.CallGraphTool;
import me.naptila.recafmcp.mcp.tools.DecompileClassTool;
import me.naptila.recafmcp.mcp.tools.ExportDecompiledSourcesTool;
import me.naptila.recafmcp.mcp.tools.ExportMappingsTool;
import me.naptila.recafmcp.mcp.tools.GetClassBytecodeTool;
import me.naptila.recafmcp.mcp.tools.GetMethodAssemblyTool;
import me.naptila.recafmcp.mcp.tools.GetRecafScriptHelpTool;
import me.naptila.recafmcp.mcp.tools.ListClassesTool;
import me.naptila.recafmcp.mcp.tools.ListFilesTool;
import me.naptila.recafmcp.mcp.tools.PatchClassTool;
import me.naptila.recafmcp.mcp.tools.PatchInstructionsTool;
import me.naptila.recafmcp.mcp.tools.ReadFileTool;
import me.naptila.recafmcp.mcp.tools.RenameSymbolTool;
import me.naptila.recafmcp.mcp.tools.RunRecafScriptTool;
import me.naptila.recafmcp.mcp.tools.SearchCodeTool;
import me.naptila.recafmcp.mcp.tools.SearchInstructionsTool;
import me.naptila.recafmcp.mcp.tools.SearchMembersTool;
import me.naptila.recafmcp.mcp.tools.SearchStringsTool;
import me.naptila.recafmcp.mcp.tools.SearchStructureTool;
import me.naptila.recafmcp.mcp.tools.TransformWorkspaceTool;
import me.naptila.recafmcp.mcp.tools.WorkspaceInfoTool;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.properties.builtin.InputFilePathProperty;
import software.coley.recaf.launch.LaunchArguments;
import software.coley.recaf.plugin.Plugin;
import software.coley.recaf.plugin.PluginInformation;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.callgraph.CallGraphService;
import software.coley.recaf.services.compile.JavacCompiler;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.MappingListeners;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.mapping.format.MappingFormatManager;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.search.match.StringPredicateProvider;
import software.coley.recaf.services.script.ScriptEngine;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.workspace.WorkspaceOpenListener;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.ResourceImporterConfig;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.services.workspace.io.InfoImporter;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Dependent
@PluginInformation(
        id = RecafMCPPlugin.PLUGIN_ID,
        version = RecafMCPPlugin.PLUGIN_VERSION,
        name = RecafMCPPlugin.PLUGIN_NAME,
        description = RecafMCPPlugin.PLUGIN_DESCRIPTION
)
public class RecafMCPPlugin implements Plugin {
    public static final String PLUGIN_ID = "me.naptila.recaf-mcp-plugin";
    public static final String PLUGIN_VERSION = "1.1.1";
    public static final String PLUGIN_NAME = "Recaf MCP Plugin";
    public static final String PLUGIN_DESCRIPTION =
            "MCP server for autonomous Java reverse engineering via LLM agents";
    private static final Logger logger = Logging.get(RecafMCPPlugin.class);
    private final WorkspaceManager workspaceManager;
    private final LaunchArguments launchArguments;
    private final DecompilerManager decompilerManager;
    private final CallGraphService callGraphService;
    private final AssemblerPipelineManager assemblerPipelineManager;
    private final JavacCompiler javacCompiler;
    private final MappingApplierService mappingApplierService;
    private final InheritanceGraphService inheritanceGraphService;
    private final MappingListeners mappingListeners;
    private final AggregateMappingManager aggregateMappingManager;
    private final MappingFormatManager mappingFormatManager;
    private final SearchService searchService;
    private final StringPredicateProvider stringPredicateProvider;
    private final TransformationApplierService transformationApplierService;
    private final ScriptEngine scriptEngine;
    private final ResourceImporterConfig resourceImporterConfig;
    private final ResourceImporter resourceImporter;
    private final InfoImporter infoImporter;
    private ExecutorService inputLoader;
    private McpHttpServer server;
    private WorkerOperationCoordinator operationCoordinator;
    private WorkspaceOpenListener workspaceOpenListener;

    @Inject
    public RecafMCPPlugin(WorkspaceManager workspaceManager,
                          LaunchArguments launchArguments,
                          DecompilerManager decompilerManager,
                          CallGraphService callGraphService,
                          AssemblerPipelineManager assemblerPipelineManager,
                          JavacCompiler javacCompiler,
                          MappingApplierService mappingApplierService,
                          InheritanceGraphService inheritanceGraphService,
                          MappingListeners mappingListeners,
                          AggregateMappingManager aggregateMappingManager,
                          MappingFormatManager mappingFormatManager,
                          SearchService searchService,
                          StringPredicateProvider stringPredicateProvider,
                          TransformationApplierService transformationApplierService,
                          ScriptEngine scriptEngine,
                          ResourceImporterConfig resourceImporterConfig,
                          ResourceImporter resourceImporter,
                          InfoImporter infoImporter) {
        this.workspaceManager = workspaceManager;
        this.launchArguments = launchArguments;
        this.decompilerManager = decompilerManager;
        this.callGraphService = callGraphService;
        this.assemblerPipelineManager = assemblerPipelineManager;
        this.javacCompiler = javacCompiler;
        this.mappingApplierService = mappingApplierService;
        this.inheritanceGraphService = inheritanceGraphService;
        this.mappingListeners = mappingListeners;
        this.aggregateMappingManager = aggregateMappingManager;
        this.mappingFormatManager = mappingFormatManager;
        this.searchService = searchService;
        this.stringPredicateProvider = stringPredicateProvider;
        this.transformationApplierService = transformationApplierService;
        this.scriptEngine = scriptEngine;
        this.resourceImporterConfig = resourceImporterConfig;
        this.resourceImporter = resourceImporter;
        this.infoImporter = infoImporter;
    }

    @Override
    public void onEnable() {
        String token = System.getenv("RECAF_MCP_TOKEN");
        if (token == null || token.isBlank()) {
            logger.debug("RECAF_MCP_TOKEN is not set; the MCP Worker server is disabled");
            return;
        }

        Path expectedInputPath = resolveExpectedInputPath();
        if (expectedInputPath != null) {
            // Large multidex APKs otherwise parse all DEX pools concurrently.
            // This config belongs to the Bridge's isolated Worker data directory.
            resourceImporterConfig.doParallelize().setValue(false);
        }
        WorkerHealth health = new WorkerHealth(
                workspaceManager::hasCurrentWorkspace,
                expectedInputPath == null ? null : expectedInputPath.toString(),
                PLUGIN_VERSION,
                RecafRuntimeInfo.current()
        );
        validateExpectedInput(expectedInputPath, health);

        workspaceOpenListener = new WorkspaceOpenListener() {
            @Override
            public void onWorkspaceOpened(Workspace workspace) {
                markWorkspaceReady(workspace, health);
            }

            @Override
            public int getPriority() {
                return Integer.MAX_VALUE;
            }
        };
        workspaceManager.addWorkspaceOpenListener(workspaceOpenListener);
        if (workspaceManager.hasCurrentWorkspace()) {
            markWorkspaceReady(workspaceManager.getCurrent(), health);
        }

        int requestedPort = Integer.getInteger("recaf.mcp.port", 0);
        try {
            McpToolRegistry toolRegistry = new McpToolRegistry(health.getRecafRuntime().compatible() ? List.of(
                    new WorkspaceInfoTool(workspaceManager),
                    new ListClassesTool(workspaceManager),
                    new ListFilesTool(workspaceManager),
                    new ReadFileTool(workspaceManager),
                    new DecompileClassTool(workspaceManager, decompilerManager),
                    new ExportDecompiledSourcesTool(workspaceManager, decompilerManager),
                    new SearchStringsTool(
                            workspaceManager,
                            searchService,
                            stringPredicateProvider
                    ),
                    new SearchInstructionsTool(
                            workspaceManager,
                            searchService,
                            stringPredicateProvider
                    ),
                    new SearchMembersTool(
                            workspaceManager,
                            searchService,
                            stringPredicateProvider
                    ),
                    new SearchCodeTool(workspaceManager, decompilerManager),
                    new SearchStructureTool(workspaceManager),
                    new RenameSymbolTool(
                            workspaceManager,
                            mappingApplierService,
                            searchService,
                            inheritanceGraphService,
                            mappingListeners,
                            aggregateMappingManager
                    ),
                    new ApplyMappingsTool(
                            workspaceManager,
                            mappingFormatManager,
                            mappingApplierService,
                            searchService,
                            inheritanceGraphService,
                            mappingListeners,
                            aggregateMappingManager
                    ),
                    new ExportMappingsTool(
                            workspaceManager,
                            mappingFormatManager,
                            aggregateMappingManager
                    ),
                    new CallGraphTool(workspaceManager, callGraphService),
                    new TransformWorkspaceTool(workspaceManager, transformationApplierService),
                    new PatchClassTool(workspaceManager, javacCompiler),
                    new GetMethodAssemblyTool(workspaceManager, assemblerPipelineManager),
                    new PatchInstructionsTool(workspaceManager, assemblerPipelineManager),
                    new GetClassBytecodeTool(workspaceManager),
                    new GetRecafScriptHelpTool(),
                    new RunRecafScriptTool(workspaceManager, scriptEngine)
            ) : List.of(new GetRecafScriptHelpTool()));
            operationCoordinator = new WorkerOperationCoordinator();
            McpRequestHandler requestHandler = new McpRequestHandler(
                    health,
                    toolRegistry,
                    operationCoordinator,
                    new HealthHandler(health, toolRegistry, operationCoordinator),
                    new RecafHeadlessServicesProbe(
                            workspaceManager,
                            decompilerManager,
                            searchService,
                            stringPredicateProvider
                    ),
                    new RecafSaveWorkspaceHandler(workspaceManager),
                    new ShutdownHandler(operationCoordinator, RecafMCPPlugin::scheduleExit)
            );
            server = new McpHttpServer(requestedPort, token, requestHandler);
            server.start();
            logger.info("RECAF_MCP_READY host=127.0.0.1 port={}", server.getPort());
            if (System.getProperty("recaf.mcp.input_path") != null && expectedInputPath != null
                    && health.getRecafRuntime().compatible()) {
                inputLoader = Executors.newSingleThreadExecutor();
                inputLoader.submit(() -> loadInput(expectedInputPath, health));
            }
        } catch (IOException | IllegalArgumentException ex) {
            logger.error("Failed to start the MCP Worker server", ex);
        }
    }

    @Override
    public void onDisable() {
        if (inputLoader != null) {
            inputLoader.shutdownNow();
            inputLoader = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        if (operationCoordinator != null) {
            operationCoordinator.close();
            operationCoordinator = null;
        }
        if (workspaceOpenListener != null) {
            workspaceManager.removeWorkspaceOpenListener(workspaceOpenListener);
            workspaceOpenListener = null;
        }
    }

    private Path resolveExpectedInputPath() {
        String managedInput = System.getProperty("recaf.mcp.input_path");
        if (managedInput != null) return canonicalize(Path.of(managedInput));
        File input = launchArguments.getInput();
        if (input == null) {
            return null;
        }
        return canonicalize(input.toPath());
    }

    private void loadInput(Path input, WorkerHealth health) {
        try {
            var resource = new AndroidInputImporter(infoImporter).readIfAndroid(input);
            var workspaceResource = resource == null ? resourceImporter.importResource(input) : resource;
            if (Thread.currentThread().isInterrupted()) {
                workspaceResource.close();
                return;
            }
            var workspace = new BasicWorkspace(workspaceResource);
            try {
                workspaceManager.setCurrent(workspace);
            } catch (RuntimeException | Error error) {
                workspace.close();
                throw error;
            }
        } catch (Exception | LinkageError | OutOfMemoryError error) {
            logger.error("Failed to load MCP input", error);
            Throwable root = error;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            health.setLastError("Input import failed: " + error.getMessage() + "; "
                    + root.getClass().getSimpleName() + ": " + root.getMessage());
        }
    }

    private static void validateExpectedInput(Path expectedInputPath, WorkerHealth health) {
        if (expectedInputPath == null) {
            return;
        }
        if (!Files.exists(expectedInputPath)) {
            health.setLastError("expected input path does not exist");
        } else if (!Files.isReadable(expectedInputPath)) {
            health.setLastError("expected input path is not readable");
        }
    }

    private static void markWorkspaceReady(Workspace workspace, WorkerHealth health) {
        if (!(workspace.getPrimaryResource() instanceof WorkspaceFileResource fileResource)) {
            health.setLastError("workspace primary resource is not file-backed");
            return;
        }
        Path inputPath = InputFilePathProperty.get(fileResource.getFileInfo());
        if (inputPath == null) {
            health.setLastError("workspace primary resource has no input path");
            return;
        }
        try {
            AndroidWorkspaceSupport.prepare(workspace);
        } catch (IOException | RuntimeException ex) {
            logger.error("Failed to prepare Android workspace without data loss", ex);
            health.setLastError("Android workspace preparation failed: " + ex.getMessage());
            return;
        }
        health.markWorkspaceReady(
                canonicalize(inputPath).toString(),
                RecafSaveWorkspaceHandler.outputFormat(workspace)
        );
    }

    private static Path canonicalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException ex) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static void scheduleExit() {
        Thread shutdownThread = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "recaf-mcp-spike-shutdown");
        shutdownThread.setDaemon(false);
        shutdownThread.start();
    }
}
