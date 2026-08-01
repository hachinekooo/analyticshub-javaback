package com.github.analyticshub.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class TwoFactorAuthServiceTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(TwoFactorAuthService.class);

    @Test
    void enabledTwoFactorAuthenticationWithoutSecretFailsDuringStartup() {
        contextRunner
                .withPropertyValues("app.security.2fa.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessage("2FA is enabled but APP_SECURITY_2FA_SECRET is blank");
                });
    }

    @Test
    void enabledTwoFactorAuthenticationWithBlankSecretFailsDuringStartup() {
        contextRunner
                .withPropertyValues(
                        "app.security.2fa.enabled=true",
                        "app.security.2fa.secret=   "
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessage("2FA is enabled but APP_SECURITY_2FA_SECRET is blank");
                });
    }

    @Test
    void disabledTwoFactorAuthenticationDoesNotRequireSecret() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TwoFactorAuthService.class);
            assertThat(context.getBean(TwoFactorAuthService.class).isEnabled()).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "JBSWY3DPEHPK3PXP",
            "jbswy3dpehpk3pxp",
            "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
    })
    void enabledTwoFactorAuthenticationWithValidBase32SecretStartsNormally(String secret) {
        contextRunner
                .withPropertyValues(
                        "app.security.2fa.enabled=true",
                        "app.security.2fa.secret=" + secret
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(TwoFactorAuthService.class).isEnabled()).isTrue();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "test-totp-secret",
            "JBSWY3DPEHPK3PX1",
            "JBSWY3DPEHPK3PXP=",
            "JBSWY3DPEHPK3PXPA",
            "JBSWY3DPEHPK3PX"
    })
    void enabledTwoFactorAuthenticationWithInvalidBase32SecretFailsDuringStartup(String secret) {
        contextRunner
                .withPropertyValues(
                        "app.security.2fa.enabled=true",
                        "app.security.2fa.secret=" + secret
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessage("APP_SECURITY_2FA_SECRET must be a valid unpadded Base32 TOTP secret")
                            .hasMessageNotContaining(secret);
                });
    }
}
