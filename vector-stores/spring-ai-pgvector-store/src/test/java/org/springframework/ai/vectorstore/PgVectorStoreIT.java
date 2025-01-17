/*
 * Copyright 2023-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.vectorstore;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.ai.vectorstore.PgVectorStore.PgIndexType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.CollectionUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Christian Tzolov
 */
@Testcontainers
public class PgVectorStoreIT {

	@Container
	static GenericContainer<?> postgresContainer = new GenericContainer<>("ankane/pgvector")
		.withEnv("POSTGRES_USER", "postgres")
		.withEnv("POSTGRES_PASSWORD", "postgres")
		.withExposedPorts(5432);

	List<Document> documents = List.of(
			new Document("Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!!",
					Collections.singletonMap("meta1", "meta1")),
			new Document("Hello World Hello World Hello World Hello World Hello World Hello World Hello World"),
			new Document(
					"Great Depression Great Depression Great Depression Great Depression Great Depression Great Depression",
					Collections.singletonMap("meta2", "meta2")));

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestApplication.class)
		.withPropertyValues("spring.ai.openai.apiKey=" + System.getenv("OPENAI_API_KEY"),
				"test.spring.ai.vectorstore.pgvector.distanceType=CosineDistance",

				// JdbcTemplate configuration
				String.format("app.datasource.url=jdbc:postgresql://localhost:%d/%s",
						postgresContainer.getMappedPort(5432), "postgres"),
				"app.datasource.username=postgres", "app.datasource.password=postgres",
				"app.datasource.type=com.zaxxer.hikari.HikariDataSource");

	private static void dropTable(ApplicationContext context) {
		JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
		jdbcTemplate.execute("DROP TABLE IF EXISTS vector_store");
	}

	@ParameterizedTest
	@ValueSource(strings = { "CosineDistance", "EuclideanDistance", "NegativeInnerProduct" })
	public void addAndSearchTest(String distanceType) {
		contextRunner.withConfiguration(AutoConfigurations.of(OpenAiAutoConfiguration.class))
			.withPropertyValues("test.spring.ai.vectorstore.pgvector.distanceType=" + distanceType)
			.run(context -> {

				VectorStore vectorStore = context.getBean(VectorStore.class);

				vectorStore.add(documents);

				List<Document> results = vectorStore.similaritySearch("Great", 1);

				assertThat(results).hasSize(1);
				Document resultDoc = results.get(0);
				assertThat(resultDoc.getId()).isEqualTo(documents.get(2).getId());
				assertThat(resultDoc.getContent()).isEqualTo(
						"Great Depression Great Depression Great Depression Great Depression Great Depression Great Depression");
				assertThat(resultDoc.getMetadata()).containsKeys("meta2", "distance");

				// Remove all documents from the store
				vectorStore.delete(documents.stream().map(doc -> doc.getId()).toList());

				List<Document> results2 = vectorStore.similaritySearch("Great", 1);
				assertThat(results2).hasSize(0);

				dropTable(context);
			});
	}

	@ParameterizedTest
	@ValueSource(strings = { "CosineDistance", "EuclideanDistance", "NegativeInnerProduct" })
	public void documentUpdateTest(String distanceType) {

		contextRunner.withConfiguration(AutoConfigurations.of(OpenAiAutoConfiguration.class))
			.withPropertyValues("test.spring.ai.vectorstore.pgvector.distanceType=" + distanceType)
			.run(context -> {

				VectorStore vectorStore = context.getBean(VectorStore.class);

				Document document = new Document(UUID.randomUUID().toString(), "Spring AI rocks!!",
						Collections.singletonMap("meta1", "meta1"));

				vectorStore.add(List.of(document));

				List<Document> results = vectorStore.similaritySearch("Spring", 5);

				assertThat(results).hasSize(1);
				Document resultDoc = results.get(0);
				assertThat(resultDoc.getId()).isEqualTo(document.getId());
				assertThat(resultDoc.getContent()).isEqualTo("Spring AI rocks!!");
				assertThat(resultDoc.getMetadata()).containsKeys("meta1", "distance");

				Document sameIdDocument = new Document(document.getId(),
						"The World is Big and Salvation Lurks Around the Corner",
						Collections.singletonMap("meta2", "meta2"));

				vectorStore.add(List.of(sameIdDocument));

				results = vectorStore.similaritySearch("FooBar", 5);

				assertThat(results).hasSize(1);
				resultDoc = results.get(0);
				assertThat(resultDoc.getId()).isEqualTo(document.getId());
				assertThat(resultDoc.getContent()).isEqualTo("The World is Big and Salvation Lurks Around the Corner");
				assertThat(resultDoc.getMetadata()).containsKeys("meta2", "distance");

				dropTable(context);
			});
	}

	@ParameterizedTest
	@ValueSource(strings = { "CosineDistance", "EuclideanDistance", "NegativeInnerProduct" })
	public void searchThresholdTest(String distanceType) {

		contextRunner.withConfiguration(AutoConfigurations.of(OpenAiAutoConfiguration.class))
			.withPropertyValues("test.spring.ai.vectorstore.pgvector.distanceType=" + distanceType)
			.run(context -> {

				VectorStore vectorStore = context.getBean(VectorStore.class);

				vectorStore.add(documents);

				List<Document> fullResult = vectorStore.similaritySearch("Great", 5, 0.0);

				List<Float> distances = fullResult.stream()
					.map(doc -> (Float) doc.getMetadata().get("distance"))
					.toList();

				assertThat(fullResult).hasSize(3);

				assertThat(isSortedByDistance(fullResult)).isTrue();

				fullResult.stream().forEach(doc -> System.out.println(doc.getMetadata().get("distance")));

				float threshold = (distances.get(0) + distances.get(1)) / 2;

				List<Document> results = vectorStore.similaritySearch("Great", 5, (1 - threshold));

				assertThat(results).hasSize(1);
				Document resultDoc = results.get(0);
				assertThat(resultDoc.getId()).isEqualTo(documents.get(2).getId());
				assertThat(resultDoc.getContent()).isEqualTo(
						"Great Depression Great Depression Great Depression Great Depression Great Depression Great Depression");
				assertThat(resultDoc.getMetadata()).containsKeys("meta2", "distance");

				dropTable(context);
			});
	}

	private static boolean isSortedByDistance(List<Document> docs) {

		List<Float> distances = docs.stream().map(doc -> (Float) doc.getMetadata().get("distance")).toList();

		if (CollectionUtils.isEmpty(distances) || distances.size() == 1) {
			return true;
		}

		Iterator<Float> iter = distances.iterator();
		Float current, previous = iter.next();
		while (iter.hasNext()) {
			current = iter.next();
			if (previous > current) {
				return false;
			}
			previous = current;
		}
		return true;
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration(exclude = { DataSourceAutoConfiguration.class })
	public static class TestApplication {

		@Value("${test.spring.ai.vectorstore.pgvector.distanceType}")
		PgVectorStore.PgDistanceType distanceType;

		@Bean
		public VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingClient embeddingClient) {
			return new PgVectorStore(jdbcTemplate, embeddingClient, PgVectorStore.INVALID_EMBEDDING_DIMENSION,
					distanceType, true, PgIndexType.HNSW);
		}

		@Bean
		public JdbcTemplate myJdbcTemplate(DataSource dataSource) {
			return new JdbcTemplate(dataSource);
		}

		@Bean
		@Primary
		@ConfigurationProperties("app.datasource")
		public DataSourceProperties dataSourceProperties() {
			return new DataSourceProperties();
		}

		@Bean
		public HikariDataSource dataSource(DataSourceProperties dataSourceProperties) {
			return dataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
		}

	}

}
