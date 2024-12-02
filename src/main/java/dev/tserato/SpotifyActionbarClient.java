package dev.tserato;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding;

public class SpotifyActionbarClient implements ClientModInitializer {
    private static final String CLIENT_ID = "eb53f798591440df8274f876058ed6a0";
    private static final String CLIENT_SECRET = "7b1a0b0320fb4164a6121e4474f36e6b";
    private static final String REDIRECT_URI = "http://localhost:8080/callback";
    private static String accessToken = null;
    private static String refreshToken = null;
    private static String currentlyPlaying = "No song playing";
    private static boolean authorizationRequested = false; // Track if authorization has been requested
    private static boolean hudEnabled = true; // Toggle state of the HUD
    private HttpServer httpServer;
    private ScheduledExecutorService scheduler;
    private static final Identifier SPOTIFY_LOGO = Identifier.of("spotify-actionbar", "textures/spotify_logo.png");
    private static KeyBinding openGuiKeyBinding;
    private boolean guiOpen = false;

    @Override
    public void onInitializeClient() {
        System.out.println("[SpotifyActionbar] Mod initialized!");
        startHttpServer();

        // Register the /spotifyactionbar toggle command
        registerCommands();

        // Schedule periodic fetching of the currently playing song
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && accessToken != null) {
                fetchCurrentlyPlaying(client.player);
            }
        }, 0, 5, TimeUnit.SECONDS);

        // Register HUD rendering callback
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (hudEnabled) {
                renderCurrentlyPlayingInHUD(drawContext);
            }
        });

        openGuiKeyBinding = new KeyBinding("key.spotifyactionbar.open", GLFW.GLFW_KEY_O, "key.categories.misc");
        registerKeyBinding(openGuiKeyBinding);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (openGuiKeyBinding.isPressed()) {
                toggleGui();  // Toggle the GUI when the "O" key is pressed
            }
        });
    }

    private void toggleGui() {
        guiOpen = !guiOpen;
        if (guiOpen) {
            MinecraftClient.getInstance().setScreen(new SpotifyControlScreen());
        } else {
            MinecraftClient.getInstance().setScreen(null); // Close the screen
        }
    }

    public static String getAccessToken() {
        return accessToken;
    }


    private void startAuthorizationFlow() {
        String authorizationUrl = "https://accounts.spotify.com/authorize" +
                "?response_type=code" +
                "&client_id=" + CLIENT_ID +
                "&redirect_uri=" + REDIRECT_URI +
                "&scope=user-read-currently-playing%20user-modify-playback-state"; // Use %20 to separate scopes

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.execute(() -> {
                // Send a clickable chat message with the authorization link
                Text clickableText = Text.literal("Click here to authorize Spotify")
                        .styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, authorizationUrl))
                                .withColor(Formatting.GREEN)
                                .withUnderline(true));
                client.player.sendMessage(Text.literal("[SpotifyActionbar] ").append(clickableText), false);
            });
        }
    }



    private void registerCommands() {
        // Register the /spotifyactionbar toggle command
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated, environment) -> {
            dispatcher.register(CommandManager.literal("spotifyactionbar")
                    .then(CommandManager.literal("toggle")
                            .executes(context -> {
                                toggleHUD(context.getSource().getPlayer());
                                return 1;
                            }))
            );
        });
    }

    private void toggleHUD(PlayerEntity player) {
        hudEnabled = !hudEnabled;
        String message = hudEnabled
                ? "SpotifyActionbar HUD is now enabled."
                : "SpotifyActionbar HUD is now disabled.";
        if (player != null) {
            player.sendMessage(Text.literal("[SpotifyActionbar] " + message), false);
        }
    }

    public static void exchangeCodeForTokens(String code) {
        try {
            URL url = new URL("https://accounts.spotify.com/api/token");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            String credentials = CLIENT_ID + ":" + CLIENT_SECRET;
            String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
            connection.setRequestProperty("Authorization", "Basic " + encodedCredentials);

            String body = "grant_type=authorization_code&code=" + code + "&redirect_uri=" + REDIRECT_URI;
            OutputStream os = connection.getOutputStream();
            os.write(body.getBytes());
            os.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line);
            }
            in.close();

            JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
            accessToken = json.get("access_token").getAsString();
            refreshToken = json.get("refresh_token").getAsString();

            System.out.println("Access Token: " + accessToken);
            System.out.println("Refresh Token: " + refreshToken);
        } catch (Exception e) {
            System.err.println("Error exchanging code for tokens:");
            e.printStackTrace();
        }
    }

    public static void refreshAccessToken() {
        try {
            URL url = new URL("https://accounts.spotify.com/api/token");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            String credentials = CLIENT_ID + ":" + CLIENT_SECRET;
            String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
            connection.setRequestProperty("Authorization", "Basic " + encodedCredentials);

            String body = "grant_type=refresh_token&refresh_token=" + refreshToken;
            OutputStream os = connection.getOutputStream();
            os.write(body.getBytes());
            os.flush();

            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line);
            }
            in.close();

            JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
            accessToken = json.get("access_token").getAsString();

            System.out.println("Refreshed Access Token: " + accessToken);
        } catch (Exception e) {
            System.err.println("Error refreshing access token:");
            e.printStackTrace();
        }
    }

    public static void fetchCurrentlyPlaying(PlayerEntity player) {
        try {
            URL url = new URL("https://api.spotify.com/v1/me/player/currently-playing");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);

            if (connection.getResponseCode() == 401) {
                refreshAccessToken();
                fetchCurrentlyPlaying(player);
                return;
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line);
            }
            in.close();

            JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();
            JsonObject item = json.getAsJsonObject("item");
            String trackName = item.get("name").getAsString();
            String artistName = item.getAsJsonArray("artists").get(0).getAsJsonObject().get("name").getAsString();

            currentlyPlaying = trackName + " by " + artistName;
        } catch (Exception e) {
            currentlyPlaying = "No song playing";
            System.err.println("Error fetching currently playing track:");
            e.printStackTrace();
        }
    }

    private void startHttpServer() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(8080), 0);
            httpServer.createContext("/callback", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    String query = exchange.getRequestURI().getQuery();
                    if (query != null && query.contains("code=")) {
                        String code = extractQueryParameter(query, "code");
                        System.out.println("Authorization Code Received: " + code);

                        exchangeCodeForTokens(code);

                        String response = "Authorization successful! You can close this tab.";
                        exchange.sendResponseHeaders(200, response.getBytes().length);
                        OutputStream os = exchange.getResponseBody();
                        os.write(response.getBytes());
                        os.close();
                    }
                }
            });
            httpServer.start();
            System.out.println("HTTP Server started on http://localhost:8080/callback");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String extractQueryParameter(String query, String parameter) {
        try {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair[0].equals(parameter)) {
                    return URLDecoder.decode(pair[1], "UTF-8");
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing query parameter: " + parameter);
        }
        return null;
    }

    @Environment(EnvType.CLIENT)
    private void renderCurrentlyPlayingInHUD(DrawContext drawContext) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!authorizationRequested && accessToken == null) {
            authorizationRequested = true;
            startAuthorizationFlow();
        }

        if (currentlyPlaying != null && accessToken != null) {
            int logoX = 10, logoY = 10; // Position for the logo
            int logoWidth = 20, logoHeight = 20; // Size of the logo
            int textX = logoX + logoWidth + 5; // Position for the text (next to the logo)
            int textY = logoY + (logoHeight / 2) - (client.textRenderer.fontHeight / 2); // Center the text vertically with the logo

            drawContext.drawTexture(SPOTIFY_LOGO, logoX, logoY, 0, 0, logoWidth, logoHeight, logoWidth, logoHeight);
            drawContext.drawText(client.textRenderer, currentlyPlaying, textX, textY, 0xFFFFFF, true);
        }
    }

    public static void setCurrentlyPlaying(String status) {
        currentlyPlaying = status;
    }

}


