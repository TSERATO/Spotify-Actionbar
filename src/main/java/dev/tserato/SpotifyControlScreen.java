package dev.tserato;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public class SpotifyControlScreen extends Screen {

    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SPACE_BETWEEN = 5;

    private ButtonWidget playpause;
    private ButtonWidget skip;
    private ButtonWidget previous;

    protected SpotifyControlScreen() {
        super(Text.literal("Spotify Playback Settings"));
    }

    @Override
    protected void init() {
        int totalHeight = (BUTTON_HEIGHT * 3) + (SPACE_BETWEEN * 2);
        int startY = (height - totalHeight) / 2;

        playpause = createButton("Play/Pause", startY, this::togglePlayPause, "Start or Stop the Playback of the Track.");
        skip = createButton("Skip", startY + BUTTON_HEIGHT + SPACE_BETWEEN, this::skipTrack, "Skip to the next Track.");
        previous = createButton("Previous", startY + (BUTTON_HEIGHT + SPACE_BETWEEN) * 2, this::previousTrack, "Go to the previous Track.");

        addDrawableChild(playpause);
        addDrawableChild(skip);
        addDrawableChild(previous);
    }

    private ButtonWidget createButton(String label, int yPos, Runnable onClick, String tooltip) {
        return ButtonWidget.builder(Text.literal(label), button -> onClick.run())
                .dimensions(width / 2 - BUTTON_WIDTH / 2, yPos, BUTTON_WIDTH, BUTTON_HEIGHT)
                .tooltip(Tooltip.of(Text.literal(tooltip)))
                .build();
    }

    private void togglePlayPause() {
        try {
            sendRequest("https://api.spotify.com/v1/me/player/pause", "PUT");
        } catch (IOException e) {
            handleError(e);
        }
    }

    private boolean isPlaying() throws IOException {
        // Logic to check if the track is playing (can be added here if needed)
        return false;
    }

    private void skipTrack() {
        try {
            sendRequest("https://api.spotify.com/v1/me/player/next", "POST");
        } catch (IOException e) {
            handleError(e);
        }
    }

    private void previousTrack() {
        try {
            sendRequest("https://api.spotify.com/v1/me/player/previous", "POST");
        } catch (IOException e) {
            handleError(e);
        }
    }

    private void sendRequest(String urlString, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + SpotifyActionbarClient.getAccessToken());
        connection.setRequestProperty("Content-Length", "0");
        connection.setDoOutput(true);
        connection.getOutputStream().close();
        int responseCode = connection.getResponseCode();
        SpotifyActionbarClient.setCurrentlyPlaying("Paused");
        if (responseCode == 403) {
            sendRequest("https://api.spotify.com/v1/me/player/play", "PUT");
        }
    }

    private void handleError(IOException e) {
        // Handle exceptions appropriately (e.g., log or notify the user)
        e.printStackTrace();
    }
}
