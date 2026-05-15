package com.envestnet.atlas.prj;

import com.envestnet.atlas.prj.auth.IdentityForwardingInterceptor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@SpringBootApplication
public class PrjApplication {
    public static void main(String[] args) {
        SpringApplication.run(PrjApplication.class, args);
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder b,
                                     IdentityForwardingInterceptor identityForwarder) {
        // Wrap the underlying factory so interceptors can read the body
        // multiple times (Spring's default factory is single-shot).
        var factory = new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory());
        return b
                .requestFactory(() -> factory)
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofMinutes(3))   // archaeology runs can be slow
                .additionalInterceptors(identityForwarder)
                .build();
    }
}
