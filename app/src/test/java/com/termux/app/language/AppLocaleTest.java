package com.termux.app.language;

import android.app.Activity;
import android.content.Context;

import com.termux.R;
import com.termux.shared.locale.AppLocale;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class AppLocaleTest {

    /** An activity that applies the chosen language like the app's activities. */
    public static class LocalizedActivity extends Activity {
        @Override
        protected void attachBaseContext(Context newBase) {
            super.attachBaseContext(AppLocale.wrap(newBase));
        }
    }

    @After
    public void tearDown() {
        AppLocale.set(RuntimeEnvironment.application, AppLocale.SYSTEM);
    }

    @Test
    public void testWrap() {
        Context context = RuntimeEnvironment.application;
        Assert.assertEquals(AppLocale.SYSTEM, AppLocale.get(context));
        Assert.assertEquals("Language", AppLocale.wrap(context).getString(R.string.title_language));
        for (String[] expected : new String[][]{{"ko", "언어"}, {"ja", "言語"}, {"zh", "语言"}, {"en", "Language"}}) {
            AppLocale.set(context, expected[0]);
            Assert.assertEquals(expected[0], AppLocale.get(context));
            Assert.assertEquals(expected[1], AppLocale.wrap(context).getString(R.string.title_language));
        }
    }

    @Test
    public void testActivity() {
        AppLocale.set(RuntimeEnvironment.application, "ko");
        Activity activity = Robolectric.buildActivity(LocalizedActivity.class).setup().get();
        Assert.assertEquals("언어", activity.getString(R.string.title_language));
        Assert.assertFalse(AppLocale.isOutdated(activity));
        // Changed while the activity is shown: it is outdated until it is created again.
        AppLocale.set(RuntimeEnvironment.application, "ja");
        Assert.assertTrue(AppLocale.isOutdated(activity));
        Activity recreated = Robolectric.buildActivity(LocalizedActivity.class).setup().get();
        Assert.assertEquals("言語", recreated.getString(R.string.title_language));
    }

}
