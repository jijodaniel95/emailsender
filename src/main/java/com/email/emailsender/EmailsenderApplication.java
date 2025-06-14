package com.email.emailsender;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.concurrent.CountDownLatch;

@SpringBootApplication
public class EmailsenderApplication implements CommandLineRunner {

	private final CountDownLatch latch = new CountDownLatch(1);

	public static void main(String[] args) {
		SpringApplication.run(EmailsenderApplication.class, args);
	}

	@Override
	public void run(String... args) throws Exception {
		latch.await(); // Keep the application running
	}

}
