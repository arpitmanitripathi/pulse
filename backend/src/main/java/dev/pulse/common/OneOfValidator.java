package dev.pulse.common;

import java.util.Arrays;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class OneOfValidator implements ConstraintValidator<OneOf, Integer> {

	private int[] allowed;

	@Override
	public void initialize(OneOf annotation) {
		this.allowed = annotation.value();
	}

	@Override
	public boolean isValid(Integer value, ConstraintValidatorContext context) {
		return value == null || Arrays.stream(allowed).anyMatch(a -> a == value);
	}

}
