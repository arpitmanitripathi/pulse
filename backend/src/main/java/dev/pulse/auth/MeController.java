package dev.pulse.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.pulse.common.ApiException;
import dev.pulse.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Auth")
@SecurityRequirement(name = "bearer")
public class MeController {

	private final UserRepository users;

	public MeController(UserRepository users) {
		this.users = users;
	}

	@GetMapping("/api/me")
	@Operation(summary = "The logged-in user")
	public AuthController.UserResponse me(@AuthenticationPrincipal Jwt jwt) {
		User user = users.findById(CurrentUser.id(jwt))
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found."));
		return new AuthController.UserResponse(user.getId(), user.getEmail());
	}

}
