package com.summit.runtime.workspace;


import com.summit.harnesscore.runtime.Workspace;
import lombok.Getter;
import java.nio.charset.Charset;


@Getter
public class LocalWorkSpace extends Workspace {

    @Override
    public Workspace resolve(String path) {
        return new LocalWorkSpace(this.workDir + "/" + path,this.charset);
    }
    public LocalWorkSpace(String workDir, Charset charset) {
        super(workDir, charset);
    }



}
