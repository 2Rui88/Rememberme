package memory.longterm;

import java.net.http.HttpClient;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class ProviderHttp {
    private ProviderHttp() {}

    static RestClient client(RestClient.Builder template, String baseUrl, String authorization, Duration timeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(timeout);
        RestClient.Builder builder = template.clone().baseUrl(baseUrl.replaceAll("/+$", "")).requestFactory(factory);
        if (!authorization.isBlank()) {
            builder.defaultHeader("Authorization", authorization);
        }
        return builder.build();
    }

    static JsonNode post(RestClient client, String endpoint, Object parameters) {
        JsonNode body = client.post().uri(endpoint).body(parameters).retrieve().body(JsonNode.class);
        if (body == null || body.isNull()) {
            throw new IllegalStateException("Memory provider returned no body");
        }
        return body;
    }
}
