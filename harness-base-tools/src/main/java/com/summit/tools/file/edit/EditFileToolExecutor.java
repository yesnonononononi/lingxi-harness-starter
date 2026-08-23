package com.summit.tools.file.edit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.summit.harnesscore.exception.OutWorkSpaceException;
import com.summit.harnesscore.runtime.Workspace;
import com.summit.harnesscore.tool.ToolExecuteResult;
import com.summit.harnesscore.tool.ToolExecution;
import com.summit.harnesscore.tool.ToolExecutor;

import com.summit.tools.arguments.EditFileRequest;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Path;

@RequiredArgsConstructor
public class EditFileToolExecutor implements ToolExecutor {
    private final ObjectMapper objectMapper;
    private final EditFileToolConfig editFileToolConfig;
    @Override
    public @NonNull ToolExecuteResult execute(ToolExecution toolExecution) {
        String args = toolExecution.getArgs();
        try {
            EditFileRequest request = objectMapper.readValue(args, EditFileRequest.class);
            //step1 validate file whether exist or not
            File target = validateFileExist(request.getPath(), toolExecution.getWorkspace());
            //edit
            FileEditorResult edit = FileEditor.edit(request, target, toolExecution.getWorkspace().getCharset(),this.editFileToolConfig.aroundLines());

            if(edit.isSuccess()){
                return ToolExecuteResult.success(toolExecution.getId(),toolExecution.getToolSpecification(),null);
            }

            return ToolExecuteResult.err(toolExecution.getId(),toolExecution.getToolSpecification(),edit.getErrMsg());


        } catch (JsonProcessingException | FileNotFoundException | OutWorkSpaceException e ) {
            return ToolExecuteResult.err(toolExecution.getId(),toolExecution.getToolSpecification(),e.getMessage());
        }


    }

    private File validateFileExist(String path, Workspace workspace) throws FileNotFoundException {
        Path resolvePath = workspace.resolve(path).normalize();
        if(!resolvePath.startsWith(workspace.getWorkDir())){
            throw new OutWorkSpaceException("Target File is out of workspace");
        }
        File target = resolvePath.toFile();
        if(!target.exists()){
            throw new FileNotFoundException("Target file does not exist");
        }
        return target;
    }
}
