package com.jagrosh.jmusicbot.commands.music;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpotifyCmd extends MusicCommand {

    private static final Logger log = LoggerFactory.getLogger(SpotifyCmd.class);
    private static final HttpClient httpClient = HttpClient.newBuilder().build();
    private static final String SPOTIFY_AUTH_URL = "https://accounts.spotify.com/api/token";
    private static final int SPOTIFY_PLAYLIST_LIMIT = 100; // Spotify API limit per request

    private String accessToken = null;
    private long accessTokenExpirationTime;

    public SpotifyCmd(Bot bot) {
        super(bot);
        this.name = "spotify";
        this.arguments = "<Spotify URL>";
        this.help = "Plays the specified Spotify track or playlist";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.beListening = true;
        this.bePlaying = false;

        // Spotify credentials
        String clientId = bot.getConfig().getSpotifyClientId();
        String clientSecret = bot.getConfig().getSpotifyClientSecret();

        if (clientId.isEmpty() || clientSecret.isEmpty()) {
            return;
        }
        // ACCESS_TOKEN
        accessToken = getAccessToken(clientId, clientSecret);
    }

    @Override
    public void doCommand(CommandEvent event) {
        if (event.getArgs().isEmpty()) {
            event.reply(event.getClient().getError() + " Please include a Spotify URL.");
            return;
        }
        String url = event.getArgs();

        if (accessToken == null) {
            event.reply("This command is disabled and must be enabled by the bot owner.");
            return;
        }

        // If the access token has expired, reissue it.
        if (System.currentTimeMillis() >= accessTokenExpirationTime) {
            String clientId = bot.getConfig().getSpotifyClientId();
            String clientSecret = bot.getConfig().getSpotifyClientSecret();
            accessToken = getAccessToken(clientId, clientSecret);
        }

        if (isSpotifyTrackUrl(url)) {
            handleTrack(event, url);
        } else if (isSpotifyPlaylistUrl(url)) {
            handlePlaylist(event, url);
        } else {
            event.reply("Error: The specified URL is not a valid Spotify track or playlist URL");
        }
    }

    private void handleTrack(CommandEvent event, String trackUrl) {
        String trackId = extractIdFromUrl(trackUrl);
        String endpoint = "https://api.spotify.com/v1/tracks/" + trackId;

        try {
            JSONObject trackInfo = fetchJsonFromSpotify(endpoint);
            String trackName = trackInfo.getString("name");
            String artistName = trackInfo.getJSONArray("artists").getJSONObject(0).getString("name");

            loadAndPlay(event, trackName + " " + artistName);
        } catch (IOException | InterruptedException e) {
            event.reply("Error: " + e.getMessage());
        }
    }

    private void handlePlaylist(CommandEvent event, String playlistUrl) {
        String playlistId = extractIdFromUrl(playlistUrl);
        String endpoint = "https://api.spotify.com/v1/playlists/" + playlistId;

        try {
            JSONObject playlistInfo = fetchJsonFromSpotify(endpoint);
            String playlistName = playlistInfo.getString("name");
            int totalTracks = playlistInfo.getJSONObject("tracks").getInt("total");

            event.reply("Loading playlist: " + playlistName + " (" + totalTracks + " tracks)");

            List<String> trackQueries = new ArrayList<>();
            for (int offset = 0; offset < totalTracks; offset += SPOTIFY_PLAYLIST_LIMIT) {
                String tracksEndpoint = endpoint + "/tracks?offset=" + offset + "&limit=" + SPOTIFY_PLAYLIST_LIMIT;
                JSONObject tracksInfo = fetchJsonFromSpotify(tracksEndpoint);
                JSONArray items = tracksInfo.getJSONArray("items");

                for (int i = 0; i < items.length(); i++) {
                    JSONObject track = items.getJSONObject(i).getJSONObject("track");
                    String trackName = track.getString("name");
                    String artistName = track.getJSONArray("artists").getJSONObject(0).getString("name");
                    trackQueries.add(trackName + " " + artistName);
                }
            }

            loadAndPlayBatch(event, trackQueries);
        } catch (IOException | InterruptedException e) {
            event.reply("Error: " + e.getMessage());
        }
    }

    private void loadAndPlayBatch(CommandEvent event, List<String> queries) {
        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String query : queries) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            bot.getPlayerManager().loadItemOrdered(event.getGuild(), "ytsearch:" + query, new AudioLoadResultHandler() {
                @Override
                public void trackLoaded(AudioTrack track) {
                    if (!bot.getConfig().isTooLong(track)) {
                        RequestMetadata rm = new RequestMetadata(event.getAuthor(), new RequestMetadata.RequestInfo(query, track.getInfo().uri));
                        handler.addTrack(new QueuedTrack(track, rm));
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                    future.complete(null);
                }

                @Override
                public void playlistLoaded(AudioPlaylist playlist) {
                    if (!playlist.getTracks().isEmpty()) {
                        trackLoaded(playlist.getTracks().get(0));
                    } else {
                        failCount.incrementAndGet();
                        future.complete(null);
                    }
                }

                @Override
                public void noMatches() {
                    failCount.incrementAndGet();
                    future.complete(null);
                }

                @Override
                public void loadFailed(FriendlyException exception) {
                    failCount.incrementAndGet();
                    future.complete(null);
                }
            });
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenRun(() -> {
            String message = String.format("Playlist loaded: %d tracks added successfully, %d failed to load.",
                    successCount.get(), failCount.get());
            event.reply(message);
        });
    }

    private void loadAndPlay(CommandEvent event, String query) {
        event.getChannel().sendMessage("Loading: " + query).queue(m ->
                bot.getPlayerManager().loadItemOrdered(event.getGuild(), "ytsearch:" + query, new ResultHandler(m, event))
        );
    }

    private JSONObject fetchJsonFromSpotify(String endpoint) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .uri(URI.create(endpoint))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return new JSONObject(response.body());
    }

    private String extractIdFromUrl(String url) {
        Pattern pattern = Pattern.compile("(track|playlist)/([a-zA-Z0-9]+)");
        Matcher matcher = pattern.matcher(url);
        return matcher.find() ? matcher.group(2) : null;
    }

    private boolean isSpotifyTrackUrl(String url) {
        return url.matches("https://open\\.spotify\\.com/(intl(-[a-z]{2})?/)?track/[a-zA-Z0-9]+");
    }

    public boolean isSpotifyPlaylistUrl(String url) {
        return url.matches("https://open\\.spotify\\.com/playlist/\\w+\\??.*");
    }

    private String getAccessToken(String clientId, String clientSecret) {
        String encodedCredentials = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes());

        HttpRequest request = HttpRequest.newBuilder()
                .header("Authorization", "Basic " + encodedCredentials)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .uri(URI.create(SPOTIFY_AUTH_URL))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject json = new JSONObject(response.body());
            accessTokenExpirationTime = System.currentTimeMillis() + json.getInt("expires_in") * 1000L;
            return json.getString("access_token");
        } catch (IOException | InterruptedException e) {
            log.error("Failed to get Spotify access token", e);
            return null;
        }
    }

    private class ResultHandler implements AudioLoadResultHandler {
        private final Message m;
        private final CommandEvent event;

        private ResultHandler(Message m, CommandEvent event) {
            this.m = m;
            this.event = event;
        }

        @Override
        public void trackLoaded(AudioTrack track) {
            if (bot.getConfig().isTooLong(track)) {
                m.editMessage(FormatUtil.filter(event.getClient().getWarning() + "**" + track.getInfo().title + "** is longer than the maximum allowed length: "
                        + TimeUtil.formatTime(track.getDuration()) + " > " + bot.getConfig().getMaxTime())).queue();
                return;
            }
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            RequestMetadata rm = new RequestMetadata(event.getAuthor(), new RequestMetadata.RequestInfo(event.getArgs(), track.getInfo().uri));
            int pos = handler.addTrack(new QueuedTrack(track, rm)) + 1;
            m.editMessage(FormatUtil.filter(event.getClient().getSuccess() + "**" + track.getInfo().title
                    + "** (" + TimeUtil.formatTime(track.getDuration()) + ") " + (pos == 0 ? "has been added."
                    : "has been added at position " + pos + "."))).queue();
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist) {
            // This method is not used for Spotify playlists, as we handle them manually
        }

        @Override
        public void noMatches() {
            m.editMessage(FormatUtil.filter(event.getClient().getWarning() + " No matches found.")).queue();
        }

        @Override
        public void loadFailed(FriendlyException throwable) {
            if (throwable.severity == FriendlyException.Severity.COMMON)
                m.editMessage(event.getClient().getError() + " Error loading track: " + throwable.getMessage()).queue();
            else
                m.editMessage(event.getClient().getError() + " Error loading track.").queue();
        }
    }
}