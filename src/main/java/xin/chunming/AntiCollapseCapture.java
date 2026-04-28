package xin.chunming;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.options.LoadState;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import xin.chunming.cookie;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class AntiCollapseCapture {
    static AtomicInteger ai = new AtomicInteger(0);
    static final String TARGET_URL = "https://read.wqyunpan.com/qrcode/89152?fileId=199087&codeId=172624&type=kz";
    static final String CAPTURE_HOST = "x1.ow365.cn"; // 替换为目标 host
    static String TOKEN; // 替换为目标 host
    static ObjectMapper om = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        List<String> capturedUrls = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();

        String stateJson = new String(Files.readAllBytes(Paths.get("state.json")));
        JsonNode root = mapper.readTree(stateJson);

        // ── 1. 从 localStorage 取 yp-token ──
        String userToken = "";
        JsonNode origins = root.get("origins");
        if (origins != null) {
            for (JsonNode origin : origins) {
                if ("https://read.wqyunpan.com".equals(origin.get("origin").asText())) {
                    for (JsonNode item : origin.get("localStorage")) {
                        if ("userToken".equals(item.get("name").asText())) {
                            userToken = item.get("value").asText();
                        }
                    }
                }
            }
        }
        System.out.println("[token] yp-token = " + userToken);
        TOKEN = userToken;

        // ── 2. 构建 cookies ──
        List<Cookie> cookies = new ArrayList<>();
        for (JsonNode c : root.get("cookies")) {
            Cookie cookie1 = new Cookie(c.get("name").asText(), c.get("value").asText());
            if (c.has("domain")) cookie1.setDomain(c.get("domain").asText());
            if (c.has("path")) cookie1.setPath(c.get("path").asText());
            if (c.has("secure")) cookie1.setSecure(c.get("secure").asBoolean());
            if (c.has("httpOnly")) cookie1.setHttpOnly(c.get("httpOnly").asBoolean());
            // expires=-1 表示 session cookie，跳过
            if (c.has("expires") && c.get("expires").asDouble() > 0) {
                cookie1.setExpires(c.get("expires").asDouble());
            }
            cookies.add(cookie1);
        }

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(false));

            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    // 注入全局请求头，所有请求都带 yp-token
                    .setExtraHTTPHeaders(Map.of("yp-token", userToken)));

            context.addCookies(cookies);

            // ── 3. 注入 localStorage（防止页面 JS 读不到登录态） ──
            final String finalUserToken = userToken;
            context.addInitScript("""
                        Object.defineProperty(window, '__injected__', { value: true });
                    """);
            // 用 addInitScript 在页面加载前写入 localStorage
            String lsScript = String.format("""
                        window.localStorage.setItem('userToken', '%s');
                        window.localStorage.setItem('userTokenName', 'yp-token');
                    """, finalUserToken);
            context.addInitScript(lsScript);

            Page page = context.newPage();

            // ── 4. 监听网络请求，捕获目标 host 的完整 URL ──
            // 存 api 响应的 map
            Map<String, String> apiResponses = new ConcurrentHashMap<>();

// 监听响应 - 专门处理 api.wqyunpan.com
            page.onResponse(response -> {
                String url = response.url();
                if (url.contains("api.wqyunpan.com")) {
                    try {
                        String contentType = response.headerValue("content-type");
                        if (contentType != null) {
                            String body = response.text();
                            apiResponses.put(url, body);
                            System.out.println("[API RESP] " + url);

                            JsonNode jsonNode = om.readTree(body);
                            JsonNode jsonNode1 = jsonNode.get("data").get("url");
                            if (jsonNode1 != null) {
                                System.out.println(" OK!");
                                String urlfile = jsonNode1.asText();
                                String name = "";
                                boolean ispdf= false;//ispdf应为isfile 控制是否带token 下载文件需token 视频不用
                                if (urlfile.contains(".mp4")) {
                                    name = "mp4";
                                }
                                if (urlfile.contains(".pdf")) {
                                    name = ".pdf";
                                    ispdf=true;
                                    urlfile=urlfile.split("url=")[1];
                                }
                                if (urlfile.contains(".doc")) {
                                    name = ".doc";
                                    ispdf=true;
                                    urlfile=urlfile.split("url=")[1];


                                }
                                if (urlfile.contains(".ppt")) {
                                    name = ".ppt";
                                    ispdf=true;
                                    urlfile=urlfile.split("url=")[1];


                                }
                                if (urlfile.contains(".xls")) {
                                    name = ".xls";
                                    ispdf=true;
                                    urlfile=urlfile.split("url=")[1];


                                }if (urlfile.contains(".rar")) {
                                    name = ".rar";
                                    ispdf=true;
                                    urlfile=urlfile.split("url=")[1];


                                }if (urlfile.contains(".zip")) {
                                    name = ".zip";
                                    ispdf=true;
                                    urlfile=urlfile.split("url=")[1];


                                }
                                downloadVideo(urlfile, new File("output" + File.separator + ai + "_" + UUID.randomUUID() + name), ispdf);
                                ai.incrementAndGet();
                            }

//                            System.out.println(body);

                        }
                    } catch (Exception e) {
                        System.out.println("[warn] 读取 api 响应失败: " + e.getMessage());
                    }
                }
            });

// 原来的 onRequest 保持不动，只去掉 api 分支的处理
            page.onRequest(request -> {
                String url = request.url();
                if (url.contains("api.wqyunpan.com")) {
                    // 不在这里处理，响应由 onResponse 拿
                    System.out.println("[API REQ] " + url);
                    return;
                }
//                if (url.contains("x1.ow365.cn/pdfv/f?f")) {
//                    System.out.println("[CAPTURED] " + url);
//                    try {
//                        downloadVideo(url, new File("output" + File.separator + ai + "_" + UUID.randomUUID()+".pdf"), true);
//                    } catch (NoSuchAlgorithmException e) {
//                        throw new RuntimeException(e);
//                    } catch (KeyManagementException e) {
//                        throw new RuntimeException(e);
//                    }
//                    ai.incrementAndGet();
//                }
//                if (url.contains("vod")) {
//                    System.out.println("[CAPTURED] " + url);
//                    downloadVideo(url, new File("output" + File.separator + ai + "_" + UUID.randomUUID()), false);
//                    ai.incrementAndGet();
//                }
            });


            page.navigate(TARGET_URL);
            Thread.sleep(5000);
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);

            // ── 5. 外层循环：anti-collapse-item ──
            // 外层查 ant-collapse-item
            List<ElementHandle> outerItems = page.querySelectorAll(".ant-collapse-item");

            for (int i = 0; i < outerItems.size(); i++) {
//                Thread.sleep(50000);
                List<ElementHandle> outerRefreshed = page.querySelectorAll(".ant-collapse-item");
                if (i >= outerRefreshed.size()) break;
                ElementHandle outerItem = outerRefreshed.get(i);

                // 点击的是 item 里的 header
                ElementHandle header = outerItem.querySelector(".ant-collapse-header");
                if (header == null) continue;
                header.scrollIntoViewIfNeeded();
                header.click();

                // 在 item 上等 listCon，结构上是子孙，能找到
                try {
                    outerItem.waitForSelector(".listCon",
                            new ElementHandle.WaitForSelectorOptions().setTimeout(3000));
                } catch (TimeoutError e) {
                    System.out.println("  [warn] 内层未出现，跳过");
                    continue;
                }

                List<ElementHandle> innerItems = outerItem.querySelectorAll(".listCon");
                System.out.println("  内层 listCon 数: " + innerItems.size());

                for (int j = 0; j < innerItems.size(); j++) {
                    // 重新定位防止 handle 失效
                    List<ElementHandle> outerR2 = page.querySelectorAll(".ant-collapse-item");
                    if (i >= outerR2.size()) break;
                    List<ElementHandle> innerR2 = outerR2.get(i).querySelectorAll(".listCon");
                    if (j >= innerR2.size()) break;

                    innerR2.get(j).scrollIntoViewIfNeeded();
                    innerR2.get(j).click();
                    page.waitForTimeout(1200);
                }
            }


            // ── 7. 输出结果 ──
//            System.out.println("\n========== 捕获到的 URL ==========");
//            capturedUrls.forEach(System.out::println);

//            Files.write(Paths.get("captured_urls.txt"),
//                    String.join("\n", capturedUrls).getBytes());
//            System.out.println("\n已保存到 captured_urls.txt，共 " + capturedUrls.size() + " 条");
            page.close();
            browser.close();
            System.exit(0);
        }
    }


    public static void downloadVideo(String url, File saveFile, boolean isPDF) throws NoSuchAlgorithmException, KeyManagementException {

        // 1. 创建信任所有证书的 TrustManager
        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[]{};
                    }
                }
        };

// 2. 初始化 SSLContext
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

// 3. 构建 OkHttpClient
        OkHttpClient client = new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
                .hostnameVerifier((hostname, session) -> true) // 总是返回 true 表示接受所有域名
                .build();


        System.out.println("下载" + url);
        //  https://x1.ow365.cn/print/web/print.html?file=KHlwZmlsZS53cWtldGFuZy5jb20uODBcNjcxMGNlNzk2YjRmMjEyN2EzMDYxOTA1LnBkZg--
//        OkHttpClient client = new OkHttpClient();
        Request.Builder requestprep = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36")
                .header("Sec-Fetch-Site", "same-site")
                .header("sec-fetch-dest", "video")
                .header("sec-ch-ua", "Google Chrome\";v=\"147\", \"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"147")
                .header("sec-ch-ua-platform", "macOS")
                .header("Sec-Fetch-Mode", "cors")
                .header("yp-token", TOKEN)
                .header("Connection", "keep-alive");
        Request request = null;
        if (isPDF) {
            requestprep.header("Referer", isPDF ? "https://x1.ow365.cn/print/web/print.html" : "https://wqyunpan.com/");
            request = requestprep.build();

        } else request = requestprep.build();
/*sec-ch-ua
"Google Chrome";v="147", "Not.A/Brand";v="8", "Chromium";v="147"
sec-ch-ua-mobile
?0
sec-ch-ua-platform
"macOS"
sec-fetch-dest
video
sec-fetch-mode
no-cors
sec-fetch-site
same-site*/
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

                // 使用 try-with-resources 自动关闭流
                try (InputStream is = response.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(saveFile)) {

                    byte[] buffer = new byte[8192]; // 8KB 缓冲区
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                    fos.flush();
                    fos.close();
                    System.out.println("下载完成！");
                }
            }
        });
    }
}
