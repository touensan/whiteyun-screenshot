package com.whiteyun.screenshot;

import android.app.Service;
import android.content.Context;
import android.content.res.Resources;

abstract class LocalizedService extends Service {
    private Context systemBaseContext;

    @Override
    protected void attachBaseContext(Context base) {
        systemBaseContext = base;
        super.attachBaseContext(localizedContext(base));
    }

    @Override
    public Resources getResources() {
        return systemBaseContext == null
                ? super.getResources()
                : localizedContext(systemBaseContext).getResources();
    }

    private Context localizedContext(Context base) {
        // Services can outlive the Activity that changed the app locale.
        return AppLocale.forTag(base, AppLocale.currentTag(base));
    }
}
