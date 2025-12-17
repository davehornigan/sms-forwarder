package pro.hornigan.smsforwarder

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4


@RunWith(AndroidJUnit4::class)
class AppNavigationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val permissionsRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS
    )

    @Test
    fun testNavigationBetweenScreens() {
        val context = composeTestRule.activity
        val settingsContentDescription = context.getString(R.string.settings)
        val backContentDescription = context.getString(R.string.back)

        // 1. Verify Main screen is displayed
        composeTestRule.onNodeWithContentDescription(settingsContentDescription).assertIsDisplayed()

        // 2. Navigate to Settings screen
        composeTestRule.onNodeWithContentDescription(settingsContentDescription).performClick()

        // 3. Verify Settings screen is displayed
        composeTestRule.onNodeWithTag("settings_screen").assertIsDisplayed()

        // 4. Navigate back to Main screen
        composeTestRule.onNodeWithContentDescription(backContentDescription).performClick()

        // 5. Verify Main screen is displayed again
        composeTestRule.onNodeWithContentDescription(settingsContentDescription).assertIsDisplayed()
    }
}
