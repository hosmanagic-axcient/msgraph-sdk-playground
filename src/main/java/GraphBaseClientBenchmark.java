import com.google.common.base.Stopwatch;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.authentication.IAuthenticationProvider;
import com.microsoft.graph.authentication.TokenCredentialAuthProvider;
import com.microsoft.graph.core.BaseClient;
import com.microsoft.graph.core.IBaseClient;
import com.microsoft.graph.httpcore.AuthenticationHandler;
import com.microsoft.graph.httpcore.HttpClients;
import com.microsoft.graph.httpcore.RedirectHandler;
import com.microsoft.graph.httpcore.RetryHandler;
import com.microsoft.graph.logger.DefaultLogger;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

import static java.lang.Runtime.getRuntime;
import static java.lang.String.format;

public class GraphBaseClientBenchmark {
    private IBaseClient underTest;
    private String userId;
    private String tenantId;
    private String clientId;
    private String clientSecret;
    private String rootFolder = "inbox";

    public static void main(String[] args) {
        new GraphBaseClientBenchmark().run();
    }

    private void run() {
        setUp();
        test();
    }

    public void setUp() {
        // -ea -DGraphBaseClientBenchmark.userId=test@example.com -DGraphBaseClientBenchmark.clientId=123 -DGraphBaseClientBenchmark.clientSecret=verysecret
        userId = System.getProperty("GraphBaseClientBenchmark.userId");
        tenantId = userId.substring(userId.indexOf('@') + 1);
        clientId = System.getProperty("GraphBaseClientBenchmark.clientId");
        clientSecret = System.getProperty("GraphBaseClientBenchmark.clientSecret");

        final IAuthenticationProvider authProvider = getAuthProvider();
        final OkHttpClient httpClient = HttpClients.custom()
                .addInterceptor(new AuthenticationHandler(authProvider))
                .addInterceptor(new RetryHandler())
                .addInterceptor(new RedirectHandler())
                .connectTimeout(1, TimeUnit.MINUTES)
                .readTimeout(1, TimeUnit.MINUTES)
                .build();
        underTest = BaseClient.builder()
                .logger(new DefaultLogger())
                .authenticationProvider(authProvider)
                .httpClient(httpClient)
                .buildClient();
    }

    private IAuthenticationProvider getAuthProvider() {
        return new TokenCredentialAuthProvider(
                new ClientSecretCredentialBuilder()
                        .tenantId(tenantId)
                        .clientId(clientId)
                        .clientSecret(clientSecret)
                        .build()
        );
    }

    public void test() {
        Stopwatch stopwatch = Stopwatch.createStarted();

        visit(rootFolder);

        System.out.printf("memory usage %d\n", getRuntime().totalMemory() - getRuntime().freeMemory());
        System.out.printf("time: %s\n", stopwatch.elapsed().toString());
        System.out.println("done.");
    }

    private void visit(String folderId) {
        visitAllMessages(folderId);

        final JsonObject childFoldersJson = listChildFolders(folderId);
        final JsonArray childFoldersArray = (JsonArray) childFoldersJson.get("value");
        for (JsonElement childFolder : childFoldersArray) {
            System.out.println("working on folder " + childFolder.getAsJsonObject().get("displayName").getAsString());
            String childFolderId = childFolder.getAsJsonObject().get("id").getAsString();
            visit(childFolderId);
        }
    }

    @Nullable
    private JsonObject listChildFolders(String folderId) {
        final String listChildFolders = format("/users/%s/mailFolders/%s/childFolders", userId, folderId);
        final JsonObject childFoldersJson = underTest.customRequest(listChildFolders, JsonObject.class)
                .buildRequest()
                .get();
        return childFoldersJson;
    }

    private void visitAllMessages(String folderId) {
        String listMessagesUrl = String.format("/users/%s/mailFolders/%s/messages/delta?$select=id", userId, folderId);
        JsonObject response = underTest.customRequest(listMessagesUrl).buildRequest().get().getAsJsonObject();
        loadMessages(response.getAsJsonArray("value"));
        while (response.has("@odata.nextLink")) {
            final String nextLink = response.get("@odata.nextLink").getAsString().replace(underTest.getServiceRoot(), "");
            response = underTest.customRequest(nextLink).buildRequest().get().getAsJsonObject();
            loadMessages(response.getAsJsonArray("value"));
        }
        // System.out.println("done with " + folderId);
    }

    private void processMessages(JsonArray messages) {

    }

    private void loadMessages(JsonArray messages) {
        for (JsonElement message : messages) {
            final String messageId = message.getAsJsonObject().get("id").getAsString();
            String listMessagesUrl = String.format("/users/%s/messages/%s?$expand=attachments", userId, messageId);
            final JsonElement response = underTest.customRequest(listMessagesUrl).buildRequest().get();
            System.out.println("loaded message " + messageId);
        }
    }
}
