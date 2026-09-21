package io.github.jevkit;

import io.github.jevkit.model.NoulQuestion;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JevClientCancellationTest {
    @Test
    void cancellingResponseFutureClosesAnInFlightHttpTransfer() throws Exception {
        var headersSent = new CountDownLatch(1);
        var peer = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            var disconnected = peer.submit(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    int length = 0;
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                            length = Integer.parseInt(line.substring(15).trim());
                        }
                    }
                    for (int i = 0; i < length; i++) {
                        if (reader.read() == -1) {
                            throw new AssertionError("Incomplete request");
                        }
                    }
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                            + "Content-Length: 100\r\n\r\n{").getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                    headersSent.countDown();
                    return reader.read();
                }
            });
            try (JevClient client = JevClient.builder().apiKey("test-key")
                    .baseUrl("http://127.0.0.1:" + server.getLocalPort())
                    .timeout(Duration.ofSeconds(10)).retryPolicy(RetryPolicy.none()).build()) {
                QuestionSet.Builder request = QuestionSet.builder("test");
                request.add("q", new NoulQuestion("Is this a test?"));
                var response = client.evaluateAsync(request.build());
                assertTrue(headersSent.await(3, TimeUnit.SECONDS), "server did not start its response");
                assertTrue(response.cancel(true));
                assertEquals(-1, disconnected.get(5, TimeUnit.SECONDS), "cancel must close the underlying transfer");
            }
        } finally {
            peer.shutdownNow();
        }
    }
}
