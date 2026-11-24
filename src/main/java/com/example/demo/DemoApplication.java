package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * util/crudgen 을 바로 실행해 보기 위한 데모 애플리케이션.
 * 내 프로젝트에서 쓸 때는 util/crudgen 폴더만 복사한다.
 */
@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
