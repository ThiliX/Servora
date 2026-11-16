package com.servora;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ServoraCoreApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServoraCoreApiApplication.class, args);
    }
}
