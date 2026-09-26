package com.dcos.platform.certapi.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a test class that needs the PostgreSQL test database. Place it before the Spring test
 * annotation so the availability probe runs before the application context is built.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(TestDatabaseAvailabilityExtension.class)
public @interface RequiresTestDatabase {}
