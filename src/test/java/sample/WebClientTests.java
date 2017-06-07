/*
 *
 *  * Copyright 2002-2017 the original author or authors.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *	  http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */
package sample;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.web.reactive.function.client.ExchangeFilterFunctions.basicAuthentication;

/**
 * @author Rob Winch
 * @since 5.0
 */
public class WebClientTests {

	private MockWebServer server;

	private WebClient webClient;


	@Before
	public void setup() {
		this.server = new MockWebServer();
		String baseUrl = this.server.url("/").toString();
		this.webClient = WebClient.create(baseUrl);
	}

	@After
	public void shutdown() throws Exception {
		this.server.shutdown();
	}

	@Test
	public void httpBasicWhenNeeded() throws Exception {
		this.server.enqueue(new MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=\"Test\""));
		this.server.enqueue(new MockResponse().setResponseCode(200).setBody("OK"));

		ClientResponse response = this.webClient
				.filter(basicIfNeeded("rob", "rob"))
				.get()
				.uri("/")
				.exchange()
				.block();

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);

		assertThat(this.server.takeRequest().getHeader("Authorization")).isNull();
		assertThat(this.server.takeRequest().getHeader("Authorization")).isEqualTo("Basic cm9iOnJvYg==");
	}


	@Test
	public void httpBasicWhenNotNeeded() throws Exception {
		this.server.enqueue(new MockResponse().setResponseCode(200).setBody("OK"));

		ClientResponse response = this.webClient
				.filter(basicIfNeeded("rob", "rob"))
				.get()
				.uri("/")
				.exchange()
				.block();

		assertThat(response.statusCode()).isEqualTo(HttpStatus.OK);

		assertThat(this.server.getRequestCount()).isEqualTo(1);
		assertThat(this.server.takeRequest().getHeader("Authorization")).isNull();
	}

	@Test
	public void oauth() throws Exception {
		this.server.enqueue(jsonResponse(401).setBody("{\"code\":401,\n" +
				"\"error\":\"invalid_token\",\n" +
				"\"error_description\":\"The access token provided has expired.\"\n" +
				"}"));
		this.server.enqueue(jsonResponse(200).setBody("{\"message\":\"See if you can refresh the token when it expires.\"}"));

		Message message = this.webClient
				.filter(refreshAccessTokenIfNeeded("foo", "bar"))
				.filter(oauth2BearerToken("token"))
				.get()
				.uri("/messages/1")
				.retrieve()
				.toEntity(Message.class)
				.block()
				.getBody();

		assertThat(message.getMessage()).isNotNull();

		assertThat(this.server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer token");
		assertThat(this.server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer new_token");
	}

	private static MockResponse jsonResponse(int code) {
		return new MockResponse().setResponseCode(code).setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);
	}

	static class Message {
		private String message;

		public String getMessage() {
			return message;
		}

		public void setMessage(String message) {
			this.message = message;
		}
	}

	public static ExchangeFilterFunction oauth2BearerToken(String token) {
		return ExchangeFilterFunction.ofRequestProcessor(
				clientRequest -> {
					ClientRequest authorizedRequest = ClientRequest.from(clientRequest)
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
							.build();
					return Mono.just(authorizedRequest);
				});
	}

	private ExchangeFilterFunction refreshAccessTokenIfNeeded(String username, String password) {
		return (request, next) ->
				next.exchange(request)
						.filter( r -> !HttpStatus.UNAUTHORIZED.equals(r.statusCode()))
						.switchIfEmpty( Mono.defer(() -> {
							String newToken = "new_token";
							return oauth2BearerToken(newToken).filter(request, next);
						}));
	}

	private ExchangeFilterFunction basicIfNeeded(String username, String password) {
		return (request, next) ->
				next.exchange(request)
						.filter( r -> !HttpStatus.UNAUTHORIZED.equals(r.statusCode()))
						.switchIfEmpty( Mono.defer(() -> {
							return basicAuthentication(username, password).filter(request, next);
						}));
	}
}
