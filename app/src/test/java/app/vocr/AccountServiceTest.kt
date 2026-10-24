package app.vocr

import app.vocr.sphere.AccountService
import org.junit.Assert.*
import org.junit.Before
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

    @Before fun cleanService() = AccountService.resetForTesting()

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
        // Deterministic: the test decides that no config exists, whatever the dev environment's real
        // (gitignored) assets/google-services.json holds. The honest status is NotConnected, never a crash.
        AccountService.assetOverride = { null }
        assertEquals(AccountService.Status.NotConnected, AccountService.status(RuntimeEnvironment.getApplication()))
    }

    @Test fun aConfiguredBuildReportsSignedOutAndExposesTheWebClientId() {
        AccountService.assetOverride = { fixture }
        val app = RuntimeEnvironment.getApplication()
        assertEquals("123-x.apps.googleusercontent.com", AccountService.webClientId(app))
        assertEquals(AccountService.Status.SignedOut, AccountService.status(app))
    }

    @Test fun aRealGoogleServicesFileParses() {
        // The development environment carries the real (gitignored) config; if present it must parse and
        // carry the project + web client the sign-in flow needs. Skipped where no file exists (CI).
        val f = java.io.File("src/main/assets/google-services.json")
        org.junit.Assume.assumeTrue("no local google-services.json", f.isFile)
        val c = AccountService.parseConfig(f.readText())
        assertNotNull(c)
        assertEquals("sphere-9c2b4", c!!.projectId)
        assertNotNull("Google provider enabled => web OAuth client present", c.webClientId)
    }
}
