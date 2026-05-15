# spring5-jakarta-demo — javax-era Spring application

Tiny Spring 5 / Java 8 application used as the input fixture for the
Framework Uplift track. Demonstrates the patterns OpenRewrite recipes must
rewrite for a Spring 6 + Jakarta EE 9+ + Java 21 target:

- `javax.servlet.*` and `javax.persistence.*` imports
- `javax.validation.*` annotations
- A Struts-flavored `@RequestMapping` controller pre-dating modern conventions
- An IBM-proprietary JNDI lookup (`com.ibm.websphere.naming`) — needs a generic JNDI replacement
- A cron-style scheduled task using deprecated APIs
- `pom.xml` pinned to Spring 5.3.x / Java 8

This **is** buildable on Java 8 with Maven if you ever want to run it; for the
purposes of Atlas Migrate it just needs to be readable.
