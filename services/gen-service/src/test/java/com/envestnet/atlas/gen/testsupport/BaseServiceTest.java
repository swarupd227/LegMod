package com.envestnet.atlas.gen.testsupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BaseServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("atlas")
            .withUsername("atlas")
            .withPassword("atlas_dev_pwd")
            .withInitScript("init/extensions.sql")
            .withReuse(true);

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(
            DockerImageName.parse("minio/minio:latest"))
            .withUserName("atlas")
            .withPassword("atlas_dev_pwd")
            .withReuse(true);

    @DynamicPropertySource
    static void wireSpring(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);

        r.add("MINIO_ENDPOINT",      MINIO::getS3URL);
        r.add("MINIO_ROOT_USER",     MINIO::getUserName);
        r.add("MINIO_ROOT_PASSWORD", MINIO::getPassword);
        r.add("MINIO_BUCKET_CORPUS", () -> "atlas-corpus-test");
    }
}
