package itkach.slobber;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
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
    private Slob emptySlob;
    private FileChannel slobChannel;
    private FileChannel unicodeSlobChannel;
    private FileChannel emptySlobChannel;
    private File staticRoot;

    @BeforeClass
    public void start() throws Exception {
        staticRoot = new File(System.getProperty("java.io.tmpdir"), "slobber-static-" + System.nanoTime());
        assertTrue(staticRoot.mkdir());
        writeStaticFile("hello.css", "body { color: #123456; }".getBytes("UTF-8"));
        writeStaticFile("image.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes("UTF-8"));
        writeStaticFile("font.woff2", new byte[] {0, 1, 2});
        writeStaticFile("empty.css", new byte[0]);
        writeStaticFile("large.css", new byte[70 * 1024]);
        System.setProperty("slobber.static.static", staticRoot.getPath());
        URL resource = getClass().getClassLoader().getResource("fixture.slob");
        slobChannel = new RandomAccessFile(new File(resource.toURI()), "r").getChannel();
        slob = new Slob(slobChannel, "fixture");
        URL unicodeResource = getClass().getClassLoader().getResource("unicode.slob");
        unicodeSlobChannel = new RandomAccessFile(new File(unicodeResource.toURI()), "r").getChannel();
        unicodeSlob = new Slob(unicodeSlobChannel, "unicode");
        File empty = new File("../slobj/src/test/resources/fixtures/empty.slob");
        emptySlobChannel = new RandomAccessFile(empty, "r").getChannel();
        emptySlob = new Slob(emptySlobChannel, "empty");
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
        if (emptySlobChannel != null) emptySlobChannel.close();
        System.clearProperty("slobber.static.static");
        delete(staticRoot);
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

        HttpURLConnection evil = requestWithOrigin("/slob", "https://evil.example");
        assertEquals(200, evil.getResponseCode());
        assertEquals(null, evil.getHeaderField("Access-Control-Allow-Origin"));
        System.setProperty("slobber.cors.origins", "https://trusted.example");
        try {
            HttpURLConnection trusted = requestWithOrigin("/slob", "https://trusted.example");
            assertEquals(200, trusted.getResponseCode());
            assertEquals("https://trusted.example", trusted.getHeaderField("Access-Control-Allow-Origin"));
            assertEquals("Origin", trusted.getHeaderField("Vary"));
        } finally {
            System.clearProperty("slobber.cors.origins");
        }
    }

    @Test
    public void rejectsUnsupportedMethodAndDoesNotExposeStackTrace() throws Exception {
        HttpURLConnection request = request("/slob");
        request.setRequestMethod("POST");
        assertEquals(405, request.getResponseCode());
        assertFalse(read(request).contains("Exception"));
    }

    @Test
    public void malformedBlobIdsAreBadRequestsAndMissingBlobsAreNotFound() throws Exception {
        HttpURLConnection request = request("/slob/" + slob.getId() + "/example?blob=not-a-blob-id");
        assertEquals(400, request.getResponseCode());
        assertEquals("Bad Request", read(request));

        HttpURLConnection missing = request("/slob/" + slob.getId() + "/example?blob=9999-9999");
        assertEquals(404, missing.getResponseCode());
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

        HttpURLConnection malformedEncoding = request("/slob/" + id + "/%zz");
        assertTrue(malformedEncoding.getResponseCode() == 400 || malformedEncoding.getResponseCode() == 404);

    }

    @Test
    public void servesStaticMimeAndRejectsTraversal() throws Exception {
        HttpURLConnection css = request("/static/hello.css");
        assertEquals(200, css.getResponseCode());
        assertTrue(css.getContentType().startsWith("text/css"));
        assertTrue(read(css).contains("#123456"));

        HttpURLConnection svg = request("/static/image.svg");
        assertEquals(200, svg.getResponseCode());
        assertTrue(svg.getContentType().startsWith("image/svg+xml"));

        HttpURLConnection woff2 = request("/static/font.woff2");
        assertEquals(200, woff2.getResponseCode());
        assertTrue(woff2.getContentType().startsWith("font/woff2"));

        HttpURLConnection empty = request("/static/empty.css");
        assertEquals(200, empty.getResponseCode());
        assertEquals("", read(empty));

        HttpURLConnection large = request("/static/large.css");
        assertEquals(200, large.getResponseCode());
        assertEquals(70 * 1024, readBytes(large));

        assertEquals(404, request("/static/unknown.txt").getResponseCode());

        String[] traversal = {"/static/../outside-static-root.txt", "/static/%2e%2e/outside-static-root.txt",
                "/static/..%2foutside-static-root.txt", "/static/%2e%2e%2foutside-static-root.txt"};
        for (String path : traversal) {
            HttpURLConnection request = request(path);
            assertTrue(path, request.getResponseCode() == 400 || request.getResponseCode() == 404);
        }
    }

    @Test
    public void blocksClasspathLeaksAndValidatesFindLimits() throws Exception {
        assertEquals(404, request("/META-INF/MANIFEST.MF").getResponseCode());
        assertEquals(404, request("/itkach/slobber/Slobber.class").getResponseCode());

        assertEquals(200, request("/find?key=example&limit=1").getResponseCode());
        assertEquals(400, request("/find?key=example&limit=0").getResponseCode());
        assertEquals(400, request("/find?key=example&limit=-1").getResponseCode());
        assertEquals(400, request("/find?key=example&limit=abc").getResponseCode());
        assertEquals(400, request("/find?key=example&limit=10001").getResponseCode());
    }

    @Test
    public void randomSkipsEmptySlobs() throws Exception {
        try {
            slobber.setSlobs(Arrays.asList(emptySlob, slob));
            assertEquals(200, request("/random").getResponseCode());
            slobber.setSlobs(Arrays.asList(emptySlob));
            assertEquals(404, request("/random").getResponseCode());
        } finally {
            slobber.setSlobs(Arrays.asList(slob, unicodeSlob));
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

    private int readBytes(HttpURLConnection connection) throws Exception {
        int total = 0;
        byte[] buffer = new byte[4096];
        java.io.InputStream input = connection.getInputStream();
        int count;
        while ((count = input.read(buffer)) != -1) total += count;
        input.close();
        return total;
    }

    private void writeStaticFile(String name, byte[] content) throws Exception {
        FileOutputStream output = new FileOutputStream(new File(staticRoot, name));
        try {
            output.write(content);
        } finally {
            output.close();
        }
    }

    private HttpURLConnection requestWithOrigin(String path, String origin) throws Exception {
        HttpURLConnection connection = request(path);
        connection.setRequestProperty("Origin", origin);
        return connection;
    }

    private void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) delete(child);
        }
        file.delete();
    }
}
