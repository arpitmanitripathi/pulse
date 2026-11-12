package dev.pulse.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CommonPasswordsTest {

	final CommonPasswords common = new CommonPasswords();

	@Test
	void theBundledListIsLoadedWithoutItsCommentLines() {
		assertThat(common.size()).isGreaterThan(9000);
		assertThat(common.contains("# Common passwords refused at registration and password reset (docs/DESIGN.md).")).isFalse();
	}

	@Test
	void matchesWhateverTheCase() {
		assertThat(common.contains("qwertyuiop")).isTrue();
		assertThat(common.contains("QwErTyUiOp")).isTrue();
		assertThat(common.contains("correct horse battery staple")).isFalse();
	}

}
