package io.github.jevkit;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A real local HTTP server that replies with queued responses and records every request it receives. */
final class StubServer implements AutoCloseable {

    record Reply(int status, String body, Map<String, String> headers, long delayMillis) {
    }

    record Recorded(String method, String path, Headers headers, String body) {
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final List<Recorded> recorded = new CopyOnWriteArrayList<>();

    StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            recorded.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders(), body));
            Reply reply = replies.poll();

            if (reply == null) {
                reply = new Reply(500, "{\"detail\":\"StubServer has no reply queued\"}", Map.of(), 0);
            }

            if (reply.delayMillis() > 0) {
                try {
                    Thread.sleep(reply.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));

            try {
                exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);

                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } catch (IOException e) {
                // The client gave up (e.g. a timeout test); nothing to do.
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    StubServer reply(int status, String body) {
        return reply(status, body, Map.of());
    }

    StubServer reply(int status, String body, Map<String, String> headers) {
        replies.add(new Reply(status, body, headers, 0));
        return this;
    }

    StubServer replyAfter(long delayMillis, int status, String body) {
        replies.add(new Reply(status, body, Map.of(), delayMillis));
        return this;
    }

    List<Recorded> recorded() {
        return new ArrayList<>(recorded);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
