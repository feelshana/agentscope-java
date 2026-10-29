import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Packaging fixture only; this is never included in an application release. */
public final class RuntimeFixture {
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(System.getenv("DATAAGENT_BASE_DIR"), "data", "workspace");
        Files.createDirectories(workspace);
        int cli = new ProcessBuilder("/usr/local/bin/wren", "--version").inheritIO().start().waitFor();
        if (cli != 0) throw new IllegalStateException("Wren subprocess failed");
        Files.writeString(workspace.resolve("fixture-ready"), "java17-wren-child-ok");
        int port = Integer.parseInt(System.getenv().getOrDefault("DATAAGENT_PORT", "18080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/actuator/health", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Files.writeString(workspace.resolve("fixture-terminated"), "TERM-received"); }
            catch (Exception e) { throw new RuntimeException(e); }
            server.stop(0);
        }));
        System.out.println("RuntimeFixture READY");
        new CountDownLatch(1).await();
    }
}
