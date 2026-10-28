package com.summit.sandbox.mxc;

import com.summit.core.runtime.CommandHandler;
import com.summit.core.runtime.workspace.WorkspaceCommands.CommandResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real Node processes exercise framing and transport cancellation without requiring an MXC backend. */
class MxcBackgroundCommandTest {
    @TempDir Path directory;
    private MxcCommandManager commands;

    @BeforeEach void setup() throws Exception {
        Path node = null;
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "node.exe" : "node";
        for (String entry : System.getenv("PATH").split(File.pathSeparator)) {
            Path candidate = Path.of(entry, executable);
            if (Files.isRegularFile(candidate)) { node = candidate.toAbsolutePath(); break; }
        }
        assumeTrue(node != null, "Node is required for launcher protocol tests");
        try (InputStream resource = getClass().getResourceAsStream("/mxc/output-frames.mjs")) {
            assertNotNull(resource);
            Files.write(directory.resolve("output-frames.mjs"), resource.readAllBytes());
        }
        Path launcher = directory.resolve("fixture.mjs");
        Files.writeString(launcher, """
                import fs from 'node:fs';
                import path from 'node:path';
                import {createOutputCollector} from './output-frames.mjs';
                const [mode, controls, token] = process.argv.slice(2);
                fs.mkdirSync(controls,{recursive:true});
                if(mode==='cancel') {
                  fs.writeFileSync(path.join(controls,'cancel-attempt'),'');
                  if(fs.existsSync(path.join(controls,'fail-cancel'))) process.exit(7);
                  fs.writeFileSync(path.join(controls,token+'.cancel'),'');process.exit(0);
                }
                let input='';for await(const chunk of process.stdin)input+=chunk;
                const r=JSON.parse(input), output=createOutputCollector(r);
                if(!r.streamOutput)throw new Error('Expected framed output');
                if(r.command==='bad'){process.stdout.write('invalid-frame\\n');process.exit(0);}
                if(r.command==='startup')await new Promise(resolve=>setTimeout(resolve,250));
                const bytes=Buffer.from('\\u4e2d\\u6587-start');
                for(let offset=0;offset<bytes.length;offset+=2)output.consume(bytes.subarray(offset,offset+2));
                if(['fail','overflow','startup','zero'].includes(r.command)) {
                  output.finish({exitCode:r.command==='fail'?7:0,timedOut:false});process.exit(0);
                }
                const ready=path.join(controls,'ready');fs.writeFileSync(ready+'.tmp',token);fs.renameSync(ready+'.tmp',ready);
                const started=Date.now();
                while(!fs.existsSync(path.join(controls,'release'))&&!fs.existsSync(path.join(controls,token+'.cancel'))&&Date.now()-started<r.timeoutMs)
                  await new Promise(resolve=>setTimeout(resolve,10));
                const cancelled=fs.existsSync(path.join(controls,token+'.cancel'));
                const timedOut=Date.now()-started>=r.timeoutMs;
                output.finish({exitCode:cancelled?143:timedOut?-1:0,timedOut});
                """, StandardCharsets.UTF_8);
        MxcRuntime runtime = new MxcRuntime(node.toString(), directory.toString(), directory, Duration.ofSeconds(1), Duration.ofSeconds(1));
        commands = new MxcCommandManager(runtime, new MxcTransport(runtime, launcher, Map.of()));
    }

    @Test void snapshotsContainCommandTextBeforeExitAndCompletionIsCached() throws Exception {
        CommandHandler handle = start("progress", 5, 128);
        try {
            waitUntil(() -> handle.outputSnapshot().contains("中文-start"));
            assertFalse(handle.isDone());
            assertFalse(handle.outputSnapshot().contains("base64"));
            Files.writeString(directory.resolve("requests/release"), "");
            CommandResult result = handle.await();
            assertEquals("中文-start", result.output());
            assertSame(result, handle.await());
            assertFalse(handle.stop().stopped());
        } finally { handle.stop(); }
    }

    @Test void aFinishedFailureRetainsItsExitCodeAndLateWaitsReturnTheSameResult() throws Exception {
        CommandHandler handle = start("fail", 5, 128);
        waitUntil(handle::isDone);
        assertEquals(7, handle.await().exitCode());
        assertSame(handle.await(), handle.await());
    }

    @Test void multibyteCharactersUseAByteAllowanceAndTheCharacterSnapshotLimit() throws Exception {
        CommandResult result = start("overflow", 5, 2).await();
        assertEquals("中文", result.output());
        assertTrue(result.truncated());
        CommandResult empty = start("zero", 5, 0).await();
        assertEquals("", empty.output());
        assertTrue(empty.truncated());
    }

    @Test void timeoutsSettleWithoutAwaitAndStartupHasItsOwnAllowance() throws Exception {
        CommandHandler timeout = commands.startCommand("progress", null, StandardCharsets.UTF_8, 100, TimeUnit.MILLISECONDS, 64);
        waitUntil(timeout::isDone);
        assertTrue(timeout.await().timedOut());
        CommandResult startup = commands.startCommand("startup", null, StandardCharsets.UTF_8, 100, TimeUnit.MILLISECONDS, 64).await();
        assertEquals(0, startup.exitCode());
        assertFalse(startup.timedOut());
    }

    @Test void stopFailureIsVisibleAndAConfirmedStopKeepsTheFinalResult() throws Exception {
        CommandHandler handle = start("progress", 5, 128);
        try {
            waitUntil(() -> Files.exists(directory.resolve("requests/ready")));
            Files.writeString(directory.resolve("requests/fail-cancel"), "");
            assertThrows(IOException.class, handle::stop);
            assertFalse(handle.isDone());
            Files.delete(directory.resolve("requests/fail-cancel"));
            CommandHandler.StopResult stopped = handle.stop();
            assertTrue(stopped.stopped());
            assertSame(stopped.result(), handle.await());
            assertEquals(143, stopped.result().exitCode());
        } finally { handle.stop(); }
    }

    @Test void malformedOutputAutomaticallyRequestsTokenCleanup() throws Exception {
        CommandHandler handle = start("bad", 5, 128);
        waitUntil(handle::isDone);
        assertThrows(IOException.class, handle::await);
        assertTrue(Files.exists(directory.resolve("requests/cancel-attempt")));
    }

    private CommandHandler start(String command, long seconds, long maxChars) throws Exception {
        return commands.startCommand(command, null, StandardCharsets.UTF_8, seconds, TimeUnit.SECONDS, maxChars);
    }
    private void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Background command did not reach the expected state");
    }
}