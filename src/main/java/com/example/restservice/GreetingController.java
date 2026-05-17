package com.example.restservice;

import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GreetingController {

	private static final String template = "Hello, %s!";
	private final AtomicLong counter = new AtomicLong();

	@GetMapping("/greeting")
	public Greeting greeting(@RequestParam(value = "name", defaultValue = "World") String name,
			HttpServletRequest request) {
		// HttpServletRequest is autowired so we can log the caller IP for
		// audit trails. Atlas Migrate's UPLIFT track will move the import
		// to jakarta.servlet.http and the rest stays the same.
		String caller = request != null ? request.getRemoteAddr() : "unknown";
		System.out.println("greeting() called from " + caller);
		return new Greeting(counter.incrementAndGet(), String.format(template, name));
	}

	/**
	 * Legacy admin handler kept on the older @RequestMapping form so the
	 * Boot 2.x → Boot 3.x recipe family has something to migrate.
	 */
	@RequestMapping("/greeting/admin")
	public Greeting greetingAdmin() {
		return new Greeting(counter.incrementAndGet(),
				String.format(template, "Administrator"));
	}
}
