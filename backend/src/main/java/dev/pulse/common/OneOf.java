package dev.pulse.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** The number must be one of the listed values (null is allowed; add @NotNull to forbid it). */
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = OneOfValidator.class)
public @interface OneOf {

	int[] value();

	String message() default "Value is not allowed.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
