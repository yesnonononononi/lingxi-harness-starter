package com.summit.adapter.langchain4j.model;

import com.summit.core.conversation.api.ChatRequestEntity;
import com.summit.core.conversation.api.ChatResponseEntity;
import com.summit.core.conversation.message.UserMessageEntity;
import com.summit.core.model.streaming.StreamingChatResponseHandler;
import com.summit.core.model.streaming.StreamingHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 强制断开必须落到真实连接上。
 *
 * <p>单元测试只能证明「我们请求了关闭」；这里用一个本机 SSE 服务器当独立证人：中途取消后，
 * 服务器必须观察到客户端把 TCP 连接关掉（读取返回 -1），而不是继续把 token 写进一个没人读的管道。</p>
 *
 * <p>放在 adapter 模块，是因为要证明的那一跳（{@code StreamingHandler.cancel()} →
 * langchain4j {@code StreamingHandle.cancel()} → 关闭响应体流）完全属于这个模块；
 * 「何时取消」的决策在 runtime 侧，由 {@code StreamingModelResponseBehaveDeciderTest} 覆盖。</p>
 */
class StreamingTransportForcedCloseTest {

    private static final long AWAIT_SECONDS = 15;

    @Test
    void cancellingMidStreamClosesTheUnderlyingConnection() throws Exception {
        try (SseServer server = new SseServer()) {
            OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                    .baseUrl("http://localhost:" + server.port() + "/v1")
                    .apiKey("test-key")
                    .modelName("test-model")
                    .timeout(Duration.ofSeconds(30))
                    .build();
            StreamingChatModelAdapter adapter = new StreamingChatModelAdapter(model);

            CountDownLatch firstDelta = new CountDownLatch(1);
            AtomicInteger observedDeltas = new AtomicInteger();

            adapter.chat(ChatRequestEntity.builder()
                            .messages(List.of(UserMessageEntity.from("hi"))).build(),
                    new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String text, StreamingHandler handle) {
                            observedDeltas.incrementAndGet();
                            if (firstDelta.getCount() > 0) {
                                firstDelta.countDown();
                                handle.cancel();
                            }
                        }

                        @Override
                        public void onPartialThinking(String thinking, StreamingHandler handle) {
                        }

                        @Override
                        public void onFinalResponse(ChatResponseEntity response) {
                        }

                        @Override
                        public void onError(Throwable err) {
                        }
                    });

            assertTrue(firstDelta.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "服务器应至少送出一个 delta，测试才有意义");
            assertTrue(server.clientClosed().await(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "取消后客户端必须关闭底层 TCP 连接，而不是继续排空");
        }
    }

    /**
     * 上一条用例的反向对照：不取消时连接必须一直开着。
     *
     * <p>没有这条，上一条的「连接已关闭」就无法归因——一个永远返回 -1 的探针也能让它变绿。</p>
     */
    @Test
    void streamKeepsTheConnectionOpenWhenNotCancelled() throws Exception {
        try (SseServer server = new SseServer()) {
            OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                    .baseUrl("http://localhost:" + server.port() + "/v1")
                    .apiKey("test-key")
                    .modelName("test-model")
                    .timeout(Duration.ofSeconds(30))
                    .build();
            StreamingChatModelAdapter adapter = new StreamingChatModelAdapter(model);

            CountDownLatch severalDeltas = new CountDownLatch(5);

            adapter.chat(ChatRequestEntity.builder()
                            .messages(List.of(UserMessageEntity.from("hi"))).build(),
                    new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String text, StreamingHandler handle) {
                            severalDeltas.countDown();
                        }

                        @Override
                        public void onPartialThinking(String thinking, StreamingHandler handle) {
                        }

                        @Override
                        public void onFinalResponse(ChatResponseEntity response) {
                        }

                        @Override
                        public void onError(Throwable err) {
                        }
                    });

            assertTrue(severalDeltas.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "未取消时应持续收到 delta，否则对照没有意义");
            assertFalse(server.clientClosed().await(2, TimeUnit.SECONDS),
                    "未取消时连接必须保持打开");
        }
    }

    /**
     * 一个最小可用的 SSE 服务器：只认「请求头 + 无限 chunked 事件流」，并记录客户端是否关掉了连接。
     */
    private static final class SseServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final ExecutorService pool = Executors.newCachedThreadPool();
        private final CountDownLatch clientClosed = new CountDownLatch(1);
        private final AtomicInteger chunksSent = new AtomicInteger();

        SseServer() throws IOException {
            this.serverSocket = new ServerSocket(0);
            pool.submit(this::serve);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        CountDownLatch clientClosed() {
            return clientClosed;
        }

        int chunksSent() {
            return chunksSent.get();
        }

        private void serve() {
            try (Socket socket = serverSocket.accept()) {
                socket.setTcpNoDelay(true);
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();

                drainRequestHead(in);

                out.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/event-stream\r\n"
                        + "Cache-Control: no-cache\r\n"
                        + "Transfer-Encoding: chunked\r\n"
                        + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();

                watchForClientClose(in);

                // 事件流刻意不结束：只有客户端关连接才能让它停下来。
                for (int i = 0; i < 5_000 && clientClosed.getCount() > 0; i++) {
                    writeChunk(out, event("token-" + i));
                    chunksSent.incrementAndGet();
                    Thread.sleep(10);
                }
            } catch (Exception ignored) {
                // 客户端断开后写入失败是预期结果，不算测试失败
            }
        }

        /** 客户端在请求之后再无数据，读取阻塞直到对端 FIN；返回 -1 即连接已关。 */
        private void watchForClientClose(InputStream in) {
            Thread detector = new Thread(() -> {
                try {
                    while (in.read() != -1) {
                        // 只可能是请求体剩余字节，耗尽后阻塞等待 FIN
                    }
                } catch (IOException ignored) {
                    // 连接被 RST 同样表示已断开
                } finally {
                    clientClosed.countDown();
                }
            });
            detector.setDaemon(true);
            detector.start();
        }

        private static void drainRequestHead(InputStream in) throws IOException {
            int matched = 0;
            int read;
            while (matched < 4 && (read = in.read()) != -1) {
                matched = switch (matched) {
                    case 0 -> read == '\r' ? 1 : 0;
                    case 1, 3 -> read == '\n' ? matched + 1 : 0;
                    default -> read == '\r' ? 3 : 0;
                };
            }
        }

        private static void writeChunk(OutputStream out, String payload) throws IOException {
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        private static String event(String token) {
            return "data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":0,"
                    + "\"model\":\"test-model\",\"choices\":[{\"index\":0,"
                    + "\"delta\":{\"content\":\"" + token + "\"}}]}\n\n";
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            pool.shutdownNow();
        }
    }
}
