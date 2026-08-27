package com.summit.tools.file.edit;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.difflib.patch.Patch;
import com.summit.harnesscore.conversation.event.FileEditEvent;
import com.summit.harnesscore.conversation.event.RuntimeEventPublisher;
import com.summit.harnesscore.exception.OutWorkSpaceException;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.*;
import com.summit.tools.arguments.EditFileRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EventListener;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@SuppressWarnings("unchecked")
public class EditFileToolExecutor implements ToolExecutor {
    private final ObjectMapper objectMapper;
    private final Differ differ;
    private final PatchManager patchManager;
    private final RuntimeEventPublisher runtimeEventPublisher;
    private final int DEFAULT_AROUND_LINE = 3;

    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        String args = toolExecution.getArgs();
        try {
            EditFileRequest request = objectMapper.readValue(args, EditFileRequest.class);
            log.info("【ToolCall】 edit_file :{}", request.getPath());

            //step1 validate file whether exist or not
            File target = ensureFileExists(request.getPath(), toolExecution.getWorkspace());
            //edit
            FileEditorResult editRes = FileEditor.edit(request, target, toolExecution.getWorkspace().runtimeEnvironment().charset(), DEFAULT_AROUND_LINE);

            if (editRes.isSuccess()) {
                DiffResult diffResult = doDiff(editRes.getData(), request.getPath());

                Object id = patchManager.savePatch(buildPatchEntity(diffResult, this.patchManager.hashFile(editRes.getData().oldContent()), request.getPath()));

                publicEvent(id, request.getPath(), editRes.getData().oldContent(), editRes.getData().newContent());
                return ToolExecuteResult.success(toolExecution.getId(), toolExecution.getToolDefinition().toolSpecification(),
                        objectMapper.writeValueAsString(diffResult)
                );
            }

            return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolDefinition().toolSpecification(), editRes.getErrMsg());


        } catch (OutWorkSpaceException | IOException e) {
            return ToolExecuteResult.err(toolExecution.getId(), toolExecution.getToolDefinition().toolSpecification(), e.getMessage());
        }


    }

    private void publicEvent(Object id, String path,String oldContent,String newContent) {
        this.runtimeEventPublisher.onFileEdit(FileEditEvent.builder()
                .patchId(id)
                .filePath(path)
                .oldContent(oldContent)
                .newContent(newContent)
                .build());
    }




    private PatchEntity<?> buildPatchEntity(DiffResult diffResult, String fileContentHash, String filePath) {
        return PatchEntity.builder()
                .fileContentHash(fileContentHash)
                .filePath(filePath)
                .patch(diffResult.getPatch())
                .build();
    }

    private DiffResult doDiff(FileEditorResult.ContentInfo data,String path) {
        return this.differ.diff(path,data.oldContent(), data.newContent());
    }

    private File ensureFileExists(String path, Workspace workspace) throws IOException {
        Path resolvePath = workspace.resolve(path);
        Path parent = resolvePath.getParent();
        if (Files.notExists(parent)) {
            Files.createDirectories(parent);
        }
        if (Files.notExists(resolvePath)) {
            Files.createFile(resolvePath);
        }

        return resolvePath.toFile();
    }
}
