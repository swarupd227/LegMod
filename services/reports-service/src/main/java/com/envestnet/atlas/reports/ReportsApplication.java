package com.envestnet.atlas.reports;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.time.Duration;

@SpringBootApplication
@EnableAsync
public class ReportsApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReportsApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder b) {
        return b.setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofMinutes(2)).build();
    }

    @Bean
    public S3Client s3(@Value("${MINIO_ENDPOINT:http://minio:9000}") String endpoint,
                       @Value("${MINIO_ROOT_USER:atlas}") String user,
                       @Value("${MINIO_ROOT_PASSWORD:atlas_dev_pwd}") String pwd) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(user, pwd)))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .build();
    }
}
