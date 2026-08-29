package com.summit.tools.file.edit;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.conversation.event.FileEditEvent;
import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.runtime.WorkspaceBridge;
import com.summit.harnesscore.tool.*;
import com.summit.tools.arguments.EditFileRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;


@Slf4j
@RequiredArgsConstructor
public class EditFileToolExecutor implements ToolExecutor {
    private final ObjectMapper objectMapper;
    private final Differ differ;
    private final PatchManager patchManager;
    private final FileHasher fileHasher;
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final int DEFAULT_AROUND_LINE = 3;

    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        String args = toolExecution.getArgs();
        try {
            EditFileRequest request = objectMapper.readValue(args, EditFileRequest.class);
            log.info("【ToolCall】 edit_file :{}", request.getPath());

            Workspace workspace = toolExecution.getWorkspace();
            WorkspaceBridge bridge = workspace.bridge();

            //step1 validate file whether exist or not (inside the workspace environment)
            Path target = ensureFileExists(request.getPath(), workspace);

            //edit: all content IO goes through the bridge so it lands in the workspace environment
            String oldContent = bridge.readString(target, workspace.runtimeEnvironment().charset());
            FileEditorResult editRes = FileEditor.edit(request, oldContent, workspace.runtimeEnvironment().charset(), DEFAULT_AROUND_LINE);

            if (editRes.isSuccess()) {
                DiffResult diffResult = doDiff(editRes.getData(), request.getPath());
                Object id = patchManager.savePatch(toolExecution.getSessionId(),
                        buildPatchEntity(diffResult, fileHasher.hash(editRes.getData().oldContent()), request.getPath()));

                publicEvent(id, request.getPath(), editRes.getData().oldContent(), editRes.getData().newContent(), toolExecution.getSessionId());
                return ToolExecuteResult.success(toolExecution.getId(), toolExecution.getToolDefinition(),
                        objectMapper.writeValueAsString(diffResult)
                );
            }

            return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolDefinition(), editRes.getErrMsg());


        } catch (IOException e) {
            return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolDefinition(), e.getMessage());
        }


    }

    private void publicEvent(Object id, String path, String oldContent, String newContent, java.io.Serializable sessionId) {
        this.runtimeEventPublisher.onFileEdit(FileEditEvent.builder()
                .patchId(id)
                .filePath(path)
                .oldContent(oldContent)
                .newContent(newContent)
                .sessionId(sessionId)
                .build());
    }




    private PatchEntity buildPatchEntity(DiffResult diffResult, String fileContentHash, String filePath) {
        return PatchEntity.builder()
                .fileContentHash(fileContentHash)
                .filePath(filePath)
                .patch(diffResult.getPatch())
                .build();
    }

    private DiffResult doDiff(FileEditorResult.ContentInfo data,String path) {
        return this.differ.diff(path,data.oldContent(), data.newContent());
    }

    private Path ensureFileExists(String path, Workspace workspace) throws IOException {
        WorkspaceBridge bridge = workspace.bridge();
        Path resolvePath = workspace.resolve(path);
        Path parent = resolvePath.getParent();
        if (parent != null && !bridge.exists(parent)) {
            bridge.createDirectories(parent);
        }
        bridge.createFile(resolvePath);
        return resolvePath;
    }
}
