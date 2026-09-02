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
                .contains(FeaturePermission.CLASSROOMS, FeaturePermission.PCS, FeaturePermission.REPORTS)
                .doesNotContain(FeaturePermission.COMMANDS, FeaturePermission.FILES, FeaturePermission.TOKENS);
    }

    @Test
    void explicitOperatorSelectionIsUsedWithoutAddingDefaults() {
        User user = new User("operator", "encoded", "operator@example.com", "USER");
        user.changeFeaturePermissions("FILES,COMMANDS");

        assertThat(service.resolve(user, "USER"))
                .containsExactlyInAnyOrder(FeaturePermission.FILES, FeaturePermission.COMMANDS);
    }

    @Test
    void viewerCannotReceiveOperatorOnlyFeaturesFromStoredData() {
        User user = new User("viewer", "encoded", "viewer@example.com", "VIEWER");
        user.changeFeaturePermissions("FILES,TOKENS");

        assertThat(service.resolve(user, "VIEWER"))
                .contains(FeaturePermission.CLASSROOMS, FeaturePermission.REPORTS)
                .doesNotContain(FeaturePermission.FILES, FeaturePermission.TOKENS);
    }
}
