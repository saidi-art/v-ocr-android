package app.vocr

import app.vocr.sphere.AccountService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountServiceTest {
    private val fixture = """
      {"project_info": {"project_id": "sphere-dev", "project_number": "123", "firebase_url": "https://sphere-dev.firebaseio.com"},
       "client": [{"client_info": {"mobilesdk_app_id": "1:123:android:abc"},
                   "oauth_client": [{"client_id": "123-x.apps.googleusercontent.com", "client_type": 3}],
                   "api_key": [{"current_key": "AIza-fixture"}]}]}"""

    @Test fun parsesAFullConfig() {
        val c = AccountService.parseConfig(fixture)
        assertNotNull(c)
        assertEquals("sphere-dev", c!!.projectId)
        assertEquals("1:123:android:abc", c.appId)
        assertEquals("AIza-fixture", c.apiKey)
        assertEquals("123-x.apps.googleusercontent.com", c.webClientId)
    }

    @Test fun missingFieldsMeanNotConfigured() {
        assertNull(AccountService.parseConfig("{}"))
        assertNull(AccountService.parseConfig("not json"))
        assertNull(AccountService.parseConfig("""{"project_info": {"project_id": "x"}}"""))
    }

    @Test fun aBuildWithoutConfigurationReportsNotConnected() {
        // no assets/google-services.json exists in this build: the honest status is NotConnected, never a crash
        assertEquals(AccountService.Status.NotConnected, AccountService.status(RuntimeEnvironment.getApplication()))
    }
}
