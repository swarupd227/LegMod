package com.envestnet.atlas.cap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import java.net.URI;

@SpringBootApplication
public class CapApplication {
    public static void main(String[] args) {
        SpringApplication.run(CapApplication.class, args);
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
