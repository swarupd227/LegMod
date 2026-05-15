package com.envestnet.atlas.graph;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import jakarta.annotation.PreDestroy;

@SpringBootApplication
public class GraphApplication {
    public static void main(String[] args) {
        SpringApplication.run(GraphApplication.class, args);
    }

    private Driver driver;

    @Bean
    public Driver neo4jDriver(@Value("${NEO4J_URI:bolt://neo4j:7687}") String uri,
                              @Value("${NEO4J_USERNAME:neo4j}") String user,
                              @Value("${NEO4J_PASSWORD:atlas_dev_pwd}") String pwd) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, pwd));
        return driver;
    }

    @PreDestroy
    public void close() {
        if (driver != null) driver.close();
    }
}
