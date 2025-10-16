package com.github.topi314.lavasrc.musixmatch;

import com.github.topi314.lavalyrics.AudioLyricsManager;
import com.github.topi314.lavalyrics.lyrics.AudioLyrics;
import com.github.topi314.lavalyrics.lyrics.BasicAudioLyrics;
import com.github.topi314.lavasrc.LavaSrcTools;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class MusixmatchLyricsManager implements AudioLyricsManager {

	private static final String APP_ID = "web-desktop-app-v1.0";
	private static final long TOKEN_TTL = 55000;
	private static final long TOKEN_PERSIST_INTERVAL = 5000;
	private static final long CACHE_TTL = 300000;
	private static final int MAX_CACHE_ENTRIES = 100;
	private static final int REQUEST_TIMEOUT_MS = 8000;

	private static final String TOKEN_ENDPOINT = "https://apic-desktop.musixmatch.com/ws/1.1/token.get";
	private static final String SEARCH_ENDPOINT = "https://apic-desktop.musixmatch.com/ws/1.1/track.search";
	private static final String LYRICS_ENDPOINT = "https://apic-desktop.musixmatch.com/ws/1.1/track.subtitle.get";
	private static final String ALT_LYRICS_ENDPOINT = "https://apic-desktop.musixmatch.com/ws/1.1/macro.subtitles.get";

	private static final Pattern TIMESTAMP_REGEX = Pattern.compile("\\[\\d{1,2}:\\d{2}(?:\\.\\d{1,3})?\\]");
	private static final Pattern BRACKET_JUNK = Pattern.compile("\\s*\\[([^\\]]*(?:official|lyrics?|video|audio|mv|visualizer|color\\s*coded|hd|4k)[^\\]]*)\\]", Pattern.CASE_INSENSITIVE);
	private static final String[] SEPARATORS = {" - ", " – ", " — ", " ~ ", "-"};

	private final HttpInterfaceManager httpInterfaceManager;
	private final Path tokenFile;
	private final Map<String, CacheEntry> cache;
	private final RequestConfig requestConfig;

	private TokenData tokenData;
	private long lastTokenPersist = 0;

	public MusixmatchLyricsManager() {
		this(Paths.get(System.getProperty("java.io.tmpdir"), "mxm_token.json"));
	}

	public MusixmatchLyricsManager(Path tokenFile) {
		this.httpInterfaceManager = HttpClientTools.createCookielessThreadLocalManager();
		this.tokenFile = tokenFile;
		this.cache = new ConcurrentHashMap<>();
		this.requestConfig = RequestConfig.custom()
			.setConnectTimeout(REQUEST_TIMEOUT_MS)
			.setSocketTimeout(REQUEST_TIMEOUT_MS)
			.setConnectionRequestTimeout(REQUEST_TIMEOUT_MS)
			.build();
	}

	@NotNull
	@Override
	public String getSourceName() {
		return "Musixmatch";
	}

	@Override
	public @Nullable AudioLyrics loadLyrics(@NotNull AudioTrack audioTrack) {
		String query = audioTrack.getInfo().author + " - " + audioTrack.getInfo().title;
		try {
			return findLyrics(query);
		} catch (Exception e) {
			return null;
		}
	}

	private AudioLyrics findLyrics(String query) throws IOException {
		ParsedQuery parsed = parseQuery(query);
		String key = cacheKey(parsed.artist, parsed.title);

		CacheEntry cached = cache.get(key);
		if (cached != null && cached.expires > System.currentTimeMillis()) {
			return cached.value;
		}

		AudioLyrics result = null;

		try {
			if (parsed.artist != null && !parsed.artist.isEmpty()) {
				result = tryMacroEndpoint(parsed.artist, parsed.title);
				if (result == null) {
					result = trySearchAndLyrics(parsed.artist, parsed.title);
				}
			} else {
				result = trySearchAndLyrics(null, parsed.title);
			}

			if (result == null) {
				result = tryMacroEndpoint(null, parsed.title);
			}
		} catch (Exception e) {
			result = null;
		}

		setCached(key, result);
		return result;
	}

	private AudioLyrics tryMacroEndpoint(String artist, String title) throws IOException {
		try {
			URIBuilder builder = new URIBuilder(ALT_LYRICS_ENDPOINT)
				.addParameter("format", "json")
				.addParameter("namespace", "lyrics_richsynched")
				.addParameter("subtitle_format", "mxm")
				.addParameter("q_track", title);

			if (artist != null && !artist.isEmpty()) {
				builder.addParameter("q_artist", artist);
			}

			JsonBrowser body = callMxm(builder.build());
			JsonBrowser macroCalls = body.get("macro_calls");

			if (macroCalls.isNull()) {
				return null;
			}

			JsonBrowser lyricsData = macroCalls.get("track.lyrics.get").get("message").get("body").get("lyrics");
			JsonBrowser trackData = macroCalls.get("matcher.track.get").get("message").get("body").get("track");
			JsonBrowser subtitlesData = macroCalls.get("track.subtitles.get").get("message").get("body").get("subtitle_list");

			String lyrics = lyricsData.get("lyrics_body").text();
			String subtitles = null;

			if (!subtitlesData.isNull() && !subtitlesData.values().isEmpty()) {
				subtitles = subtitlesData.index(0).get("subtitle").get("subtitle_body").text();
			}

			if (lyrics != null || subtitles != null) {
				return formatResult(subtitles, lyrics, trackData);
			}
		} catch (Exception e) {
			// If it fails just return null.
		}
		return null;
	}

	private AudioLyrics trySearchAndLyrics(String artist, String title) throws IOException {
		try {
			URIBuilder builder = new URIBuilder(SEARCH_ENDPOINT)
				.addParameter("page_size", "1")
				.addParameter("page", "1")
				.addParameter("s_track_rating", "desc")
				.addParameter("q_track", title);

			if (artist != null && !artist.isEmpty()) {
				builder.addParameter("q_artist", artist);
			}

			JsonBrowser searchBody = callMxm(builder.build());
			JsonBrowser trackList = searchBody.get("track_list");

			if (trackList.isNull() || trackList.values().isEmpty()) {
				return null;
			}

			JsonBrowser track = trackList.index(0).get("track");
			String trackId = track.get("track_id").text();

			if (trackId == null) {
				return null;
			}

			URIBuilder lyricsBuilder = new URIBuilder(LYRICS_ENDPOINT)
				.addParameter("subtitle_format", "mxm")
				.addParameter("track_id", trackId);

			JsonBrowser lyricsBody = callMxm(lyricsBuilder.build());
			String subtitles = lyricsBody.get("subtitle").get("subtitle_body").text();

			if (subtitles != null) {
				return formatResult(subtitles, null, track);
			}
		} catch (Exception e) {
			// If it fails just return null.
		}
		return null;
	}

	private AudioLyrics formatResult(String subtitles, String lyrics, JsonBrowser track) {
		List<AudioLyrics.Line> lines = subtitles != null ? parseSubtitles(subtitles) : null;
		String text = lyrics != null ? cleanLyrics(lyrics) : (lines != null ? linesToText(lines) : null);

		String trackName = track.get("track_name").text();
		String artistName = track.get("artist_name").text();
		String albumArt = track.get("album_coverart_800x800").text();

		if (albumArt == null) {
			albumArt = track.get("album_coverart_350x350").text();
		}
		if (albumArt == null) {
			albumArt = track.get("album_coverart_100x100").text();
		}

		return new BasicAudioLyrics("Musixmatch", "Musixmatch", text, lines != null ? lines : new ArrayList<>());
	}

	private List<AudioLyrics.Line> parseSubtitles(String subtitleBody) {
		try {
			JsonBrowser parsed = JsonBrowser.parse(subtitleBody);
			List<JsonBrowser> arr = parsed.isList() ? parsed.values() : 
				(parsed.get("subtitle").isList() ? parsed.get("subtitle").values() : null);

			if (arr == null || arr.isEmpty()) {
				return null;
			}

			List<AudioLyrics.Line> lines = new ArrayList<>();
			for (JsonBrowser item : arr) {
				JsonBrowser timeTotal = item.get("time").get("total");
				double total = timeTotal.isNull() ? 0.0 : Double.parseDouble(timeTotal.text());
				String text = item.get("text").text();
				if (text != null) {
					lines.add(new BasicAudioLyrics.BasicLine(
						Duration.ofMillis(Math.round(total * 1000)),
						null,
						text
					));
				}
			}
			return lines;
		} catch (Exception e) {
			return null;
		}
	}

	private String cleanLyrics(String lyrics) {
		String cleaned = TIMESTAMP_REGEX.matcher(lyrics).replaceAll("");
		String[] lines = cleaned.split("\n");
		StringBuilder result = new StringBuilder();
		for (String line : lines) {
			String trimmed = line.trim();
			if (!trimmed.isEmpty()) {
				if (result.length() > 0) {
					result.append("\n");
				}
				result.append(trimmed);
			}
		}
		return result.toString();
	}

	private String linesToText(List<AudioLyrics.Line> lines) {
		StringBuilder sb = new StringBuilder();
		for (AudioLyrics.Line line : lines) {
			if (sb.length() > 0) {
				sb.append("\n");
			}
			sb.append(line.getLine());
		}
		return sb.toString();
	}

	private ParsedQuery parseQuery(String query) {
		String cleaned = BRACKET_JUNK.matcher(query).replaceAll("").trim();
		for (String separator : SEPARATORS) {
			int index = cleaned.indexOf(separator);
			if (index > 0 && index < cleaned.length() - separator.length()) {
				String artist = cleaned.substring(0, index).trim();
				String title = cleaned.substring(index + separator.length()).trim();
				if (!artist.isEmpty() && !title.isEmpty()) {
					return new ParsedQuery(artist, title);
				}
			}
		}
		return new ParsedQuery(null, cleaned);
	}

	private String cacheKey(String artist, String title) {
		String normalizedArtist = artist != null ? artist.toLowerCase().trim() : "";
		String normalizedTitle = title.toLowerCase().trim();
		return normalizedArtist + "|" + normalizedTitle;
	}

	private void setCached(String key, AudioLyrics value) {
		if (cache.size() >= MAX_CACHE_ENTRIES) {
			String firstKey = cache.keySet().iterator().next();
			cache.remove(firstKey);
		}
		cache.put(key, new CacheEntry(value, System.currentTimeMillis() + CACHE_TTL));
	}

	private synchronized String getToken(boolean force) throws IOException {
		long now = System.currentTimeMillis();

		if (!force && tokenData != null && now < tokenData.expires) {
			tokenData.expires = now + TOKEN_TTL;
			if (now - lastTokenPersist > TOKEN_PERSIST_INTERVAL) {
				lastTokenPersist = now;
				saveTokenToFile();
			}
			return tokenData.value;
		}

		if (tokenData == null && !force) {
			tokenData = readTokenFromFile();
			if (tokenData != null && now < tokenData.expires) {
				return tokenData.value;
			}
		}

		return acquireNewToken();
	}

	private String acquireNewToken() throws IOException {
		try {
			String token = fetchToken();
			long expires = System.currentTimeMillis() + TOKEN_TTL;
			tokenData = new TokenData(token, expires);
			saveTokenToFile();
			return token;
		} catch (IOException e) {
			throw e;
		}
	}

	private String fetchToken() throws IOException {
		try {
			URIBuilder builder = new URIBuilder(TOKEN_ENDPOINT)
				.addParameter("app_id", APP_ID);

			JsonBrowser body = apiGet(builder.build());
			return body.get("user_token").text();
		} catch (URISyntaxException e) {
			throw new IOException(e);
		}
	}

	private JsonBrowser callMxm(URI uri) throws IOException {
		String token = getToken(false);
		try {
			URIBuilder builder = new URIBuilder(uri)
				.addParameter("app_id", APP_ID)
				.addParameter("usertoken", token);
			return apiGet(builder.build());
		} catch (URISyntaxException e) {
			throw new IOException(e);
		}
	}

	private JsonBrowser apiGet(URI uri) throws IOException {
		HttpGet request = new HttpGet(uri);
		request.setConfig(requestConfig);
		request.setHeader("Accept", "application/json");
		request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

		try (var httpInterface = httpInterfaceManager.getInterface()) {
			JsonBrowser response = LavaSrcTools.fetchResponseAsJson(httpInterface, request);
			JsonBrowser header = response.get("message").get("header");
			JsonBrowser statusCodeBrowser = header.get("status_code");
			int statusCode = statusCodeBrowser.isNull() ? 0 : Integer.parseInt(statusCodeBrowser.text());

			if (statusCode != 200) {
				throw new IOException("Musixmatch API error: " + statusCode);
			}

			return response.get("message").get("body");
		}
	}

	private TokenData readTokenFromFile() {
		try {
			if (Files.exists(tokenFile)) {
				String content = new String(Files.readAllBytes(tokenFile));
				JsonBrowser json = JsonBrowser.parse(content);
				String value = json.get("value").text();
				JsonBrowser expiresBrowser = json.get("expires");
				long expires = expiresBrowser.isNull() ? 0 : Long.parseLong(expiresBrowser.text());

				if (value != null && expires > System.currentTimeMillis()) {
					return new TokenData(value, expires);
				}
			}
		} catch (Exception e) {
			// Ignore this.
		}
		return null;
	}

	private void saveTokenToFile() {
		try {
			if (tokenData != null) {
				String json = String.format("{\"value\":\"%s\",\"expires\":%d}", 
					tokenData.value, tokenData.expires);
				Files.write(tokenFile, json.getBytes());
			}
		} catch (Exception e) {
			// Ignore this.
		}
	}

	@Override
	public void shutdown() {
		try {
			httpInterfaceManager.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static class TokenData {
		String value;
		long expires;

		TokenData(String value, long expires) {
			this.value = value;
			this.expires = expires;
		}
	}

	private static class CacheEntry {
		AudioLyrics value;
		long expires;

		CacheEntry(AudioLyrics value, long expires) {
			this.value = value;
			this.expires = expires;
		}
	}

	private static class ParsedQuery {
		String artist;
		String title;

		ParsedQuery(String artist, String title) {
			this.artist = artist;
			this.title = title;
		}
	}
}
