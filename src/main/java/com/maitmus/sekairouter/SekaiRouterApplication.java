package com.maitmus.sekairouter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SekaiRouterApplication {
    public static void main(String[] args) {
        SpringApplication.run(SekaiRouterApplication.class, args);
    }
}
