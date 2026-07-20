package com.sks;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.sks.**.mapper")
public class SksServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SksServerApplication.class, args);
    }
}
