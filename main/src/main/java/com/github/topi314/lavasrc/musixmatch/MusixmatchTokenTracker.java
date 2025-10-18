package com.github.topi314.lavasrc.musixmatch;

import com.github.topi314.lavasrc.LavaSrcTools;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MusixmatchTokenTracker {
	private static final Logger log = LoggerFactory.getLogger(MusixmatchTokenTracker.class);

	private static final Pattern TOKEN_PATTERN = Pattern.compile("usertoken[\"']?\\s*[:=]\\s*[\"']([a-f0-9]{40,})[\"']", Pattern.CASE_INSENSITIVE);
	private static final Pattern APP_ID_PATTERN = Pattern.compile("app_id[\"']?\\s*[:=]\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
	private static final Pattern CONFIG_TOKEN_PATTERN = Pattern.compile("token[\"']?\\s*[:=]\\s*[\"']([a-f0-9]{40,})[\"']", Pattern.CASE_INSENSITIVE);
	private static final Pattern SECRET_PATTERN = Pattern.compile("\"secret\":\\[(\\d+(?:,\\d+)+)]");

	private static final String MUSIXMATCH_HOME = "https://www.musixmatch.com/";
	private static final String TOKEN_ENDPOINT = "https://apic-desktop.musixmatch.com/ws/1.1/token.get";
	private static final String APP_ID = "web-desktop-app-v1.0";
	private static final long TOKEN_TTL = 55000;
	private static final int REQUEST_TIMEOUT_MS = 8000;

	private final Object tokenLock = new Object();
	private final RequestConfig requestConfig;

	private String userToken;
	private Instant tokenExpires;
	private String extractedAppId;

	public MusixmatchTokenTracker() {
		this.requestConfig = RequestConfig.custom()
			.setConnectTimeout(REQUEST_TIMEOUT_MS)
			.setSocketTimeout(REQUEST_TIMEOUT_MS)
			.setConnectionRequestTimeout(REQUEST_TIMEOUT_MS)
			.build();
	}

	public String getUserToken() throws IOException {
		if (this.userToken == null || this.tokenExpires == null || this.tokenExpires.isBefore(Instant.now())) {
			synchronized (tokenLock) {
				if (this.userToken == null || this.tokenExpires == null || this.tokenExpires.isBefore(Instant.now())) {
					log.debug("User token is invalid or expired, refreshing token...");
					this.refreshUserToken();
				}
			}
		}
		return this.userToken;
	}

	private void refreshUserToken() throws IOException {
		String token = null;

		try {
			token = extractTokenFromWebsite();
		} catch (Exception e) {
			log.debug("Failed to extract token from website, falling back to API", e);
		}

		if (token == null) {
			token = fetchTokenFromAPI();
		}

		this.userToken = token;
		this.tokenExpires = Instant.now().plusMillis(TOKEN_TTL);
	}

	private String extractTokenFromWebsite() throws IOException {
		log.debug("Attempting to extract token from Musixmatch website");

		try (CloseableHttpClient client = HttpClients.createDefault()) {
			HttpGet request = new HttpGet(MUSIXMATCH_HOME);
			request.setConfig(requestConfig);

			try (CloseableHttpResponse response = client.execute(request)) {
				String html = EntityUtils.toString(response.getEntity());
				Document doc = Jsoup.parse(html);
				Elements scriptElements = doc.select("script[src]");

				List<String> scriptUrls = new ArrayList<>();
				for (Element script : scriptElements) {
					String scriptUrl = script.attr("src");
					if (scriptUrl.contains("main") || scriptUrl.contains("app") || scriptUrl.contains("bundle")) {
						if (!scriptUrl.startsWith("http")) {
							scriptUrl = MUSIXMATCH_HOME + scriptUrl.replaceFirst("^/", "");
						}
						scriptUrls.add(scriptUrl);
						log.debug("Found script URL: {}", scriptUrl);
					}
				}

				Elements inlineScripts = doc.select("script:not([src])");
				for (Element inlineScript : inlineScripts) {
					String scriptContent = inlineScript.html();
					String token = extractTokenFromScript(scriptContent);
					if (token != null) {
						log.debug("Extracted token from inline script");
						return token;
					}
				}

				for (String scriptUrl : scriptUrls) {
					String token = extractTokenFromScriptUrl(client, scriptUrl);
					if (token != null) {
						log.debug("Extracted token from script: {}", scriptUrl);
						return token;
					}
				}
			}
		}

		log.debug("No token found in website scripts");
		return null;
	}

	private String extractTokenFromScriptUrl(CloseableHttpClient client, String scriptUrl) throws IOException {
		HttpGet scriptRequest = new HttpGet(scriptUrl);
		scriptRequest.setConfig(requestConfig);

		try (CloseableHttpResponse scriptResponse = client.execute(scriptRequest)) {
			String scriptContent = EntityUtils.toString(scriptResponse.getEntity());
			return extractTokenFromScript(scriptContent);
		}
	}

	private String extractTokenFromScript(String scriptContent) {
		Matcher tokenMatcher = TOKEN_PATTERN.matcher(scriptContent);
		if (tokenMatcher.find()) {
			return tokenMatcher.group(1);
		}

		Matcher configMatcher = CONFIG_TOKEN_PATTERN.matcher(scriptContent);
		if (configMatcher.find()) {
			return configMatcher.group(1);
		}

		Matcher appIdMatcher = APP_ID_PATTERN.matcher(scriptContent);
		if (appIdMatcher.find()) {
			this.extractedAppId = appIdMatcher.group(1);
			log.debug("Extracted app_id: {}", this.extractedAppId);
		}

		return null;
	}

	private String fetchTokenFromAPI() throws IOException {
		log.debug("Fetching token from Musixmatch API");

		try {
			String appId = this.extractedAppId != null ? this.extractedAppId : APP_ID;

			URIBuilder builder = new URIBuilder(TOKEN_ENDPOINT)
				.addParameter("app_id", appId);

			try (CloseableHttpClient client = HttpClients.createDefault()) {
				HttpGet request = new HttpGet(builder.build());
				request.setConfig(requestConfig);
				request.setHeader("Accept", "application/json");
				request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

				try (CloseableHttpResponse response = client.execute(request)) {
					String responseBody = EntityUtils.toString(response.getEntity());
					String token = parseTokenFromResponse(responseBody);

					if (token == null) {
						throw new IOException("Failed to extract token from API response");
					}

					log.debug("Successfully fetched token from API");
					return token;
				}
			}
		} catch (URISyntaxException e) {
			throw new IOException("Failed to build token request URL", e);
		}
	}

	private String parseTokenFromResponse(String responseBody) {
		Matcher matcher = Pattern.compile("\"user_token\"\\s*:\\s*\"([^\"]+)\"").matcher(responseBody);
		if (matcher.find()) {
			return matcher.group(1);
		}
		return null;
	}

	public String getAppId() {
		return this.extractedAppId != null ? this.extractedAppId : APP_ID;
	}

	public void invalidateToken() {
		synchronized (tokenLock) {
			this.userToken = null;
			this.tokenExpires = null;
		}
	}
}
