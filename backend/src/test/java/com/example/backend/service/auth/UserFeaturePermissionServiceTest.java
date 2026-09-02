package com.example.backend.service.auth;

import com.example.backend.model.user.FeaturePermission;
import com.example.backend.model.user.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserFeaturePermissionServiceTest {

    private final UserFeaturePermissionService service = new UserFeaturePermissionService();

    @Test
    void administratorAlwaysReceivesEveryFeature() {
        User user = new User("admin", "encoded", "admin@example.com", "ADMIN");
        user.changeFeaturePermissions("");

        assertThat(service.resolve(user, "ADMIN")).containsExactlyInAnyOrder(FeaturePermission.values());
    }

    @Test
    void legacyOperatorKeepsTheExistingVisibleModules() {
        User user = new User("operator", "encoded", "operator@example.com", "USER");

        assertThat(service.resolve(user, "USER"))
                .contains(FeaturePermission.CLASSROOM_VIEW, FeaturePermission.PC_VIEW, FeaturePermission.REPORT_VIEW,
                        FeaturePermission.POWER_ON, FeaturePermission.SCMATE_BASELINE_UPDATE)
                .doesNotContain(FeaturePermission.COMMAND_VIEW, FeaturePermission.FILE_DISTRIBUTION_VIEW,
                        FeaturePermission.TOKEN_VIEW);
    }

    @Test
    void explicitOperatorSelectionIsUsedWithoutAddingDefaults() {
        User user = new User("operator", "encoded", "operator@example.com", "USER");
        user.changeFeaturePermissions("POWER_ON,COMMAND_VIEW");

        assertThat(service.resolve(user, "USER"))
                .containsExactlyInAnyOrder(FeaturePermission.POWER_ON, FeaturePermission.COMMAND_VIEW);
    }

    @Test
    void viewerCannotReceiveOperatorOnlyFeaturesFromStoredData() {
        User user = new User("viewer", "encoded", "viewer@example.com", "VIEWER");
        user.changeFeaturePermissions("FILES,TOKENS");

        assertThat(service.resolve(user, "VIEWER"))
                .contains(FeaturePermission.CLASSROOM_VIEW, FeaturePermission.REPORT_VIEW)
                .doesNotContain(FeaturePermission.FILE_DISTRIBUTION_VIEW, FeaturePermission.TOKEN_VIEW);
    }
}
