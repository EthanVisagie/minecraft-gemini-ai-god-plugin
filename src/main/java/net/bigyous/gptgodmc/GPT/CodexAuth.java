package net.bigyous.gptgodmc.GPT;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.HttpPost;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import net.bigyous.gptgodmc.GPTGOD;

public final class CodexAuth {
    private static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final String TOKEN_URL = "https://auth.openai.com/oauth/token";
    private static final long REFRESH_SKEW_SECONDS = 300;
    private static final Gson gson = new Gson();
    private static final HttpClient client = HttpClient.newHttpClient();

    private CodexAuth() {
    }

    public static boolean applyBearerAuth(HttpPost request) {
        Optional<CodexTokens> tokens = getValidTokens();
        if (tokens.isEmpty()) {
            return false;
        }

        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.get().accessToken());
        tokens.get().accountId().ifPresent(accountId -> request.setHeader("ChatGPT-Account-Id", accountId));
        request.setHeader("originator", "gptgodmc");
        request.setHeader("User-Agent", "gptgodmc/" + JavaPlugin.getPlugin(GPTGOD.class).getDescription().getVersion());
        return true;
    }

    public static String missingAuthMessage() {
        return "Codex OAuth auth is not available. Run codex login, or set codex-auth-file to a valid Codex auth.json path.";
    }

    private static Optional<CodexTokens> getValidTokens() {
        Path authPath = getAuthPath();
        Optional<JsonObject> authJson = readAuthJson(authPath);
        if (authJson.isEmpty()) {
            return Optional.empty();
        }

        JsonObject tokens = getObject(authJson.get(), "tokens");
        if (tokens == null) {
            return Optional.empty();
        }

        String accessToken = getString(tokens, "access_token");
        String refreshToken = getString(tokens, "refresh_token");
        String accountId = getString(tokens, "account_id");

        if (accessToken != null && !isExpired(accessToken)) {
            return Optional.of(new CodexTokens(accessToken, Optional.ofNullable(accountId)));
        }

        if (refreshToken == null || refreshToken.isBlank()) {
            return Optional.empty();
        }

        Optional<JsonObject> refreshed = refreshTokens(refreshToken);
        if (refreshed.isEmpty()) {
            return accessToken == null ? Optional.empty()
                    : Optional.of(new CodexTokens(accessToken, Optional.ofNullable(accountId)));
        }

        String refreshedAccess = getString(refreshed.get(), "access_token");
        if (refreshedAccess == null || refreshedAccess.isBlank()) {
            return Optional.empty();
        }

        String refreshedAccountId = extractAccountId(refreshed.get()).orElse(accountId);
        tokens.addProperty("access_token", refreshedAccess);

        String refreshedRefresh = getString(refreshed.get(), "refresh_token");
        if (refreshedRefresh != null && !refreshedRefresh.isBlank()) {
            tokens.addProperty("refresh_token", refreshedRefresh);
        }
        if (refreshedAccountId != null && !refreshedAccountId.isBlank()) {
            tokens.addProperty("account_id", refreshedAccountId);
        }
        String refreshedIdToken = getString(refreshed.get(), "id_token");
        if (refreshedIdToken != null && !refreshedIdToken.isBlank()) {
            tokens.addProperty("id_token", refreshedIdToken);
        }

        writeAuthJson(authPath, authJson.get());
        return Optional.of(new CodexTokens(refreshedAccess, Optional.ofNullable(refreshedAccountId)));
    }

    private static Path getAuthPath() {
        FileConfiguration config = JavaPlugin.getPlugin(GPTGOD.class).getConfig();
        String configuredPath = config.getString("codex-auth-file");
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath);
        }
        return Path.of(System.getProperty("user.home"), ".codex", "auth.json");
    }

    private static Optional<JsonObject> readAuthJson(Path path) {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JsonParser.parseString(Files.readString(path)).getAsJsonObject());
        } catch (IOException | JsonSyntaxException | IllegalStateException e) {
            GPTGOD.LOGGER.warn("Could not read Codex auth file at " + path);
            return Optional.empty();
        }
    }

    private static void writeAuthJson(Path path, JsonObject authJson) {
        try {
            Files.writeString(path, gson.toJson(authJson), StandardCharsets.UTF_8);
        } catch (IOException e) {
            GPTGOD.LOGGER.warn("Could not update Codex auth file at " + path);
        }
    }

    private static Optional<JsonObject> refreshTokens(String refreshToken) {
        String formBody = formParam("grant_type", "refresh_token") + "&"
                + formParam("refresh_token", refreshToken) + "&"
                + formParam("client_id", CLIENT_ID);

        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                GPTGOD.LOGGER.warn("Codex OAuth token refresh failed with status " + response.statusCode());
                return Optional.empty();
            }
            return Optional.of(JsonParser.parseString(response.body()).getAsJsonObject());
        } catch (IOException | InterruptedException | JsonSyntaxException | IllegalStateException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            GPTGOD.LOGGER.warn("Codex OAuth token refresh failed");
            return Optional.empty();
        }
    }

    private static String formParam(String key, String value) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean isExpired(String jwt) {
        Optional<JsonObject> claims = parseJwt(jwt);
        if (claims.isEmpty()) {
            return false;
        }

        String expValue = getString(claims.get(), "exp");
        if (expValue == null) {
            return false;
        }

        try {
            long expiresAt = Long.parseLong(expValue);
            return expiresAt <= Instant.now().getEpochSecond() + REFRESH_SKEW_SECONDS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Optional<String> extractAccountId(JsonObject tokenResponse) {
        String accountId = getString(tokenResponse, "account_id");
        if (accountId != null && !accountId.isBlank()) {
            return Optional.of(accountId);
        }

        String idToken = getString(tokenResponse, "id_token");
        if (idToken != null) {
            Optional<String> idClaimAccount = extractAccountIdFromJwt(idToken);
            if (idClaimAccount.isPresent()) {
                return idClaimAccount;
            }
        }

        String accessToken = getString(tokenResponse, "access_token");
        if (accessToken != null) {
            return extractAccountIdFromJwt(accessToken);
        }

        return Optional.empty();
    }

    private static Optional<String> extractAccountIdFromJwt(String jwt) {
        Optional<JsonObject> claims = parseJwt(jwt);
        if (claims.isEmpty()) {
            return Optional.empty();
        }

        JsonObject auth = getObject(claims.get(), "https://api.openai.com/auth");
        if (auth != null) {
            String accountId = getString(auth, "chatgpt_account_id");
            if (accountId != null && !accountId.isBlank()) {
                return Optional.of(accountId);
            }
        }

        String accountId = getString(claims.get(), "chatgpt_account_id");
        return accountId == null || accountId.isBlank() ? Optional.empty() : Optional.of(accountId);
    }

    private static Optional<JsonObject> parseJwt(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }

        try {
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            return Optional.of(JsonParser.parseString(payload).getAsJsonObject());
        } catch (IllegalArgumentException | JsonSyntaxException | IllegalStateException e) {
            return Optional.empty();
        }
    }

    private static JsonObject getObject(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonObject() ? object.get(name).getAsJsonObject() : null;
    }

    private static String getString(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : null;
    }

    private record CodexTokens(String accessToken, Optional<String> accountId) {
    }
}
