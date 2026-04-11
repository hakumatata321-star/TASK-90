package com.example.ricms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RicmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(RicmsApplication.class, args);
    }
}
