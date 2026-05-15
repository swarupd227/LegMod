package com.envestnet.atlas.arch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@SpringBootApplication
@EnableAsync
public class ArchApplication {
    public static void main(String[] args) {
        SpringApplication.run(ArchApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder b) {
        return b
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
    }
}
