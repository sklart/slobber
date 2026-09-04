package itkach.slobber;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.util.Arrays;

import itkach.slob.Slob;

public class SlobberIntegrationTest {
    private Slobber slobber;
    private int port;
    private Slob slob;
    private Slob unicodeSlob;
    private FileChannel slobChannel;
    private FileChannel unicodeSlobChannel;

    @BeforeClass
    public void start() throws Exception {
        URL staticResource = getClass().getClassLoader().getResource("static/hello.css");
        System.setProperty("slobber.static.static", new File(staticResource.toURI()).getParent());
        URL resource = getClass().getClassLoader().getResource("fixture.slob");
        slobChannel = new RandomAccessFile(new File(resource.toURI()), "r").getChannel();
        slob = new Slob(slobChannel, "fixture");
        URL unicodeResource = getClass().getClassLoader().getResource("unicode.slob");
        unicodeSlobChannel = new RandomAccessFile(new File(unicodeResource.toURI()), "r").getChannel();
        unicodeSlob = new Slob(unicodeSlobChannel, "unicode");
        slobber = new Slobber();
        slobber.setSlobs(Arrays.asList(slob, unicodeSlob));
        ServerSocket socket = new ServerSocket(0);
        port = socket.getLocalPort();
        socket.close();
        slobber.start("127.0.0.1", port);
    }

    @AfterClass
    public void stop() throws Exception {
        if (slobber != null) slobber.stop();
        if (slobChannel != null) slobChannel.close();
        if (unicodeSlobChannel != null) unicodeSlobChannel.close();
        System.clearProperty("slobber.static.static");
    }

    @Test
    public void servesSlobAndRejectsMissingContentWithoutCorsReflection() throws Exception {
        HttpURLConnection info = request("/slob");
        assertEquals(200, info.getResponseCode());
        assertTrue(info.getContentType().startsWith("application/json"));
        assertTrue(read(info).contains("slobs"));
        assertEquals("nosniff", info.getHeaderField("X-Content-Type-Options"));
        assertEquals("no-referrer", info.getHeaderField("Referrer-Policy"));

        HttpURLConnection missing = request("/slob/not-a-uuid/missing");
        assertEquals(404, missing.getResponseCode());
        assertEquals(null, missing.getHeaderField("Access-Control-Allow-Origin"));

        assertTrue(Slobber.GETContainer.isTrustedOrigin("https://trusted.example",
                "https://other.example, https://trusted.example"));
        assertFalse(Slobber.GETContainer.isTrustedOrigin("https://untrusted.example",
                "https://trusted.example"));
    }

    @Test
    public void rejectsUnsupportedMethodAndDoesNotExposeStackTrace() throws Exception {
        HttpURLConnection request = request("/slob");
        request.setRequestMethod("POST");
        assertEquals(405, request.getResponseCode());
        assertFalse(read(request).contains("Exception"));
    }

    @Test
    public void internalErrorsDoNotExposeStackTraces() throws Exception {
        HttpURLConnection request = request("/slob/" + slob.getId() + "/example?blob=not-a-blob-id");
        assertEquals(500, request.getResponseCode());
        assertTrue(request.getContentType().startsWith("text/plain; charset=utf-8"));
        assertEquals("Internal Server Error", read(request));
    }

    @Test
    public void servesContentAndAllPublicEndpoints() throws Exception {
        String id = slob.getId().toString();
        HttpURLConnection root = request("/");
        assertEquals(404, root.getResponseCode());

        HttpURLConnection find = request("/find?key=example&limit=1");
        assertEquals(200, find.getResponseCode());
        assertTrue(find.getContentType().startsWith("application/json"));
        assertTrue(read(find).contains("example"));

        HttpURLConnection random = request("/random");
        assertEquals(200, random.getResponseCode());
        assertTrue(read(random).contains("\"url\""));

        HttpURLConnection info = request("/slob/" + id);
        assertEquals(200, info.getResponseCode());
        assertTrue(read(info).contains(id));

        HttpURLConnection article = request("/slob/" + id + "/" + URLEncoder.encode("example", "UTF-8"));
        assertEquals(200, article.getResponseCode());
        assertTrue(article.getContentType().startsWith("text/plain"));
        assertEquals("example", read(article));

        HttpURLConnection unicode = request("/slob/" + unicodeSlob.getId() + "/"
                + URLEncoder.encode(unicodeSlob.get(0).key, "UTF-8"));
        assertEquals(200, unicode.getResponseCode());
        assertTrue(read(unicode).length() > 0);

    }

    @Test
    public void servesStaticMimeAndRejectsTraversal() throws Exception {
        HttpURLConnection css = request("/static/hello.css");
        assertEquals(200, css.getResponseCode());
        assertTrue(css.getContentType().startsWith("text/css"));
        assertTrue(read(css).contains("#123456"));

        String[] traversal = {"/static/../outside-static-root.txt", "/static/%2e%2e/outside-static-root.txt",
                "/static/..%2foutside-static-root.txt", "/static/%2e%2e%2foutside-static-root.txt"};
        for (String path : traversal) {
            HttpURLConnection request = request(path);
            assertTrue(path, request.getResponseCode() == 400 || request.getResponseCode() == 404);
        }
    }

    private HttpURLConnection request(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        return connection;
    }

    private String read(HttpURLConnection connection) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream(), "UTF-8"));
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) result.append(line);
        reader.close();
        return result.toString();
    }
}
