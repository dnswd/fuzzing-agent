package id.dendenden;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that a class or method should be instrumented by the agent.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Instrumented {
    /**
     * Optional comment about why this element is being instrumented.
     */
    String value() default "";
}
