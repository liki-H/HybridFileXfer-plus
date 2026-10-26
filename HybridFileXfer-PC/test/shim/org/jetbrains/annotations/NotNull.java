package org.jetbrains.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-only shim: the real annotation ships with the IDE, not with the repository.
 * Only {@code RetentionPolicy.CLASS} matters for compilation.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.LOCAL_VARIABLE})
public @interface NotNull {
}
